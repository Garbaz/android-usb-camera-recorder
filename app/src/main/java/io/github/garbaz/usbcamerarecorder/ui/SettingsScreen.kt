package io.github.garbaz.usbcamerarecorder.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import io.github.garbaz.usbcamerarecorder.ActiveStream
import io.github.garbaz.usbcamerarecorder.AltSetting
import io.github.garbaz.usbcamerarecorder.CameraController
import io.github.garbaz.usbcamerarecorder.Codec
import io.github.garbaz.usbcamerarecorder.Probe
import io.github.garbaz.usbcamerarecorder.Profiles
import io.github.garbaz.usbcamerarecorder.R
import io.github.garbaz.usbcamerarecorder.StreamConfig
import io.github.garbaz.usbcamerarecorder.UvcCamera
import io.github.garbaz.usbcamerarecorder.fpsOf

private const val REPOSITORY = "https://github.com/Garbaz/usb-camera-recorder"

/** Full-screen settings page; back (arrow, gesture or button) returns to the camera. */
@Composable
fun SettingsScreen(vm: CameraController, onBack: () -> Unit) {
    // Outside the branch below, so the position is kept while the licences are open.
    val scroll = rememberScrollState()
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    Page("Settings", onBack) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            vm.camera?.let { CameraSection(vm, it, onBack) }
            RecordingSection(vm)
            About(onLicenses = { showLicenses = true })
        }
    }
}

/** The licences of the libraries in the app, collected at build time by AboutLibraries. A library opens a
 * sheet with its licence text, which the app ships; the default would open the licence's web page. */
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Page("Open source licenses", onBack) { padding ->
        LibrariesContainer(
            libraries,
            Modifier.fillMaxSize(),
            contentPadding = padding,
            detailMode = LibraryDetailMode.Sheet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun About(onLicenses: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 8.dp))
    Hint("${stringResource(R.string.app_name)} $version, GNU GPL v3 or later")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Link("Source code") { uri.openUri(REPOSITORY) }
        Link("Report a problem") { uri.openUri("$REPOSITORY/issues") }
        Link("Open source licenses", onLicenses)
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )

@Composable
private fun CameraSection(vm: CameraController, camera: UvcCamera, onDone: () -> Unit) {
    val saved = remember(camera) { vm.manualConfig() }
    var manual by remember(camera) { mutableStateOf(saved != null) }

    SectionTitle("Camera")
    Segmented(listOf("Automatic", "Manual"), if (manual) 1 else 0) { index ->
        manual = index == 1
        // Leaving manual mode takes effect right away; entering it waits for Apply.
        if (!manual && saved != null) {
            vm.applyManual(null)
            onDone()
        }
    }
    if (manual) {
        ManualSetup(vm, camera, saved, onApply = { vm.applyManual(it); onDone() })
    } else {
        Hint("Camera's default mode, at the USB bandwidth it asks for if the phone sustains it.")
        OutlinedButton(onClick = { vm.searchAgain(); onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text("Set up again")
        }
    }
    vm.active?.let { StreamDetails(vm, camera, it) }
    if (vm.probes.size > 1) ProbeHistory(vm.probes)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

@Composable
private fun StreamDetails(vm: CameraController, camera: UvcCamera, active: ActiveStream) {
    val context = LocalContext.current
    val bps = remember(active, vm.codec) {
        Profiles.videoBitrate(context, vm.codec, active.mode.width, active.mode.height, active.config.fps)
    }
    val audio = remember { Profiles.audio(context) }
    Column(Modifier.padding(top = 12.dp)) {
        Detail("Camera", camera.name)
        Detail("Video", "${active.mode.resolution} ${active.mode.format}")
        Detail("Frame rate", "%.1f fps, %d negotiated".format(vm.fps, active.config.fps))
        Detail(
            "USB",
            active.alt?.let { alt ->
                val asked = if (active.fallback) "camera asks ${active.requestedPayload} bytes" else "as the camera asks"
                "alt ${alt.alt}, ${alt.packets}, $asked"
            } ?: "bulk transfers",
        )
        Detail("Incomplete frames", vm.incompleteFrames.toString())
        Detail("Recording", "%s, %.1f Mbit/s".format(vm.codec.label, bps / 1e6))
        Detail(
            "Audio",
            when {
                vm.microphone == null -> "None from this camera"
                !vm.microphonePermission -> "Microphone not allowed"
                else -> "AAC, %d kbit/s".format(audio.bitrate / 1000)
            },
        )
    }
    if (active.fallback) Hint("This phone cannot sustain the bandwidth the camera asks for; complete frames are checked.")
}

@Composable
private fun ProbeHistory(probes: List<Probe>) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("Setup attempts", style = MaterialTheme.typography.labelMedium)
        probes.forEach { probe ->
            val setting = probe.alt?.let { "alt ${it.alt}, ${it.packets}" } ?: "as the camera asks"
            val frames = probe.complete + probe.incomplete
            Detail(setting, if (frames > 0) "${probe.outcome.problem}, ${probe.complete}/$frames complete" else probe.outcome.problem)
        }
    }
}

