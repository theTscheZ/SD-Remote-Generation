package com.a1111remote.app.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a1111remote.app.data.A1111Api
import com.a1111remote.app.GenerationStatusService
import com.a1111remote.app.data.PcImage
import com.a1111remote.app.data.SecureStore
import com.a1111remote.app.data.ServerOptions
import com.a1111remote.app.model.ConnectionSettings
import com.a1111remote.app.model.GenerationSettings
import com.a1111remote.app.model.GenerationMode
import com.a1111remote.app.model.ExtrasSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Local-only gallery. Remote browsing of previous PC images is a later milestone. */
data class LocalImage(val file: File, val preview: Bitmap, val label: String)

data class UiState(
    val connection: ConnectionSettings = ConnectionSettings(),
    val settings: GenerationSettings = GenerationSettings(),
    val mode: GenerationMode = GenerationMode.TXT2IMG,
    val extras: ExtrasSettings = ExtrasSettings(),
    val sourcePreview: Bitmap? = null,
    val connectedUrl: String = "",
    val options: ServerOptions? = null,
    val isConnecting: Boolean = false,
    val isGenerating: Boolean = false,
    val remoteGenerating: Boolean = false,
    val progress: Float = 0f,
    val preview: Bitmap? = null,
    val generated: List<Bitmap> = emptyList(),
    val gallery: List<LocalImage> = emptyList(),
    val bridgeAvailable: Boolean = false,
    val pcImages: List<PcImage> = emptyList(),
    val pcPreviews: Map<String, Bitmap> = emptyMap(),
    val isPcLoading: Boolean = false,
    val isRestoring: Boolean = false,
    val message: String = ""
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val api = A1111Api()
    var state by androidx.compose.runtime.mutableStateOf(
        UiState(connection = store.connection(), settings = store.generation(store.mode()),
            mode = store.mode(), extras = store.extras())
    )
        private set

    private var progressJob: Job? = null
    private var remotePollJob: Job? = null
    private var generationJob: Job? = null
    private val loadingThumbs = mutableSetOf<String>()
    private var sourceBytes: ByteArray? = null

    init { refreshGallery(); connect() }

    fun editConnection(transform: (ConnectionSettings) -> ConnectionSettings) {
        val updated = transform(state.connection)
        state = state.copy(connection = updated, connectedUrl = "", options = null, bridgeAvailable = false, pcImages = emptyList(), pcPreviews = emptyMap())
        store.saveConnection(updated)
    }

    fun editSettings(transform: (GenerationSettings) -> GenerationSettings) {
        val updated = transform(state.settings)
        state = state.copy(settings = updated)
        store.saveGeneration(updated, state.mode)
    }

    fun selectMode(mode: GenerationMode) {
        if (state.isGenerating || state.remoteGenerating || state.mode == mode) return
        store.saveMode(mode)
        state = state.copy(mode = mode,
            settings = store.generation(mode),
            generated = emptyList(), preview = null, progress = 0f)
    }

    fun editExtras(transform: (ExtrasSettings) -> ExtrasSettings) {
        val updated = transform(state.extras)
        state = state.copy(extras = updated)
        store.saveExtras(updated)
    }

    fun pickSourceImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                    if (maxSide <= 0) throw IOException("Das gewählte Bild konnte nicht gelesen werden.")
                    var sample = 1
                    while (maxSide / sample > 2048) sample *= 2
                    val bitmap = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: throw IOException("Bild konnte nicht dekodiert werden.")
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    Pair(bitmap, out.toByteArray())
                }
                sourceBytes = selected.second
                state = state.copy(sourcePreview = selected.first, message = "Ausgangsbild geladen.")
            } catch (error: Exception) {
                state = state.copy(message = "Bildauswahl fehlgeschlagen: ${error.message}")
            }
        }
    }

    fun usePcImageAsSource(image: PcImage) {
        if (!state.bridgeAvailable) return
        viewModelScope.launch {
            try {
                val bytes = api.pcImageDownload(state.connectedUrl, state.connection, image.id)
                val decoded = decodeBitmap(bytes) ?: throw IOException("Bild konnte nicht gelesen werden.")
                sourceBytes = bytes
                state = state.copy(sourcePreview = decoded, message = "PC-Bild als Ausgangsbild gewählt.")
            } catch (error: Exception) {
                state = state.copy(message = "PC-Bild konnte nicht geladen werden: ${error.message}")
            }
        }
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
                    val connected = A1111Api.normalizePrivateUrl(url)
                    val bridge = api.bridgeAvailable(connected, config)
                    state = state.copy(connectedUrl = connected, options = options,
                        isConnecting = false, bridgeAvailable = bridge,
                        message = if (bridge) "Verbunden: $connected" else "Verbunden: $connected · PC-Verlauf benötigt die Erweiterung.")
                    established = true
                    refreshRemoteStatus()
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Verbindung fehlgeschlagen."
                }
            }
            if (!established) state = state.copy(connectedUrl = "", options = null,
                isConnecting = false, message = "Nicht verbunden: $lastError")
        }
    }

    /** Called on activity resume, connection, and after a locally interrupted request. */
    fun refreshRemoteStatus() {
        if (state.connectedUrl.isBlank() || state.isGenerating) return
        remotePollJob?.cancel()
        remotePollJob = viewModelScope.launch {
            var lastSeenActive = state.remoteGenerating
            var pollIndex = 0
            do {
                val response = runCatching {
                    api.progress(state.connectedUrl, state.connection, withImage = (++pollIndex % 3 == 0))
                }.getOrNull() ?: break
                if (state.isGenerating) break
                state = state.copy(
                    remoteGenerating = response.remoteActive,
                    progress = response.fraction,
                    preview = response.currentImage?.let(::decodeBitmap) ?: state.preview
                )
                if (!response.remoteActive) {
                    if (lastSeenActive) {
                        state = state.copy(message = "PC-Auftrag abgeschlossen. Ergebnisse in der PC-Galerie prüfen.")
                        refreshPcGallery()
                    }
                    break
                }
                lastSeenActive = true
                delay(1800)
            } while (!state.isGenerating && state.connectedUrl.isNotBlank())
        }
    }

    fun generate() {
        if (state.isGenerating || state.remoteGenerating) return
        if (state.connectedUrl.isBlank()) {
            state = state.copy(message = "Zuerst unter Verbindung den PC verbinden.")
            return
        }
        if (state.mode != GenerationMode.TXT2IMG && sourceBytes == null) {
            state = state.copy(message = "Zuerst ein Ausgangsbild auswählen.")
            return
        }
        remotePollJob?.cancel()
        generationJob = viewModelScope.launch {
            val mode = state.mode
            val source = sourceBytes
            val extras = state.extras
            val url = state.connectedUrl
            val credentials = state.connection
            val settings = state.settings
            state = state.copy(isGenerating = true, remoteGenerating = false,
                generated = emptyList(), preview = null,
                progress = 0f, message = "Generierung läuft …")
            val app = getApplication<Application>()
            val serviceStarted = runCatching {
                GenerationStatusService.start(app, mode.name.lowercase())
            }.isSuccess
            progressJob?.cancel()
            if (mode != GenerationMode.EXTRAS) progressJob = launch {
                var poll = 0
                while (isActive) {
                    delay(1250)
                    // Progress is best-effort; generation should not fail due to a polling timeout.
                    runCatching { api.progress(url, credentials, withImage = (++poll % 3 == 0)) }
                        .onSuccess { progress ->
                            state = state.copy(progress = progress.fraction,
                                preview = progress.currentImage?.let(::decodeBitmap) ?: state.preview)
                            if (serviceStarted) runCatching {
                                GenerationStatusService.update(app, progress.fraction)
                            }
                        }
                }
            }
            try {
                val images = api.generate(url, credentials, settings, mode, source, extras)
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
                if (serviceStarted) runCatching { GenerationStatusService.finish(app) }
                state = state.copy(isGenerating = false)
            }
        }
    }

    fun interrupt() {
        if (!state.isGenerating && !state.remoteGenerating) return
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
        if (!state.isGenerating && !state.remoteGenerating) return
        viewModelScope.launch {
            try {
                api.skip(state.connectedUrl, state.connection)
                state = state.copy(message = "Skip an AUTOMATIC1111 gesendet.")
            } catch (e: Exception) {
                state = state.copy(message = "Skip fehlgeschlagen: ${e.message}")
            }
        }
    }


    /** Tapping an already-selected LoRA removes its complete tag from the prompt. */
    fun updateAdetailer(index: Int, update: com.a1111remote.app.model.AdetailerSettings.() -> com.a1111remote.app.model.AdetailerSettings) {
        editSettings { old ->
            if (index == 0) old.copy(adetailer = old.adetailer.update())
            else {
                val extras = old.additionalAdetailers.toMutableList()
                if (index - 1 in extras.indices) extras[index - 1] = extras[index - 1].update()
                old.copy(additionalAdetailers = extras)
            }
        }
    }

    fun addAdetailerTab() {
        editSettings { old ->
            if (old.additionalAdetailers.size >= 4) old
            else old.copy(additionalAdetailers = old.additionalAdetailers +
                com.a1111remote.app.model.AdetailerSettings(model = "hand_yolov8n.pt"))
        }
    }

    fun removeAdetailerTab(index: Int) {
        if (index <= 0) return
        editSettings { old ->
            old.copy(additionalAdetailers = old.additionalAdetailers.filterIndexed { pos, _ -> pos != index - 1 })
        }
    }

    fun toggleLora(name: String, weight: String = "1.0") {
        val tag = Regex("<lora:([^:>]+):([^>]+)>", RegexOption.IGNORE_CASE)
        val selected = tag.findAll(state.settings.prompt).any { it.groupValues[1].equals(name, ignoreCase = true) }
        val value = weight.toFloatOrNull()?.coerceIn(-5f, 5f) ?: 1f
        editSettings { old ->
            val prompt = if (selected) {
                tag.replace(old.prompt) { match ->
                    if (match.groupValues[1].equals(name, ignoreCase = true)) "" else match.value
                }.replace(Regex("[ \\t]{2,}"), " ").trim()
            } else {
                (old.prompt.trimEnd() + " <lora:$name:$value>").trim()
            }
            old.copy(prompt = prompt)
        }
    }

    fun restorePcParameters() {
        if (!state.bridgeAvailable || state.connectedUrl.isBlank() || state.isRestoring) {
            state = state.copy(message = "Für PC-Parameter die A1111 Remote-Erweiterung am PC installieren.")
            return
        }
        viewModelScope.launch {
            state = state.copy(isRestoring = true)
            try {
                val data = api.lastPcParameters(state.connectedUrl, state.connection, state.mode)
                if (state.mode == GenerationMode.EXTRAS) {
                    editExtras {
                        it.copy(
                            upscaler = data.optString("upscaler", it.upscaler),
                            scale = data.optDouble("scale", it.scale.toDouble()).toFloat(),
                            gfpgan = data.optDouble("gfpgan", it.gfpgan.toDouble()).toFloat(),
                            codeformer = data.optDouble("codeformer", it.codeformer.toDouble()).toFloat(),
                            codeformerWeight = data.optDouble("codeformer_weight", it.codeformerWeight.toDouble()).toFloat()
                        )
                    }
                    state = state.copy(message = "Extras-Parameter vom PC übernommen.")
                    return@launch
                }
                val old = state.settings
                val checkpoints = state.options?.checkpoints.orEmpty()
                val model = data.optString("model")
                val hash = data.optString("model_hash")
                val checkpoint = checkpoints.firstOrNull {
                    (model.isNotBlank() && it.contains(model, ignoreCase = true)) ||
                        (hash.isNotBlank() && it.contains(hash, ignoreCase = true))
                } ?: old.checkpoint
                val updated = old.copy(
                    prompt = data.optString("prompt", old.prompt),
                    negativePrompt = data.optString("negative_prompt", old.negativePrompt),
                    steps = data.optInt("steps", old.steps),
                    cfgScale = data.optDouble("cfg_scale", old.cfgScale.toDouble()).toFloat(),
                    sampler = data.optString("sampler", old.sampler),
                    scheduler = data.optString("scheduler", old.scheduler),
                    seed = data.optLong("seed", old.seed),
                    width = data.optInt("width", old.width),
                    height = data.optInt("height", old.height),
                    checkpoint = checkpoint,
                    hiresEnabled = data.optBoolean("hires_enabled", old.hiresEnabled),
                    hiresScale = data.optDouble("hires_scale", old.hiresScale.toDouble()).toFloat(),
                    hiresDenoising = data.optDouble("hires_denoising", old.hiresDenoising.toDouble()).toFloat(),
                    img2imgDenoising = data.optDouble("img2img_denoising", old.img2imgDenoising.toDouble()).toFloat()
                )
                editSettings { updated }
                state = state.copy(message = "Letzte Generierungsparameter vom PC übernommen.")
            } catch (error: Exception) {
                state = state.copy(message = "PC-Parameter konnten nicht geladen werden: ${error.message}")
            } finally {
                state = state.copy(isRestoring = false)
            }
        }
    }

    fun refreshPcGallery() {
        if (!state.bridgeAvailable || state.connectedUrl.isBlank()) {
            state = state.copy(pcImages = emptyList(), isPcLoading = false)
            return
        }
        viewModelScope.launch {
            state = state.copy(isPcLoading = true)
            try {
                val images = api.pcImages(state.connectedUrl, state.connection)
                val ids = images.map { it.id }.toSet()
                state = state.copy(pcImages = images,
                    pcPreviews = state.pcPreviews.filterKeys { it in ids })
            } catch (error: Exception) {
                state = state.copy(message = "PC-Bilder konnten nicht geladen werden: ${error.message}")
            } finally {
                state = state.copy(isPcLoading = false)
            }
        }
    }

    fun loadPcThumbnail(image: PcImage) {
        if (image.id in state.pcPreviews || !loadingThumbs.add(image.id) || !state.bridgeAvailable) return
        viewModelScope.launch {
            try {
                val bytes = api.pcThumbnail(state.connectedUrl, state.connection, image.id)
                decodeBitmap(bytes)?.let { bitmap ->
                    state = state.copy(pcPreviews = state.pcPreviews + (image.id to bitmap))
                }
            } catch (_: Exception) {
                // Individual thumbnail failures should not hide the rest of the gallery.
            } finally {
                loadingThumbs.remove(image.id)
            }
        }
    }

    fun downloadPcImage(image: PcImage) {
        if (!state.bridgeAvailable) return
        viewModelScope.launch {
            state = state.copy(message = "Lade ${image.name} vom PC …")
            try {
                val bytes = api.pcImageDownload(state.connectedUrl, state.connection, image.id)
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val suffix = image.name.substringAfterLast('.', "png").lowercase()
                    val mime = when (suffix) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        else -> "image/png"
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, image.name)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A1111 Remote")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Kann Bild nicht in der Handy-Galerie speichern.")
                    try {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IOException("Speichern fehlgeschlagen.")
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    } catch (error: Exception) {
                        resolver.delete(uri, null, null)
                        throw error
                    }
                }
                state = state.copy(message = "Bild vom PC in Bilder/A1111 Remote gespeichert.")
            } catch (error: Exception) {
                state = state.copy(message = "Download fehlgeschlagen: ${error.message}")
            }
        }
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
