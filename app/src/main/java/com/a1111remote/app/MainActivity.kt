package com.a1111remote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.a1111remote.app.model.SectionPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalDensity
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
import com.a1111remote.app.ui.RemoteViewModel
import com.a1111remote.app.ui.UiState

private val accent = Color(0xFF8CB5FF)
private val scheme = darkColorScheme(primary = accent, secondary = Color(0xFFB1C5EA), surface = Color(0xFF171C26), background = Color(0xFF0D111B))

private enum class Screen { GENERATE, GALLERY, CONTROL, CONNECTION, PREFERENCES }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
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
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
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
                        IconButton(onClick = { screen = Screen.PREFERENCES }) {
                            Icon(Icons.Default.Settings, contentDescription = "Anzeige-Einstellungen")
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
            if (!keyboardVisible) NavigationBar {
                NavigationBarItem(selected = screen == Screen.GENERATE, onClick = { screen = Screen.GENERATE }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Generate") })
                NavigationBarItem(selected = screen == Screen.GALLERY, onClick = { vm.refreshPcGallery(); screen = Screen.GALLERY }, icon = { Icon(Icons.Default.Image, null) }, label = { Text("Galerie") })
                NavigationBarItem(selected = screen == Screen.CONTROL,
                    onClick = { screen = Screen.CONTROL; vm.refreshPcControl() },
                    icon = { Icon(Icons.Default.Computer, null) }, label = { Text("PC") })
                NavigationBarItem(selected = screen == Screen.CONNECTION, onClick = { screen = Screen.CONNECTION }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Verbindung") })
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            if (state.message.isNotEmpty()) {
                Text(state.message, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            when (screen) {
                Screen.GENERATE -> GenerateScreen(state, vm, onOpenConnection = { screen = Screen.CONNECTION }, onGenerate = startGeneration)
                Screen.GALLERY -> GalleryScreen(state, vm)
                Screen.PREFERENCES -> AppearanceSettingsScreen(state, vm)
                Screen.CONTROL -> PcControlScreen(state, vm)
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
            StableMultilineField("main-prompt-${mode.name}", current.prompt, "Prompt",
                minLines = 3, maxLines = 8) { text ->
                vm.editSettings { it.copy(prompt = text) }
            }
            StableMultilineField("main-negative-${mode.name}", current.negativePrompt,
                "Negative Prompt", minLines = 2, maxLines = 5) { text ->
                vm.editSettings { it.copy(negativePrompt = text) }
            }


            OrderedGenerationSections(state, vm)
            if (mode == GenerationMode.IMG2IMG) {
                SectionCard("img2img") {
                    NumberInput("Denoising Strength", current.img2imgDenoising.toString(),
                        Modifier.fillMaxWidth(), KeyboardType.Decimal) { input ->
                        input.toFloatOrNull()?.let { value ->
                            vm.editSettings { it.copy(img2imgDenoising = value.coerceIn(0f, 1f)) }
                        }
                    }
                }
            }


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
private fun OrderedGenerationSections(state: UiState, vm: RemoteViewModel) {
    val current = state.settings
    val mode = state.mode
    val visible = when (mode) {
        GenerationMode.TXT2IMG -> setOf("generation", "loras", "hires", "adetailer")
        GenerationMode.IMG2IMG -> setOf("generation", "loras", "adetailer")
        GenerationMode.EXTRAS -> emptySet()
    }
    state.sectionPreferences.order.filter { it in visible }.forEach { section ->
        key(section) {
        when (section) {
            "generation" -> {
                CollapsibleSection("generation", state, "Generierung") {
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
            }
            }
            "hires" -> {
                CollapsibleSection("hires", state, "Hires. Fix") {
                    ToggleRow("Aktivieren", current.hiresEnabled) { vm.editSettings { s -> s.copy(hiresEnabled = it) } }
                    if (current.hiresEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberInput("Upscale", current.hiresScale.toString(), Modifier.weight(1f), KeyboardType.Decimal) { it.toFloatOrNull()?.let { n -> vm.editSettings { s -> s.copy(hiresScale = n) } } }
                            NumberInput("Denoising", current.hiresDenoising.toString(), Modifier.weight(1f), KeyboardType.Decimal) { it.toFloatOrNull()?.let { n -> vm.editSettings { s -> s.copy(hiresDenoising = n) } } }
                        }
                    }
                }
            }
            "loras" -> {
                CollapsibleSection("loras", state, "LoRAs") {
                    LoraPicker(state, vm)
                }
            }
            "adetailer" -> AdetailerCard(current, state, vm)
        }
        }
    }
}

@Composable
private fun CollapsibleSection(
    id: String,
    state: UiState,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val defaultOpen = state.sectionPreferences.defaultExpanded[id] ?: true
    var open by rememberSaveable(id, defaultOpen) { mutableStateOf(defaultOpen) }
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (open) "▴" else "▾",
                    style = MaterialTheme.typography.titleLarge)
            }
            if (open) content()
        }
    }
}

@Composable
private fun AppearanceSettingsScreen(state: UiState, vm: RemoteViewModel) {
    val prefs = state.sectionPreferences
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anzeige & Standards", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("Sortiere die Kategorien mit den Pfeilen. Die Ausklapp-Einstellung " +
            "bestimmt, wie die Bereiche beim Öffnen angezeigt werden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        prefs.order.forEachIndexed { index, id ->
            SectionCard(SectionPreferences.LABELS[id] ?: id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Reihenfolge", Modifier.weight(1f))
                    TextButton(onClick = {
                        vm.updateSectionPreferences { it.move(id, -1) }
                    }, enabled = index > 0) { Text("↑") }
                    TextButton(onClick = {
                        vm.updateSectionPreferences { it.move(id, 1) }
                    }, enabled = index < prefs.order.lastIndex) { Text("↓") }
                }
                ToggleRow("Standardmäßig ausgeklappt",
                    prefs.defaultExpanded[id] ?: true) { value ->
                    vm.updateSectionPreferences {
                        it.copy(defaultExpanded = it.defaultExpanded + (id to value))
                    }
                }
                if (id == "hires" || id == "adetailer") {
                    ToggleRow("Standardmäßig aktiviert",
                        prefs.defaultEnabled[id] ?: false) { value ->
                        vm.updateSectionPreferences {
                            it.copy(defaultEnabled = it.defaultEnabled + (id to value),
                                activationDefaultsConfigured = true)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = vm::applyEnabledDefaults,
            modifier = Modifier.fillMaxWidth()) {
            Text("Aktivierungs-Standards jetzt anwenden")
        }
        Text("Bestehende Generierungswerte werden nicht stillschweigend überschrieben. " +
            "Der Button wendet deine Aktivierungs-Standards auf die aktuelle Generierung an.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AdetailerCard(settings: GenerationSettings, state: UiState, vm: RemoteViewModel) {
    val units = listOf(settings.adetailer) + settings.additionalAdetailers
    var selectedTab by remember { mutableIntStateOf(0) }
    val index = selectedTab.coerceIn(0, units.lastIndex)
    val ad = units[index]
    var expanded by remember { mutableStateOf(true) }
    CollapsibleSection("adetailer", state, "ADetailer") {
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
            StableMultilineField("ad-prompt-${state.mode.name}-$index", ad.prompt,
                "ADetailer Prompt", minLines = 2, maxLines = 5) { value ->
                vm.updateAdetailer(index) { copy(prompt = value) }
            }
            StableMultilineField("ad-negative-${state.mode.name}-$index", ad.negativePrompt,
                "ADetailer Negative Prompt", minLines = 2, maxLines = 5) { value ->
                vm.updateAdetailer(index) { copy(negativePrompt = value) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberInput("Confidence (0–1)", ad.confidence.toString(),
                    Modifier.weight(1f), KeyboardType.Decimal) { input ->
                    input.toFloatOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(confidence = value.coerceIn(0f, 1f)) }
                    }
                }
                NumberInput("Top (0 = alle)", ad.topK.toString(),
                    Modifier.weight(1f), KeyboardType.Number) { input ->
                    input.toIntOrNull()?.let { value ->
                        vm.updateAdetailer(index) { copy(topK = value.coerceIn(0, 10)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    var selectedPc by remember { mutableStateOf<PcImage?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End) {
            if (state.bridgeAvailable) {
                TextButton(onClick = vm::refreshPcGallery) { Text("Aktualisieren") }
            }
        }
        if (!state.bridgeAvailable) {
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
    selectedPc?.let { item ->
        AlertDialog(onDismissRequest = { selectedPc = null }, title = { Text("PC-Bild") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.pcPreviews[item.id]?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), "PC-Vorschau",
                            Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit)
                    }
                    Text(item.name, style = MaterialTheme.typography.bodySmall)
                    Text("Das Original befindet sich auf dem PC. Nur mit „Original herunterladen“ wird es auf dem Handy gespeichert.",
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
private fun PcControlScreen(state: UiState, vm: RemoteViewModel) {
    var confirmShutdown by remember { mutableStateOf(false) }
    val status = state.pcControllerStatus
    val busy = state.pcControlBusy
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PC-Steuerung", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("Zum Einschalten im Heim-WLAN: Wake-on-LAN. Für Start/Stopp/Shutdown: " +
            "Windows-Helfer auf Port 42001. Tailscale ist für diese Befehle optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        SectionCard("Einrichten") {
            OutlinedTextField(
                value = state.pcMac, onValueChange = vm::setPcMac,
                label = { Text("PC-MAC-Adresse (Ethernet)") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = state.pcControllerToken, onValueChange = vm::setPcToken,
                label = { Text("Controller-Token vom PC-Helfer") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text("Der Token wird verschlüsselt gespeichert. Die IP-Adressen kommen " +
                "von der Verbindungsseite; Port 42001 wird automatisch verwendet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        SectionCard("PC und AUTOMATIC1111") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        status == null -> "Controller: nicht verbunden"
                        status.a1111Reachable -> "PC online · A1111 läuft"
                        else -> "PC online · A1111 gestoppt"
                    }, modifier = Modifier.weight(1f),
                    color = if (status != null) Color(0xFF80D4AE)
                        else MaterialTheme.colorScheme.secondary
                )
                IconButton(onClick = vm::refreshPcControl,
                    enabled = !busy && state.pcControllerToken.isNotBlank()) {
                    Icon(Icons.Default.Refresh, contentDescription = "Status aktualisieren")
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.pcControlMessage.isNotBlank()) {
                Text(state.pcControlMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            OutlinedButton(onClick = vm::wakePc, modifier = Modifier.fillMaxWidth(),
                enabled = !busy && state.pcMac.isNotBlank()) {
                Icon(Icons.Default.PowerSettingsNew, null)
                Spacer(Modifier.width(8.dp))
                Text("PC einschalten (Wake-on-LAN)")
            }
            Button(onClick = { vm.pcAction("start") }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy && state.pcControllerToken.length >= 32 &&
                    status != null && !status.a1111Reachable && !status.a1111Managed) {
                Text("A1111 starten")
            }
            OutlinedButton(onClick = { vm.pcAction("stop") }, modifier = Modifier.fillMaxWidth(),
                enabled = !busy && status?.a1111Managed == true) {
                Text("A1111 stoppen")
            }
            if (status?.a1111Reachable == true && status.a1111Managed == false) {
                Text("A1111 wurde außerhalb des Helfers gestartet und kann deshalb " +
                    "nicht über diesen Button gestoppt werden.",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { confirmShutdown = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && status != null) {
                Text("PC herunterfahren")
            }
            if (status?.shutdownPending == true) {
                TextButton(onClick = { vm.pcAction("shutdown/cancel") }) {
                    Text("Herunterfahren abbrechen (30-Sekunden-Frist)")
                }
            }
        }
        Text("Noch nicht eingerichtet? Im Repository unter pc-control/ " +
            "den Windows-Helfer installieren. Keine Ports im Router freigeben.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
    if (confirmShutdown) {
        AlertDialog(
            onDismissRequest = { confirmShutdown = false },
            title = { Text("PC wirklich herunterfahren?") },
            text = { Text("Windows wird nach 30 Sekunden heruntergefahren. " +
                "Nicht gespeicherte Arbeit kann verloren gehen. " +
                "Beende laufende Generierungen vorher.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShutdown = false
                    vm.pcAction("shutdown")
                }) { Text("Herunterfahren") }
            },
            dismissButton = {
                TextButton(onClick = { confirmShutdown = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun ConnectionScreen(state: UiState, vm: RemoteViewModel) {
    val conn = state.connection
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("PC verbinden", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Verbindungsweg wählen – Auto probiert zuerst LAN, danach Tailscale.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "lan" to "WLAN", "tailscale" to "Tailscale")
                .forEach { (route, label) ->
                    FilterChip(
                        selected = conn.connectionMode == route,
                        onClick = { vm.switchConnectionMode(route) },
                        label = { Text(label) },
                        enabled = !state.isConnecting && !state.isGenerating &&
                            !state.remoteGenerating,
                        modifier = Modifier.weight(1f)
                    )
                }
        }
        Text("Der gewählte Weg wird beim Antippen sofort getestet. " +
            "Wenn LAN bei aktivem Tailscale nicht erreichbar ist, prüfe Windows-Firewall, " +
            "Exit Node und lokale Netzwerkrouten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
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
            Text("Bei aktivem Tailscale und blockiertem LAN: Firewall-Regeln für das lokale Netzwerk, Exit Node und Allow LAN access prüfen.")
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

/**
 * Keep text, selection and cursor position together across recompositions.
 * A String-only TextField loses selection context when the VM publishes prompt updates,
 * which can also trigger an unexpected bring-into-view jump in the parent scroll column.
 */
/**
 * State-based Material 3 text field keeps selection separate from the UI view model.
 * Hoist the field's OWN scroll state: the old value-based overload owned its internal
 * scroll offset, which could jump back to the start after focus left and returned.
 * Important: do not nest another verticalScroll modifier on this field itself.
 */
@Composable
private fun StableMultilineField(
    id: String,
    value: String,
    label: String,
    minLines: Int,
    maxLines: Int,
    onChanged: (String) -> Unit
) {
    key(id) {
        val field = rememberTextFieldState(initialText = value)
        val fieldScroll = rememberScrollState()
        val latestOnChanged by rememberUpdatedState(onChanged)

        // External changes (e.g. "Letzte PC-Parameter übernehmen") must update
        // the field without replacing its editor instance or losing the selection.
        LaunchedEffect(value) {
            if (field.text.toString() != value) {
                field.edit {
                    val oldSelection = selection
                    replace(0, length, value)
                    selection = TextRange(
                        oldSelection.start.coerceIn(0, value.length),
                        oldSelection.end.coerceIn(0, value.length)
                    )
                }
            }
        }
        LaunchedEffect(field) {
            snapshotFlow { field.text.toString() }
                .drop(1)
                .collect { text -> latestOnChanged(text) }
        }

        OutlinedTextField(
            state = field,
            scrollState = fieldScroll,
            lineLimits = TextFieldLineLimits.MultiLine(
                minHeightInLines = minLines, maxHeightInLines = maxLines
            ),
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
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
