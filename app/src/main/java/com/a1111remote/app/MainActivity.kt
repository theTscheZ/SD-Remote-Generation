package com.a1111remote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a1111remote.app.model.AdetailerSettings
import com.a1111remote.app.model.GenerationSettings
import com.a1111remote.app.model.GenerationMode
import com.a1111remote.app.data.PcImage
import com.a1111remote.app.ui.LocalImage
import com.a1111remote.app.ui.RemoteViewModel
import com.a1111remote.app.ui.UiState

private val accent = Color(0xFF8CB5FF)
private val scheme = darkColorScheme(primary = accent, secondary = Color(0xFFB1C5EA), surface = Color(0xFF171C26), background = Color(0xFF0D111B))

private enum class Screen { GENERATE, GALLERY, CONNECTION }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RemoteApp(viewModel())
                }
            }
        }
    }
}

@Composable
private fun RemoteApp(vm: RemoteViewModel) {
    val state = vm.state
    var screen by remember { mutableStateOf(Screen.GENERATE) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshRemoteStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var notificationPermissionAsked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.generate() }
    val startGeneration: () -> Unit = {
        if (state.connectedUrl.isBlank()) {
            screen = Screen.CONNECTION
        } else if (Build.VERSION.SDK_INT >= 33 &&
            !notificationPermissionAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.generate()
        }
    }
    Scaffold(
        topBar = {
            // Keep the custom toolbar below the Android status bar on edge-to-edge devices.
            // Scaffold propagates this adjusted toolbar height to the screen content.
            Surface(modifier = Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, null, tint = accent)
                        Spacer(Modifier.width(8.dp))
                        Text("A1111 Remote", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        val running = state.isGenerating || state.remoteGenerating
                        if (running) {
                            Text("${(state.progress * 100).toInt()} %",
                                style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = vm::interrupt) {
                                Icon(Icons.Default.Stop, contentDescription = "Generierung stoppen")
                            }
                        } else {
                            IconButton(onClick = startGeneration,
                                enabled = state.connectedUrl.isNotBlank()) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Generate starten",
                                    tint = accent)
                            }
                        }
                        Icon(if (state.connectedUrl.isNotEmpty()) Icons.Default.CheckCircle
                            else Icons.Default.CloudOff,
                            contentDescription = if (state.connectedUrl.isNotEmpty()) "Verbunden" else "Offline",
                            tint = if (state.connectedUrl.isNotEmpty()) Color(0xFF80D4AE)
                                else MaterialTheme.colorScheme.outline)
                    }
                    if (state.isGenerating || state.remoteGenerating) {
                        LinearProgressIndicator(progress = { state.progress },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = screen == Screen.GENERATE, onClick = { screen = Screen.GENERATE }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Generate") })
                NavigationBarItem(selected = screen == Screen.GALLERY, onClick = { vm.refreshGallery(); vm.refreshPcGallery(); screen = Screen.GALLERY }, icon = { Icon(Icons.Default.Image, null) }, label = { Text("Galerie") })
                NavigationBarItem(selected = screen == Screen.CONNECTION, onClick = { screen = Screen.CONNECTION }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Verbindung") })
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (state.message.isNotEmpty()) {
                Text(state.message, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            when (screen) {
                Screen.GENERATE -> GenerateScreen(state, vm, onOpenConnection = { screen = Screen.CONNECTION }, onGenerate = startGeneration)
                Screen.GALLERY -> GalleryScreen(state, vm)
                Screen.CONNECTION -> ConnectionScreen(state, vm)
            }
        }
    }
}

@Composable
private fun GenerateScreen(state: UiState, vm: RemoteViewModel, onOpenConnection: () -> Unit, onGenerate: () -> Unit) {
    val current = state.settings
    val mode = state.mode
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        vm.pickSourceImage(uri)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                GenerationMode.TXT2IMG to "txt2img",
                GenerationMode.IMG2IMG to "img2img",
                GenerationMode.EXTRAS to "Extras"
            ).forEach { (option, label) ->
                FilterChip(
                    selected = mode == option,
                    onClick = { vm.selectMode(option) },
                    label = { Text(label) },
                    enabled = !state.isGenerating && !state.remoteGenerating,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (mode != GenerationMode.TXT2IMG) {
            SectionCard("Ausgangsbild") {
                OutlinedButton(onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Bild vom Handy auswählen")
                }
                state.sourcePreview?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "Ausgangsbild",
                        Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        contentScale = ContentScale.Fit)
                }
                if (state.sourcePreview == null) {
                    Text("Wähle ein Bild aus. Alternativ kannst du ein PC-Bild in der PC-Galerie als Quelle auswählen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        OutlinedButton(
            onClick = vm::restorePcParameters,
            enabled = state.bridgeAvailable && !state.isRestoring,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isRestoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Letzte PC-Parameter übernehmen")
        }
        if (state.connectedUrl.isNotBlank() && !state.bridgeAvailable) {
            Text("PC-Parameter und PC-Galerie: A1111 Remote-Erweiterung am PC installieren.",
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (state.connectedUrl.isBlank()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenConnection)) {
                Text("Noch nicht verbunden · PC-Verbindung einrichten", Modifier.padding(14.dp), color = accent)
            }
        }
        if (mode != GenerationMode.EXTRAS) {
            OutlinedTextField(value = current.prompt, onValueChange = { text -> vm.editSettings { it.copy(prompt = text) } }, label = { Text("Prompt") },
                minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = current.negativePrompt, onValueChange = { text -> vm.editSettings { it.copy(negativePrompt = text) } }, label = { Text("Negative Prompt") },
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())

            SectionCard("Generierung") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice("Sampler", current.sampler, state.options?.samplers.orEmpty(), Modifier.weight(1f)) { value -> vm.editSettings { it.copy(sampler = value) } }
                    Choice("Scheduler", current.scheduler, state.options?.schedulers.orEmpty(), Modifier.weight(1f)) { value -> vm.editSettings { it.copy(scheduler = value) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberInput("Steps", current.steps.toString(), Modifier.weight(1f), KeyboardType.Number) { it.toIntOrNull()?.let { value -> vm.editSettings { s -> s.copy(steps = value) } } }
                    NumberInput("CFG Scale", current.cfgScale.toString(), Modifier.weight(1f), KeyboardType.Decimal) { it.toFloatOrNull()?.let { value -> vm.editSettings { s -> s.copy(cfgScale = value) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberInput("Breite", current.width.toString(), Modifier.weight(1f), KeyboardType.Number) { it.toIntOrNull()?.let { value -> vm.editSettings { s -> s.copy(width = value) } } }
                    NumberInput("Höhe", current.height.toString(), Modifier.weight(1f), KeyboardType.Number) { it.toIntOrNull()?.let { value -> vm.editSettings { s -> s.copy(height = value) } } }
                }
                NumberInput("Seed (-1 = zufällig)", current.seed.toString(),
                    Modifier.fillMaxWidth(), KeyboardType.Number) { input ->
                    input.toLongOrNull()?.let { value -> vm.editSettings { it.copy(seed = value) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberInput("Batch Count", current.batchCount.toString(),
                        Modifier.weight(1f), KeyboardType.Number) { input ->
                        input.toIntOrNull()?.let { value ->
                            vm.editSettings { it.copy(batchCount = value.coerceIn(1, 100)) }
                        }
                    }
                    NumberInput("Batch Size", current.batchSize.toString(),
                        Modifier.weight(1f), KeyboardType.Number) { input ->
                        input.toIntOrNull()?.let { value ->
                            vm.editSettings { it.copy(batchSize = value.coerceIn(1, 8)) }
                        }
                    }
                }
                Text("Gesamt: ${current.batchCount * current.batchSize} Bilder · " +
                    "${current.batchCount} Durchläufe × ${current.batchSize} Bilder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                Choice("Checkpoint", current.checkpoint.ifBlank { state.options?.activeCheckpoint.orEmpty() }, state.options?.checkpoints.orEmpty(), Modifier.fillMaxWidth()) { value -> vm.editSettings { it.copy(checkpoint = value) } }
                LoraPicker(state, vm)
            }

            if (mode == GenerationMode.IMG2IMG) {
                SectionCard("img2img") {
                    NumberInput("Denoising Strength", current.img2imgDenoising.toString(),
                        Modifier.fillMaxWidth(), KeyboardType.Decimal) { input ->
                        input.toFloatOrNull()?.let { value ->
                            vm.editSettings { it.copy(img2imgDenoising = value.coerceIn(0f, 1f)) }
                        }
                    }
                }
            } else {
                SectionCard("Hires. Fix") {
                    ToggleRow("Aktivieren", current.hiresEnabled) { vm.editSettings { s -> s.copy(hiresEnabled = it) } }
                    if (current.hiresEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberInput("Upscale", current.hiresScale.toString(), Modifier.weight(1f), KeyboardType.Decimal) { it.toFloatOrNull()?.let { n -> vm.editSettings { s -> s.copy(hiresScale = n) } } }
                            NumberInput("Denoising", current.hiresDenoising.toString(), Modifier.weight(1f), KeyboardType.Decimal) { it.toFloatOrNull()?.let { n -> vm.editSettings { s -> s.copy(hiresDenoising = n) } } }
                        }
                    }
                }
            }
            AdetailerCard(current, state, vm)

        } else {
        SectionCard("Extras · Upscaling und Wiederherstellung") {
            Choice("Upscaler", state.extras.upscaler,
                state.options?.upscalers.orEmpty(), Modifier.fillMaxWidth()) { value ->
                vm.editExtras { it.copy(upscaler = value) }
            }
            NumberInput("Upscaling (1–4×)", state.extras.scale.toString(),
                Modifier.fillMaxWidth(), KeyboardType.Decimal) { input ->
                input.toFloatOrNull()?.let { value ->
                    vm.editExtras { it.copy(scale = value.coerceIn(1f, 4f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberInput("GFPGAN (0–1)", state.extras.gfpgan.toString(),
                    Modifier.weight(1f), KeyboardType.Decimal) { input ->
                    input.toFloatOrNull()?.let { value ->
                        vm.editExtras { it.copy(gfpgan = value.coerceIn(0f, 1f)) }
                    }
                }
                NumberInput("CodeFormer (0–1)", state.extras.codeformer.toString(),
                    Modifier.weight(1f), KeyboardType.Decimal) { input ->
                    input.toFloatOrNull()?.let { value ->
                        vm.editExtras { it.copy(codeformer = value.coerceIn(0f, 1f)) }
                    }
                }
            }
            NumberInput("CodeFormer Weight", state.extras.codeformerWeight.toString(),
                Modifier.fillMaxWidth(), KeyboardType.Decimal) { input ->
                input.toFloatOrNull()?.let { value ->
                    vm.editExtras { it.copy(codeformerWeight = value.coerceIn(0f, 1f)) }
                }
            }
            Text("Extras verarbeitet das gewählte Bild auf dem PC. Es verwendet keinen Prompt oder Sampler.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }

        }

        if (state.isGenerating || state.remoteGenerating) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
            state.preview?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "Generierungsvorschau", Modifier.fillMaxWidth().heightIn(max = 370.dp), contentScale = ContentScale.Fit)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::interrupt, modifier = Modifier.weight(1f)) { Text("Interrupt") }
                OutlinedButton(onClick = vm::skip, modifier = Modifier.weight(1f)) { Text("Skip") }
            }
        } else {
            Button(onClick = onGenerate,
                enabled = state.connectedUrl.isNotEmpty() &&
                    !state.remoteGenerating &&
                    (mode == GenerationMode.TXT2IMG || state.sourcePreview != null),
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (mode == GenerationMode.EXTRAS) "Bild verarbeiten" else "Generate",
                    fontWeight = FontWeight.Bold)
            }
        }
        state.generated.forEachIndexed { index, bitmap ->
            Image(bitmap.asImageBitmap(), "Generiertes Bild ${index + 1}", Modifier.fillMaxWidth().heightIn(max = 460.dp), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.height(20.dp))
    }
}


@Composable
private fun AdetailerCard(settings: GenerationSettings, state: UiState, vm: RemoteViewModel) {
    val units = listOf(settings.adetailer) + settings.additionalAdetailers
    var selectedTab by remember { mutableIntStateOf(0) }
    val index = selectedTab.coerceIn(0, units.lastIndex)
    val ad = units[index]
    var expanded by remember { mutableStateOf(true) }
    SectionCard("ADetailer") {
        if (state.options != null && !state.options.adetailerInstalled) {
            Text("ADetailer wurde auf dem PC nicht erkannt.",
                color = Color(0xFFFFC77D), style = MaterialTheme.typography.bodySmall)
        }
        ScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp) {
            units.forEachIndexed { pos, unit ->
                val name = when (pos) { 0 -> "Face"; 1 -> "Hand"; else -> "Tab ${pos + 1}" }
                Tab(selected = index == pos, onClick = { selectedTab = pos },
                    text = { Text("$name ${if (unit.enabled) "✓" else "○"}") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tab ${index + 1} aktivieren", Modifier.weight(1f))
            Switch(checked = ad.enabled, onCheckedChange = { enabled ->
                vm.updateAdetailer(index) { copy(enabled = enabled) }
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Weniger anzeigen" else "Optionen anzeigen")
            }
            if (units.size < 5) {
                TextButton(onClick = { vm.addAdetailerTab(); selectedTab = units.size }) {
                    Text("+ Tab")
                }
            }
            if (index > 0 && units.size > 2) {
                TextButton(onClick = { vm.removeAdetailerTab(index); selectedTab = 0 }) {
                    Text("Tab entfernen")
                }
            }
        }
        if (expanded) {
            if (state.options?.adetailerModels?.isNotEmpty() == true) {
                Choice("Detection Model", ad.model, state.options.adetailerModels,
                    Modifier.fillMaxWidth()) { value ->
                    vm.updateAdetailer(index) { copy(model = value) }
                }
            } else {
                OutlinedTextField(value = ad.model, onValueChange = { value ->
                    vm.updateAdetailer(index) { copy(model = value) }
                }, label = { Text("Detection Model") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            OutlinedTextField(value = ad.prompt, onValueChange = { value ->
                vm.updateAdetailer(index) { copy(prompt = value) }
            }, label = { Text("ADetailer Prompt") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            OutlinedTextField(value = ad.negativePrompt, onValueChange = { value ->
                vm.updateAdetailer(index) { copy(negativePrompt = value) }
            }, label = { Text("ADetailer Negative Prompt") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberInput("Confidence (0–1)", ad.confidence.toString(),
                    Modifier.weight(1f), KeyboardType.Decimal) { input ->
                    input.toFloatOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(confidence = value.coerceIn(0f, 1f)) }
                    }
                }
                NumberInput("Denoising (0–1)", ad.denoising.toString(),
                    Modifier.weight(1f), KeyboardType.Decimal) { input ->
                    input.toFloatOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(denoising = value.coerceIn(0f, 1f)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberInput("Mask Blur", ad.maskBlur.toString(),
                    Modifier.weight(1f), KeyboardType.Number) { input ->
                    input.toIntOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(maskBlur = value) }
                    }
                }
                NumberInput("Padding", ad.padding.toString(),
                    Modifier.weight(1f), KeyboardType.Number) { input ->
                    input.toIntOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(padding = value) }
                    }
                }
            }
            NumberInput("Mask erosion (-) / dilation (+)", ad.dilation.toString(),
                Modifier.fillMaxWidth(), KeyboardType.Number) { input ->
                input.toIntOrNull()?.let { value ->
                    vm.updateAdetailer(index) { copy(dilation = value) }
                }
            }
            Text("Negativ = Maske verkleinern, positiv = Maske vergrößern.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
            ToggleRow("Nur Maske inpainten", ad.onlyMasked) { enabled ->
                vm.updateAdetailer(index) { copy(onlyMasked = enabled) }
            }
        }
    }
}

@Composable
private fun LoraPicker(state: UiState, vm: RemoteViewModel) {
    val loras = state.options?.loras.orEmpty()
    var search by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("1.0") }
    val selected = remember(state.settings.prompt) {
        Regex("<lora:([^:>]+):[^>]+>", RegexOption.IGNORE_CASE)
            .findAll(state.settings.prompt).map { it.groupValues[1] }.toList()
    }
    Text("PC-LoRAs", style = MaterialTheme.typography.titleSmall)
    if (loras.isEmpty()) {
        Text("Keine LoRAs vom PC geladen. Verbindung prüfen oder neu verbinden.",
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        return
    }
    OutlinedTextField(value = search, onValueChange = { search = it },
        label = { Text("LoRA suchen (${loras.size} auf dem PC)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Gewicht für neue LoRAs", modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = weight, onValueChange = { weight = it },
            label = { Text("Gewicht") }, singleLine = true, modifier = Modifier.width(120.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    if (selected.isNotEmpty()) {
        Text("Aktiv im Prompt (antippen zum Entfernen)", style = MaterialTheme.typography.labelMedium)
        selected.forEach { name ->
            TextButton(onClick = { vm.toggleLora(name, weight) }) { Text("✓ $name   ✕") }
        }
    }
    val matched = remember(loras, search) {
        loras.filter { it.contains(search.trim(), ignoreCase = true) }
    }
    Text(if (search.isBlank()) "LoRAs antippen zum Hinzufügen · Suche für weitere Einträge"
        else "${matched.size} Treffer · Antippen zum Hinzufügen oder Entfernen",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    matched.take(if (search.isBlank()) 12 else 35).forEach { name ->
        val active = selected.any { it.equals(name, ignoreCase = true) }
        OutlinedButton(onClick = { vm.toggleLora(name, weight) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (active) "✓  $name   (entfernen)" else "+  $name",
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (matched.size > (if (search.isBlank()) 12 else 35)) {
        Text("Weitere LoRAs über die Suche finden.",
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GalleryScreen(state: UiState, vm: RemoteViewModel) {
    var showPc by remember { mutableStateOf(false) }
    var selectedLocal by remember { mutableStateOf<LocalImage?>(null) }
    var selectedPc by remember { mutableStateOf<PcImage?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !showPc, onClick = { showPc = false },
                label = { Text("Handy") })
            FilterChip(selected = showPc, onClick = { showPc = true; vm.refreshPcGallery() },
                label = { Text("PC") })
            if (showPc && state.bridgeAvailable) {
                TextButton(onClick = vm::refreshPcGallery) { Text("Aktualisieren") }
            }
        }
        if (!showPc) {
            if (state.gallery.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Noch keine lokal gespeicherten Bilder.",
                        color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.gallery, key = { it.file.absolutePath }) { item ->
                        Card(Modifier.fillMaxWidth().clickable { selectedLocal = item }) {
                            Image(item.preview.asImageBitmap(), "Generiertes Bild",
                                Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
                            Text(item.label, Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else if (!state.bridgeAvailable) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Für die PC-Galerie muss die A1111 Remote-Erweiterung installiert sein. " +
                    "Danach WebUI neu starten und die App erneut verbinden.",
                    color = MaterialTheme.colorScheme.secondary)
            }
        } else if (state.pcImages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isPcLoading) CircularProgressIndicator()
                else Text("Keine Bilder in den PC-Ausgabeordnern gefunden.",
                    color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.pcImages, key = { it.id }) { item ->
                    LaunchedEffect(item.id) { vm.loadPcThumbnail(item) }
                    val preview = state.pcPreviews[item.id]
                    Card(Modifier.fillMaxWidth().clickable { selectedPc = item }) {
                        if (preview != null) {
                            Image(preview.asImageBitmap(), "PC-Bild",
                                Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
                        } else {
                            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(30.dp))
                            }
                        }
                        Text(item.name, Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    selectedLocal?.let { item ->
        AlertDialog(onDismissRequest = { selectedLocal = null }, title = { Text("Handy-Bild") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(item.preview.asImageBitmap(), "Vorschau",
                        Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit)
                    Text(item.label, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.exportImage(item); selectedLocal = null }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text("In Bilder speichern")
                }
            },
            dismissButton = { TextButton(onClick = { selectedLocal = null }) { Text("Schließen") } })
    }
    selectedPc?.let { item ->
        AlertDialog(onDismissRequest = { selectedPc = null }, title = { Text("PC-Bild") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.pcPreviews[item.id]?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), "PC-Vorschau",
                            Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit)
                    }
                    Text(item.name, style = MaterialTheme.typography.bodySmall)
                    Text("Das Original bleibt auf dem PC, bis du es herunterlädst.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.downloadPcImage(item); selectedPc = null }) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Original herunterladen")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.usePcImageAsSource(item)
                    vm.selectMode(GenerationMode.IMG2IMG)
                    selectedPc = null
                }) { Text("Als img2img-Quelle") }
                TextButton(onClick = { selectedPc = null }) { Text("Schließen") }
            })
    }
}

@Composable
private fun ConnectionScreen(state: UiState, vm: RemoteViewModel) {
    val conn = state.connection
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PC verbinden", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Zuerst LAN, dann Tailscale. Nur Adressen eintragen, die du selbst kontrollierst.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(value = conn.lanUrl, onValueChange = { v -> vm.editConnection { it.copy(lanUrl = v) } },
            label = { Text("WLAN-Adresse") }, placeholder = { Text("http://192.168.1.100:7860") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = conn.vpnUrl, onValueChange = { v -> vm.editConnection { it.copy(vpnUrl = v) } },
            label = { Text("Tailscale-Adresse (optional)") }, placeholder = { Text("http://100.100.100.100:7860") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("AUTOMATIC1111 API-Authentifizierung", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = conn.username, onValueChange = { v -> vm.editConnection { it.copy(username = v) } },
            label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = conn.password, onValueChange = { v -> vm.editConnection { it.copy(password = v) } },
            label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = vm::connect, enabled = !state.isConnecting && !state.isGenerating,
            modifier = Modifier.fillMaxWidth().height(50.dp)) {
            if (state.isConnecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Verbindung testen")
        }
        if (state.connectedUrl.isNotBlank()) {
            Text("Verbunden über: ${state.connectedUrl}", color = Color(0xFF80D4AE), style = MaterialTheme.typography.bodyMedium)
            Text("${state.options?.checkpoints?.size ?: 0} Checkpoints · ${state.options?.loras?.size ?: 0} LoRAs · ADetailer: ${if (state.options?.adetailerInstalled == true) "erkannt" else "nicht erkannt"}",
                style = MaterialTheme.typography.bodySmall)
            Text(if (state.bridgeAvailable) "PC-Erweiterung: verbunden" else "PC-Erweiterung: nicht erkannt",
                color = if (state.bridgeAvailable) Color(0xFF80D4AE) else MaterialTheme.colorScheme.secondary)
        }
        SectionCard("Einrichtung am PC") {
            Text("1. Tailscale auf PC und Handy installieren und beide im selben Tailnet anmelden.")
            Text("2. A1111 mit --api --listen und optional --api-auth BENUTZER:PASSWORT starten.")
            Text("3. Die lokale PC-IP und die Tailscale-IP eintragen und verbinden.")
            Text("4. PC und AUTOMATIC1111 müssen zum Generieren eingeschaltet bleiben.")
            Text("Keinen Router-Port freigeben! HTTP nur für vertrauenswürdiges LAN oder VPN.", color = Color(0xFFFFC77D))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun Choice(label: String, value: String, choices: List<String>, modifier: Modifier = Modifier, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = choices.isNotEmpty()) {
                Text(value.ifEmpty { "Auswählen" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(option) })
                }
            }
        }
    }
}

@Composable
private fun NumberInput(label: String, value: String, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Number, onChanged: (String) -> Unit) {
    var typed by remember { mutableStateOf(value) }
    // Keep text entry stable while the user is editing a fractional value or negative seed.
    LaunchedEffect(value) { if (typed.toDoubleOrNull() != value.toDoubleOrNull()) typed = value }
    OutlinedTextField(value = typed, onValueChange = { typed = it; onChanged(it) }, modifier = modifier,
        label = { Text(label, maxLines = 1) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType))
}
