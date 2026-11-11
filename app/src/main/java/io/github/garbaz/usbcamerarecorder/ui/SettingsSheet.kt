package io.github.garbaz.usbcamerarecorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.garbaz.usbcamerarecorder.ActiveStream
import io.github.garbaz.usbcamerarecorder.AltSetting
import io.github.garbaz.usbcamerarecorder.Bitrate
import io.github.garbaz.usbcamerarecorder.CameraController
import io.github.garbaz.usbcamerarecorder.Codec
import io.github.garbaz.usbcamerarecorder.Probe
import io.github.garbaz.usbcamerarecorder.Recorder
import io.github.garbaz.usbcamerarecorder.StreamConfig
import io.github.garbaz.usbcamerarecorder.UvcCamera
import io.github.garbaz.usbcamerarecorder.fpsOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: CameraController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            vm.camera?.let { CameraSection(vm, it, onDismiss) }
            RecordingSection(vm)
        }
    }
}

@Composable
private fun CameraSection(vm: CameraController, camera: UvcCamera, onDismiss: () -> Unit) {
    val saved = remember(camera) { vm.manualConfig() }
    var manual by remember(camera) { mutableStateOf(saved != null) }

    SectionTitle("Camera")
    InfoRow("Device", camera.name)
    Segmented(listOf("Automatic", "Manual"), if (manual) 1 else 0) { index ->
        manual = index == 1
        // Leaving manual mode takes effect right away; entering it waits for Apply.
        if (!manual && saved != null) {
            vm.applyManual(null)
            onDismiss()
        }
    }
    if (manual) {
        ManualSetup(vm, camera, saved, onApply = { vm.applyManual(it); onDismiss() })
    } else {
        Hint("Uses the camera's default resolution and frame rate, and the USB bandwidth the camera asks for when the phone can sustain it.")
        OutlinedButton(onClick = { vm.searchAgain(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
            Text("Set up again")
        }
    }
    vm.active?.let { StreamDetails(vm, it) }
    if (vm.probes.size > 1) ProbeHistory(vm.probes)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
}

@Composable
private fun StreamDetails(vm: CameraController, active: ActiveStream) {
    SubTitle("Current stream")
    InfoRow("Resolution", active.mode.resolution)
    InfoRow("Format", active.mode.format)
    InfoRow("Frame rate, negotiated", "${active.config.fps} fps")
    InfoRow("Frame rate, measured", "%.1f fps".format(vm.fps))
    InfoRow("Incomplete frames", vm.incompleteFrames.toString())
    active.alt?.let {
        InfoRow("USB alternate setting", it.alt.toString())
        InfoRow("Per microframe", it.packets)
    } ?: InfoRow("USB transfer", "Bulk")
    InfoRow("Camera asks for", "${active.requestedPayload} bytes")
    if (active.fallback) {
        Hint(
            "This phone could not sustain what the camera asks for, so a smaller setting is used. The picture " +
                "is the same as long as frames arrive complete.",
        )
    }
}

@Composable
private fun ProbeHistory(probes: List<Probe>) {
    SubTitle("Setup attempts")
    probes.forEach { probe ->
        val setting = probe.alt?.let { "alt ${it.alt}, ${it.packets}" } ?: "As the camera asks"
        val result = if (probe.complete + probe.incomplete > 0) {
            "${probe.outcome.problem}, ${probe.complete} of ${probe.complete + probe.incomplete} frames complete"
        } else {
            probe.outcome.problem
        }
        InfoRow(setting, result)
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
    vm.active?.let { active ->
        val context = LocalContext.current
        val bps = remember(active, vm.codec) {
            Bitrate.forStream(context, vm.codec, active.mode.width, active.mode.height, active.config.fps)
        }
        InfoRow("Bitrate", "%.1f Mbit/s".format(bps / 1e6))
        Hint("The bitrate this phone uses for its own videos at this resolution.")
    }
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Start recording when the camera connects", Modifier.weight(1f))
        Switch(checked = vm.autoRecord, onCheckedChange = vm::updateAutoRecord)
    }
    InfoRow("Saved to", "Movies/${Recorder.FOLDER}")
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
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
