package com.a1111remote.app.data

import android.util.Base64
import com.a1111remote.app.model.ConnectionSettings
import com.a1111remote.app.model.GenerationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

data class ServerOptions(
    val checkpoints: List<String>,
    val samplers: List<String>,
    val schedulers: List<String>,
    val loras: List<String>,
    val activeCheckpoint: String,
    val adetailerInstalled: Boolean,
    val adetailerModels: List<String>
)

data class Progress(val fraction: Float, val currentImage: ByteArray?)

/** Requests are scoped to one explicitly configured private endpoint. */
class A1111Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // generation can take minutes
        .build()
    private val metadataClient = client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        /** Prevent accidentally sending Basic credentials to an internet-facing HTTP host. */
        fun normalizePrivateUrl(raw: String): String {
            val input = raw.trim().trimEnd('/')
            require(input.isNotEmpty()) { "Bitte eine PC-Adresse eingeben." }
            val uri = runCatching { URI(input) }.getOrNull()
                ?: throw IllegalArgumentException("Ungültige URL.")
            require(uri.scheme == "http" || uri.scheme == "https") { "Nur http:// oder https:// erlaubt." }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "URL ohne Benutzer, Query oder Fragment eingeben." }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Bitte nur die Basisadresse mit Port eingeben." }
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Hostname fehlt.")
            val p = host.split('.').map { it.toIntOrNull() }
            val ipv4 = p.size == 4 && p.all { it != null && it in 0..255 }
            val privateHost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
                (ipv4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) ||
                    (p[0] == 172 && (p[1]?.let { it in 16..31 } == true)) ||
                    (p[0] == 100 && (p[1]?.let { it in 64..127 } == true))))
            require(uri.scheme == "https" || privateHost) {
                "Für HTTP nur lokale oder Tailscale-Adressen verwenden."
            }
            return input
        }

    }

    private fun request(baseUrl: String, credentials: ConnectionSettings, path: String, payload: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url("${normalizePrivateUrl(baseUrl)}$path")
        if (credentials.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(credentials.username, credentials.password))
        }
        if (payload == null) builder.get()
        else builder.post(payload.toString().toRequestBody(jsonMediaType))
        val selectedClient = if (path.startsWith("/sdapi/v1/txt2img")) client else metadataClient
        selectedClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val description = when (response.code) {
                    401, 403 -> "Authentifizierung fehlgeschlagen (${response.code})."
                    404 -> "API-Endpunkt nicht gefunden (404). --api aktiviert?"
                    else -> "A1111 antwortet mit HTTP ${response.code}."
                }
                throw ApiException(description)
            }
            val content = response.body?.string() ?: throw ApiException("Leere Serverantwort.")
            return JSONObject(content)
        }
    }

    private fun array(baseUrl: String, auth: ConnectionSettings, endpoint: String, field: String): List<String> =
        requestArray(baseUrl, auth, endpoint).mapNotNull { it.optString(field).takeIf(String::isNotEmpty) }

    private fun requestArray(baseUrl: String, auth: ConnectionSettings, endpoint: String): List<JSONObject> {
        val builder = Request.Builder().url("${normalizePrivateUrl(baseUrl)}$endpoint")
        if (auth.username.isNotBlank()) builder.header("Authorization", Credentials.basic(auth.username, auth.password))
        metadataClient.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("$endpoint: HTTP ${response.code}")
            val json = JSONArray(response.body?.string() ?: "[]")
            return (0 until json.length()).mapNotNull { json.optJSONObject(it) }
        }
    }

    suspend fun probe(baseUrl: String, auth: ConnectionSettings): ServerOptions = withContext(Dispatchers.IO) {
        val options = request(baseUrl, auth, "/sdapi/v1/options")
        val models = array(baseUrl, auth, "/sdapi/v1/sd-models", "title")
        val samplers = array(baseUrl, auth, "/sdapi/v1/samplers", "name")
        val schedulers = runCatching { array(baseUrl, auth, "/sdapi/v1/schedulers", "name") }.getOrDefault(emptyList())
        val loras = runCatching { array(baseUrl, auth, "/sdapi/v1/loras", "name") }.getOrDefault(emptyList())
        val scripts = runCatching { requestArray(baseUrl, auth, "/sdapi/v1/script-info") }.getOrDefault(emptyList())
        val adScripts = scripts.filter { it.optString("name").contains("adetailer", ignoreCase = true) }
        val availableAdModels = adScripts.flatMap { script ->
            val args = script.optJSONArray("args") ?: JSONArray()
            (0 until args.length()).flatMap { idx ->
                val option = args.optJSONObject(idx) ?: return@flatMap emptyList<String>()
                if (!option.optString("label").contains("ADetailer detector", ignoreCase = true)) {
                    emptyList()
                } else {
                    val choices = option.optJSONArray("choices") ?: JSONArray()
                    (0 until choices.length()).mapNotNull { pos ->
                        choices.optString(pos).takeIf { it.isNotBlank() && it != "None" }
                    }
                }
            }
        }.distinct()
        ServerOptions(models, samplers, schedulers, loras, options.optString("sd_model_checkpoint"), adScripts.isNotEmpty(), availableAdModels)
    }

    suspend fun generate(baseUrl: String, auth: ConnectionSettings, settings: GenerationSettings): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val obj = request(baseUrl, auth, "/sdapi/v1/txt2img", settings.toPayload())
            val images = obj.optJSONArray("images") ?: throw ApiException("Server hat keine Bilder zurückgegeben.")
            (0 until images.length()).map { index -> decodeImage(images.getString(index)) }
        }

    suspend fun progress(baseUrl: String, auth: ConnectionSettings, withImage: Boolean): Progress = withContext(Dispatchers.IO) {
        val result = request(baseUrl, auth, "/sdapi/v1/progress?skip_current_image=${!withImage}")
        val encoded = result.optString("current_image")
        Progress(result.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            if (withImage && encoded.isNotEmpty()) runCatching { decodeImage(encoded) }.getOrNull() else null)
    }

    suspend fun interrupt(baseUrl: String, auth: ConnectionSettings) = withContext(Dispatchers.IO) {
        request(baseUrl, auth, "/sdapi/v1/interrupt", JSONObject())
    }

    suspend fun skip(baseUrl: String, auth: ConnectionSettings) = withContext(Dispatchers.IO) {
        request(baseUrl, auth, "/sdapi/v1/skip", JSONObject())
    }

    private fun decodeImage(encoded: String): ByteArray = Base64.decode(encoded.substringAfterLast(','), Base64.DEFAULT)
}
