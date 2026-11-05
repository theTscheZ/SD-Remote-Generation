package com.a1111remote.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.a1111remote.app.model.ConnectionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

data class PcControllerStatus(
    val a1111Reachable: Boolean,
    val a1111Managed: Boolean,
    val shutdownPending: Boolean
)

/** Local/Tailscale only; separate token, never the A1111 API password. */
class PcControlClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    companion object {
        private const val CONTROL_PORT = 42001
        private val VALID_ACTIONS = setOf("start", "stop", "shutdown", "shutdown/cancel")

        fun controllerUrl(a1111Url: String): String {
            val clean = A1111Api.normalizePrivateUrl(a1111Url)
            val uri = URI(clean)
            val host = uri.host ?: throw IllegalArgumentException("PC-IP fehlt.")
            val ip = host.split('.').map { it.toIntOrNull() }
            require(ip.size == 4 && ip.all { it != null && it in 0..255 }) {
                "PC-Steuerung erfordert eine private IPv4-Adresse."
            }
            val first = ip[0]!!
            val private = first == 10 ||
                (first == 192 && ip[1] == 168) ||
                (first == 172 && ip[1]!! in 16..31) ||
                (first == 100 && ip[1]!! in 64..127)
            require(private) { "Nur LAN- und Tailscale-IP-Adressen sind erlaubt." }
            return "${uri.scheme}://$host:$CONTROL_PORT"
        }

        fun parseMac(raw: String): ByteArray {
            val cleaned = raw.trim().replace("-", "").replace(":", "")
            require(cleaned.matches(Regex("[a-fA-F0-9]{12}"))) {
                "Bitte eine gültige MAC-Adresse eingeben (z. B. AA:BB:CC:DD:EE:FF)."
            }
            return ByteArray(6) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private fun addresses(connection: ConnectionSettings): List<String> =
        listOf(connection.lanUrl, connection.vpnUrl)
            .filter { it.isNotBlank() }.distinct().map(::controllerUrl)

    private fun request(url: String, token: String, path: String, post: Boolean): JSONObject {
        require(token.length >= 32) { "Steuerungs-Token fehlt oder ist zu kurz." }
        val builder = Request.Builder().url("$url/v1/$path")
            .header("X-Controller-Token", token)
            .header("Cache-Control", "no-store")
        if (post) builder.post(okhttp3.RequestBody.create(null, ByteArray(0))) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string()?.let(::JSONObject) ?: JSONObject()
            if (!response.isSuccessful) {
                throw IllegalStateException(body.optString("error", "PC-Steuerung: HTTP ${response.code}"))
            }
            return body
        }
    }

    private suspend fun call(connection: ConnectionSettings, token: String, action: String, post: Boolean) =
        withContext(Dispatchers.IO) {
            val urls = addresses(connection)
            require(urls.isNotEmpty()) { "Bitte zuerst die PC-IP in Verbindung eintragen." }
            var error: Exception? = null
            for (url in urls) {
                try {
                    return@withContext request(url, token, action, post)
                } catch (e: Exception) {
                    error = e
                    if (e is IllegalStateException || e is IllegalArgumentException) break
                }
            }
            throw error ?: IllegalStateException("PC-Steuerung nicht erreichbar.")
        }

    suspend fun status(connection: ConnectionSettings, token: String): PcControllerStatus {
        val json = call(connection, token, "status", post = false)
        return PcControllerStatus(
            json.optBoolean("a1111_reachable"),
            json.optBoolean("a1111_managed"),
            json.optBoolean("shutdown_pending")
        )
    }

    suspend fun action(connection: ConnectionSettings, token: String, action: String): String {
        require(action in VALID_ACTIONS) { "Ungültige Aktion." }
        return call(connection, token, action, post = true).optString("message")
    }

    suspend fun wake(context: Context, mac: String) = withContext(Dispatchers.IO) {
        val bytes = parseMac(mac)
        val magic = ByteArray(6 + bytes.size * 16) { index ->
            if (index < 6) 0xff.toByte() else bytes[(index - 6) % 6]
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: throw IllegalStateException("Wake-on-LAN funktioniert hier nur über das Heim-WLAN.")
        DatagramSocket().use { socket ->
            wifiNetwork.bindSocket(socket)
            socket.broadcast = true
            val target = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(magic, magic.size, target, 9)
            repeat(3) { socket.send(packet) }
        }
    }
}
