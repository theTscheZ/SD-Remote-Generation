package com.a1111remote.app.data

import android.util.Base64
import com.a1111remote.app.model.ConnectionSettings
import com.a1111remote.app.model.GenerationSettings
import com.a1111remote.app.model.GenerationMode
import com.a1111remote.app.model.ExtrasSettings
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
    val adetailerModels: List<String>,
    val upscalers: List<String> = emptyList()
)

data class Progress(val fraction: Float, val currentImage: ByteArray?, val remoteActive: Boolean)
data class PcImage(val id: String, val name: String, val modified: Long)

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
        val selectedClient = if (path.startsWith("/sdapi/v1/txt2img") || path.startsWith("/sdapi/v1/img2img") || path.startsWith("/sdapi/v1/extra-single-image")) client else metadataClient
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
        val upscalers = runCatching { array(baseUrl, auth, "/sdapi/v1/upscalers", "name") }.getOrDefault(emptyList())
        ServerOptions(models, samplers, schedulers, loras, options.optString("sd_model_checkpoint"), adScripts.isNotEmpty(), availableAdModels, upscalers)
    }

    suspend fun generate(baseUrl: String, auth: ConnectionSettings, settings: GenerationSettings,
                         mode: GenerationMode, sourceImage: ByteArray?, extras: ExtrasSettings): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val payload: JSONObject
            val endpoint: String
            when (mode) {
                GenerationMode.TXT2IMG -> {
                    endpoint = "/sdapi/v1/txt2img"
                    payload = settings.toPayload()
                }
                GenerationMode.IMG2IMG -> {
                    val image = sourceImage ?: throw ApiException("Für img2img zuerst ein Ausgangsbild auswählen.")
                    endpoint = "/sdapi/v1/img2img"
                    payload = settings.toPayload()
                        .put("enable_hr", false)
                        .put("init_images", JSONArray().put(Base64.encodeToString(image, Base64.NO_WRAP)))
                        .put("denoising_strength", settings.img2imgDenoising.coerceIn(0f, 1f).toDouble())
                        .put("include_init_images", false)
                        .put("resize_mode", 0)
                }
                GenerationMode.EXTRAS -> {
                    val image = sourceImage ?: throw ApiException("Für Extras zuerst ein Bild auswählen.")
                    endpoint = "/sdapi/v1/extra-single-image"
                    payload = JSONObject()
                        .put("image", Base64.encodeToString(image, Base64.NO_WRAP))
                        .put("upscaler_1", extras.upscaler)
                        .put("upscaler_2", "None")
                        .put("extras_upscaler_2_visibility", 0.0)
                        .put("upscaling_resize", extras.scale.coerceIn(1f, 4f).toDouble())
                        .put("upscaling_resize_w", 0).put("upscaling_resize_h", 0)
                        .put("upscaling_crop", false)
                        .put("gfpgan_visibility", extras.gfpgan.coerceIn(0f, 1f).toDouble())
                        .put("codeformer_visibility", extras.codeformer.coerceIn(0f, 1f).toDouble())
                        .put("codeformer_weight", extras.codeformerWeight.coerceIn(0f, 1f).toDouble())
                }
            }
            val result = request(baseUrl, auth, endpoint, payload)
            if (mode == GenerationMode.EXTRAS) {
                val encoded = result.optString("image")
                if (encoded.isBlank()) throw ApiException("Extras hat kein Bild zurückgegeben.")
                listOf(decodeImage(encoded))
            } else {
                val images = result.optJSONArray("images")
                    ?: throw ApiException("Server hat keine Bilder zurückgegeben.")
                (0 until images.length()).map { index -> decodeImage(images.getString(index)) }
            }
        }

    suspend fun progress(baseUrl: String, auth: ConnectionSettings, withImage: Boolean): Progress = withContext(Dispatchers.IO) {
        val result = request(baseUrl, auth, "/sdapi/v1/progress?skip_current_image=${!withImage}")
        val encoded = result.optString("current_image")
        Progress(result.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            if (withImage && encoded.isNotEmpty()) runCatching { decodeImage(encoded) }.getOrNull() else null,
            result.optJSONObject("state")?.optString("job").orEmpty().isNotBlank())
    }

    suspend fun interrupt(baseUrl: String, auth: ConnectionSettings) = withContext(Dispatchers.IO) {
        request(baseUrl, auth, "/sdapi/v1/interrupt", JSONObject())
    }

    suspend fun skip(baseUrl: String, auth: ConnectionSettings) = withContext(Dispatchers.IO) {
        request(baseUrl, auth, "/sdapi/v1/skip", JSONObject())
    }


    /** Companion extension is opt-in and only accessible with the same --api-auth credentials. */
    suspend fun bridgeAvailable(baseUrl: String, auth: ConnectionSettings): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { request(baseUrl, auth, "/sd-remote/v1/status").optInt("version") == 1 }
                .getOrDefault(false)
        }

    suspend fun lastPcParameters(baseUrl: String, auth: ConnectionSettings, mode: GenerationMode): JSONObject =
        withContext(Dispatchers.IO) { request(baseUrl, auth, "/sd-remote/v1/last?mode=${mode.name.lowercase()}") }

    suspend fun pcImages(baseUrl: String, auth: ConnectionSettings): List<PcImage> =
        withContext(Dispatchers.IO) {
            val items = request(baseUrl, auth, "/sd-remote/v1/images").optJSONArray("images") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (!id.matches(Regex("[a-f0-9]{32}"))) return@mapNotNull null
                PcImage(id, item.optString("name"), item.optLong("modified"))
            }
        }

    private fun pcImageBytes(baseUrl: String, auth: ConnectionSettings, id: String, preview: Boolean): ByteArray {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Ungültige Bild-ID." }
        val endpoint = if (preview) "preview" else "file"
        val builder = Request.Builder().url("${normalizePrivateUrl(baseUrl)}/sd-remote/v1/images/$id/$endpoint")
        if (auth.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(auth.username, auth.password))
        }
        client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .build().newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("PC-Bild: HTTP ${response.code}")
                val body = response.body ?: throw ApiException("Leere Bilddatei.")
                if (body.contentLength() > 80_000_000L) throw ApiException("Bilddatei zu groß.")
                return body.bytes().also {
                    if (it.size > 80_000_000) throw ApiException("Bilddatei zu groß.")
                }
            }
    }

    suspend fun pcThumbnail(baseUrl: String, auth: ConnectionSettings, id: String): ByteArray =
        withContext(Dispatchers.IO) { pcImageBytes(baseUrl, auth, id, true) }

    suspend fun pcImageDownload(baseUrl: String, auth: ConnectionSettings, id: String): ByteArray =
        withContext(Dispatchers.IO) { pcImageBytes(baseUrl, auth, id, false) }

    private fun decodeImage(encoded: String): ByteArray = Base64.decode(encoded.substringAfterLast(','), Base64.DEFAULT)
}