/** Compact label and value line for the detected setup. */
@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManualSetup(vm: CameraController, camera: UvcCamera, saved: StreamConfig?, onApply: (StreamConfig) -> Unit) {
    val modes = camera.modes.filter { it.supported }
    if (modes.isEmpty()) {
        Hint("This camera offers no MJPEG or YUY2 mode, the formats this app can show.")
        return
    }
    val start = saved ?: vm.active?.config
    var mode by remember {
        mutableStateOf(
            modes.firstOrNull { it.formatIndex == start?.formatIndex && it.width == start.width && it.height == start.height }
                ?: CameraController.autoMode(modes)!!,
        )
    }
    val formats = modes.map { it.format }.distinct()
    val sizes = modes.filter { it.format == mode.format }
    val rates = mode.intervals.map(::fpsOf).distinct()
    var fps by remember(mode) { mutableStateOf(start?.fps?.takeIf { it in rates } ?: rates.first()) }
    // null: what the camera asks for.
    var alt by remember {
        mutableStateOf(start?.maxPayload?.takeIf { it > 0 }?.let { CameraController.altFor(camera.altSettings, it) })
    }

    if (formats.size > 1) {
        Picker("Format", formats, mode.format, { it }) { f ->
            mode = modes.filter { it.format == f }.maxBy { it.width * it.height }
        }
    }
    Picker("Resolution", sizes, mode, { it.resolution }) { mode = it }
    Picker("Frame rate", rates, fps, { "$it fps" }) { fps = it }
    if (camera.altSettings.isNotEmpty()) {
        Picker("USB bandwidth", listOf<AltSetting?>(null) + camera.altSettings, alt, ::altLabel) { alt = it }
        Hint("If there is no image, try a setting with a single packet per microframe: many phones cannot receive several.")
    }
    Button(
        onClick = { onApply(StreamConfig(mode.formatIndex, mode.width, mode.height, fps, alt?.bytes ?: 0)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Apply") }
}

private fun altLabel(alt: AltSetting?) = alt?.let { "alt ${it.alt}, ${it.packets}" } ?: "As the camera asks"

@Composable
private fun RecordingSection(vm: CameraController) {
    SectionTitle("Recording")
    val codecs = Codec.entries.filter { it.available }
    if (codecs.size > 1) {
        Segmented(codecs.map { it.label }, codecs.indexOf(vm.codec)) { vm.updateCodec(codecs[it]) }
    }
    Hint(
        when (vm.codec) {
            Codec.H264 -> "H.264 plays everywhere."
            Codec.HEVC -> "HEVC gives better quality at the same file size, but not every app or messenger plays it."
        },
    )
    SubTitle("Auto split")
    Hint(
        "Records whenever there is a picture. A break in the picture longer than the time below ends the file, " +
            "and the next picture starts a new one. A screen of one colour, like a capture device's no-signal screen, " +
            "counts as no picture.",
    )
    NumberRow("Split after", vm.splitAfterSeconds, "s", vm::updateSplitAfter)
    SaveLocationRow(vm)
}

/** Opens the system folder picker. */
@Composable
private fun SaveLocationRow(vm: CameraController) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(vm::updateSaveTree)
    }
    Row(
        Modifier.fillMaxWidth().clickable { pick.launch(vm.saveLocation.tree) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Save to", Modifier.weight(1f))
        Text(vm.saveLocation.label, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

@Composable
private fun SubTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun Hint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

/**
 * A whole number of at least 1; incomplete input keeps the last valid value. An outlined text field at
 * button height: [OutlinedTextField] has a fixed minimum height, its decoration box does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberRow(label: String, value: Int, unit: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter(Char::isDigit).take(5)
                text.toIntOrNull()?.takeIf { it >= 1 }?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interaction,
            modifier = Modifier.width(110.dp).height(ButtonDefaults.MinHeight),
        ) { field ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = text,
                innerTextField = field,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                suffix = { Text(unit) },
                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
            )
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun <T> Picker(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Box {
            Text(text(selected), color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach {
                    DropdownMenuItem(text = { Text(text(it)) }, onClick = { onSelect(it); open = false })
                }
            }
        }
    }
}
