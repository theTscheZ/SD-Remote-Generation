package com.a1111remote.app.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a1111remote.app.data.A1111Api
import com.a1111remote.app.data.SecureStore
import com.a1111remote.app.data.ServerOptions
import com.a1111remote.app.model.ConnectionSettings
import com.a1111remote.app.model.GenerationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Local-only gallery. Remote browsing of previous PC images is a later milestone. */
data class LocalImage(val file: File, val preview: Bitmap, val label: String)

data class UiState(
    val connection: ConnectionSettings = ConnectionSettings(),
    val settings: GenerationSettings = GenerationSettings(),
    val connectedUrl: String = "",
    val options: ServerOptions? = null,
    val isConnecting: Boolean = false,
    val isGenerating: Boolean = false,
    val progress: Float = 0f,
    val preview: Bitmap? = null,
    val generated: List<Bitmap> = emptyList(),
    val gallery: List<LocalImage> = emptyList(),
    val message: String = ""
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val api = A1111Api()
    var state by androidx.compose.runtime.mutableStateOf(
        UiState(connection = store.connection(), settings = store.generation())
    )
        private set

    private var progressJob: Job? = null
    private var generationJob: Job? = null

    init { refreshGallery() }

    fun editConnection(transform: (ConnectionSettings) -> ConnectionSettings) {
        val updated = transform(state.connection)
        state = state.copy(connection = updated, connectedUrl = "", options = null)
        store.saveConnection(updated)
    }

    fun editSettings(transform: (GenerationSettings) -> GenerationSettings) {
        val updated = transform(state.settings)
        state = state.copy(settings = updated)
        store.saveGeneration(updated)
    }

    fun connect() {
        if (state.isConnecting || state.isGenerating) return
        viewModelScope.launch {
            state = state.copy(isConnecting = true, message = "Verbinde …")
            val config = state.connection
            val urls = listOf(config.lanUrl, config.vpnUrl).filter { it.isNotBlank() }.distinct()
            var lastError = "Keine Adresse angegeben."
            var established = false
            for (url in urls) {
                try {
                    val options = api.probe(url, config)
                    state = state.copy(connectedUrl = A1111Api.normalizePrivateUrl(url), options = options,
                        isConnecting = false, message = "Verbunden: ${A1111Api.normalizePrivateUrl(url)}")
                    established = true
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Verbindung fehlgeschlagen."
                }
            }
            if (!established) state = state.copy(connectedUrl = "", options = null,
                isConnecting = false, message = "Nicht verbunden: $lastError")
        }
    }

    fun generate() {
        if (state.isGenerating) return
        if (state.connectedUrl.isBlank()) {
            state = state.copy(message = "Zuerst unter Verbindung den PC verbinden.")
            return
        }
        generationJob = viewModelScope.launch {
            val url = state.connectedUrl
            val credentials = state.connection
            val settings = state.settings
            state = state.copy(isGenerating = true, generated = emptyList(), preview = null,
                progress = 0f, message = "Generierung läuft …")
            progressJob?.cancel()
            progressJob = launch {
                var poll = 0
                while (isActive) {
                    delay(1250)
                    // Progress is best-effort; generation should not fail due to a polling timeout.
                    runCatching { api.progress(url, credentials, withImage = (++poll % 3 == 0)) }
                        .onSuccess { progress ->
                            state = state.copy(progress = progress.fraction,
                                preview = progress.currentImage?.let(::decodeBitmap) ?: state.preview)
                        }
                }
            }
            try {
                val images = api.generate(url, credentials, settings)
                withContext(Dispatchers.IO) { saveLocal(images) }
                state = state.copy(generated = images.mapNotNull(::decodeBitmap), progress = 1f,
                    message = "${images.size} Bild(er) fertig und lokal gespeichert.")
                refreshGallery()
            } catch (e: CancellationException) {
                state = state.copy(message = "Anfrage abgebrochen. Möglicherweise arbeitet der PC weiter.")
            } catch (e: Exception) {
                state = state.copy(message = "Generierung fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}")
            } finally {
                progressJob?.cancel()
                state = state.copy(isGenerating = false)
            }
        }
    }

    fun interrupt() {
        if (!state.isGenerating) return
        viewModelScope.launch {
            try {
                api.interrupt(state.connectedUrl, state.connection)
                state = state.copy(message = "Interrupt an AUTOMATIC1111 gesendet.")
            } catch (e: Exception) {
                state = state.copy(message = "Interrupt fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun skip() {
        if (!state.isGenerating) return
        viewModelScope.launch {
            try {
                api.skip(state.connectedUrl, state.connection)
                state = state.copy(message = "Skip an AUTOMATIC1111 gesendet.")
            } catch (e: Exception) {
                state = state.copy(message = "Skip fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun appendLora(name: String, weight: String) {
        val formatted = weight.toFloatOrNull()?.coerceIn(-5f, 5f) ?: 1f
        editSettings { it.copy(prompt = (it.prompt.trimEnd() + " <lora:$name:$formatted>").trim()) }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun outputDir(): File = File(getApplication<Application>().filesDir, "images").apply { mkdirs() }

    private fun saveLocal(images: List<ByteArray>) {
        images.forEach { bytes ->
            File(outputDir(), "a1111_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.png").writeBytes(bytes)
        }
    }

    fun refreshGallery() {
        viewModelScope.launch {
            val gallery = withContext(Dispatchers.IO) {
                outputDir().listFiles()?.filter { it.isFile && it.extension == "png" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(120)
                    ?.mapNotNull { file ->
                        try {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)
                            val scale = (maxOf(bounds.outHeight, bounds.outWidth) / 512).coerceAtLeast(1)
                            val options = BitmapFactory.Options().apply { inSampleSize = scale }
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                            bitmap?.let { LocalImage(file, it, DateFormat.getDateTimeInstance().format(Date(file.lastModified()))) }
                        } catch (_: Exception) { null }
                    }.orEmpty()
            }
            state = state.copy(gallery = gallery)
        }
    }

    fun exportImage(image: LocalImage) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = image.file.name
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A1111 Remote")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("MediaStore konnte keine Datei anlegen.")
                    try {
                        resolver.openOutputStream(uri)?.use { out -> image.file.inputStream().use { it.copyTo(out) } }
                            ?: throw IOException("Schreibzugriff nicht verfügbar.")
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }
                state = state.copy(message = "Bild in Pictures/A1111 Remote gespeichert.")
            } catch (e: Exception) {
                state = state.copy(message = "Export fehlgeschlagen: ${e.message}")
            }
        }
    }
}
