package io.github.garbaz.usbcamerarecorder.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.garbaz.usbcamerarecorder.CameraController
import io.github.garbaz.usbcamerarecorder.Recording
import io.github.garbaz.usbcamerarecorder.Status
import kotlinx.coroutines.delay

private val RecordRed = Color(0xFFE53935)
private val Scrim = Color(0x99000000)
private val StatPadding = 10.dp

@Composable
fun CameraScreen(vm: CameraController, onRequestCameraPermission: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val streaming = vm.status == Status.Streaming

    LaunchedEffect(vm.message) {
        vm.message?.let {
            vm.message = null
            snackbar.showSnackbar(it)
        }
    }
    val view = LocalView.current
    DisposableEffect(streaming) {
        view.keepScreenOn = streaming
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val mode = vm.active?.mode
        val aspect = if (mode != null) mode.width.toFloat() / mode.height else 4f / 3f
        Preview(onSurface = vm::setPreview, modifier = Modifier.align(Alignment.Center).aspectRatio(aspect))

        // Landscape: the record button sits at the right edge, in the bar beside the picture or over
        // its edge. The info goes into the left bar only if that is wide enough, else above the picture.
        val landscape = maxWidth > maxHeight
        val bar = (maxWidth - minOf(maxWidth, maxHeight * aspect)) / 2
        val startInset = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(LocalLayoutDirection.current)
        val infoWidth = bar - startInset - 24.dp
        // The bar works if resolution and frame rate fit on one line; a long camera name may wrap.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val needed = with(density) {
            listOfNotNull(mode?.resolution, "000.0 fps", "Resolution", "Frame rate").maxOf {
                measurer.measure(it, MaterialTheme.typography.labelLarge).size.width
            }.toDp()
        } + StatPadding * 2
        val infoInBar = landscape && infoWidth >= needed

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    if (streaming) {
                        StreamInfo(vm, vertical = infoInBar, if (infoInBar) Modifier.widthIn(max = infoWidth) else Modifier)
                    }
                }
                IconButton(onClick = { showSettings = true }, modifier = Modifier.clip(CircleShape).background(Scrim)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            if (!streaming) {
                StatusMessage(
                    vm.status,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onRetry = { if (vm.camera == null) vm.findCamera() else vm.restart() },
                    onManualSetup = { showSettings = true },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Column(
                Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                vm.recording?.let { RecordingTimer(it) }
                // A recording can always be stopped, also while the stream reconnects.
                RecordButton(recording = vm.recording != null, enabled = streaming || vm.recording != null, onClick = { vm.toggleRecording() })
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }

    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
}

@Composable
private fun Preview(onSurface: (android.view.Surface?) -> Unit, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        onSurface(holder.surface)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                })
            }
        },
    )
}

@Composable
private fun StreamInfo(vm: CameraController, vertical: Boolean, modifier: Modifier) {
    val active = vm.active ?: return
    val items: @Composable () -> Unit = {
        vm.camera?.let { Stat("Camera", it.name) }
        Stat("Resolution", active.mode.resolution)
        Stat("Frame rate", "%.1f fps".format(vm.fps))
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { items() }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { items() }
}

@Composable
private fun Stat(caption: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Scrim).padding(horizontal = StatPadding, vertical = 6.dp)) {
        Text(caption, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun StatusMessage(
    status: Status,
    onRequestCameraPermission: () -> Unit,
    onRetry: () -> Unit,
    onManualSetup: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val text = when (status) {
            Status.NoCamera -> "Connect a USB camera"
            Status.NeedCameraPermission -> "Android only gives apps with camera permission access to USB cameras."
            Status.NeedUsbPermission -> "Allow access to the USB camera to continue."
            is Status.Connecting -> "Setting up the camera\n${status.detail}"
            is Status.Failed -> status.message
            Status.Paused, Status.Streaming -> ""
        }
        if (status is Status.Connecting) CircularProgressIndicator(color = Color.White)
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        when (status) {
            Status.NeedCameraPermission -> FilledTonalButton(onClick = onRequestCameraPermission) { Text("Grant permission") }
            Status.NeedUsbPermission -> FilledTonalButton(onClick = onRetry) { Text("Ask again") }
            is Status.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry) { Text("Retry") }
                FilledTonalButton(onClick = onManualSetup) { Text("Manual setup") }
            }
            else -> Unit
        }
    }
}

@Composable
private fun RecordingTimer(recording: Recording) {
    val elapsed by produceState(0L, recording) {
        while (true) {
            value = (android.os.SystemClock.elapsedRealtime() - recording.startedAt) / 1000
            delay(250)
        }
    }
    Row(
        Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(Scrim)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(RecordRed))
        Text("%02d:%02d".format(elapsed / 60, elapsed % 60), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 28.dp else 56.dp, label = "record")
    val corner by animateDpAsState(if (recording) 6.dp else 28.dp, label = "corner")
    Box(
        Modifier.size(72.dp).clip(CircleShape).border(4.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(if (enabled) RecordRed else Color.DarkGray))
    }
}
