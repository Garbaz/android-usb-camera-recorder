package com.example.usbcamerarecorder

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.roundToInt

sealed interface Status {
    data object NoCamera : Status
    data object NeedCameraPermission : Status
    data object NeedUsbPermission : Status
    data class Connecting(val detail: String) : Status
    data object Streaming : Status

    /** Camera open, stream stopped while no screen shows it and nothing records. */
    data object Paused : Status
    data class Failed(val message: String) : Status
}

/** The running stream: what was negotiated and which bandwidth is in use. */
data class ActiveStream(
    val config: StreamConfig,
    val mode: FrameMode,
    /** Bytes per microframe the camera asked for. */
    val requestedPayload: Int,
    /** The altsetting libuvc selected. */
    val alt: AltSetting?,
    val manual: Boolean,
) {
    /** Automatic setup had to go below what the camera asked for. */
    val fallback get() = !manual && config.maxPayload in 1 until requestedPayload
}

data class Recording(val name: String, val startedAt: Long)

/** One setup attempt: [alt] null when the stream did not start far enough to know it. */
data class Probe(val maxPayload: Int, val alt: AltSetting?, val outcome: Outcome, val complete: Long, val incomplete: Long)

enum class Outcome(val problem: String) {
    OK("Works"),
    START_FAILED("The stream does not start"),
    NO_FRAMES("No image arrives"),
    INCOMPLETE("Frames arrive incomplete"),
}

/**
 * Owns the camera, the stream and the recorder for the whole process, so recording continues
 * without a visible activity ([RecordingService] keeps the process alive meanwhile).
 */
class CameraController(private val app: Application) {
    private val usb = app.getSystemService(UsbManager::class.java)
    private val renderer = FrameRenderer()
    val settings = Settings(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serializes everything that touches the camera or the recorder. */
    private val lock = Mutex()

    var status by mutableStateOf<Status>(Status.NoCamera); private set
    var camera by mutableStateOf<UvcCamera?>(null); private set
    var active by mutableStateOf<ActiveStream?>(null); private set
    var fps by mutableStateOf(0.0); private set
    var recording by mutableStateOf<Recording?>(null); private set
    var message by mutableStateOf<String?>(null)

    /** The attempts of the last setup, in order. */
    var probes by mutableStateOf<List<Probe>>(emptyList()); private set

    /** Frames of the running stream that arrived incomplete and were skipped. */
    var incompleteFrames by mutableStateOf(0L); private set
    private var badBaseline = 0L

    var codec by mutableStateOf(settings.codec); private set
    var autoRecord by mutableStateOf(settings.autoRecord); private set

    private var cameraPermission = false
    private var uiVisible = false
    private var recorder: Recorder? = null

    /** What the open camera asks for; known after the first start. */
    private var requestedPayload: Int? = null

    /** Payloads that stalled mid-stream with the open camera; automatic setup skips them. */
    private val unreliable = mutableSetOf<Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SHUTDOWN) {
                // Finalize the MP4 before power goes; an unfinished one is unreadable.
                runBlocking { withTimeoutOrNull(3000) { lock.withLock { stopRecording() } } }
                return
            }
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device == camera?.device) disconnect()
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else status = Status.NeedUsbPermission
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, receiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
                addAction(Intent.ACTION_SHUTDOWN)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { monitor() }
    }

    private fun io(block: suspend () -> Unit) = scope.launch(Dispatchers.IO) { lock.withLock { block() } }

    fun setPreview(surface: Surface?) = renderer.setPreview(surface)

    /** Streams while the activity is visible or a recording runs. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        io {
            if (visible && status == Status.Paused) startStream()
            if (!visible && recorder == null) pause()
        }
    }

    private fun pause() {
        val cam = camera ?: return
        cam.stop()
        active = null
        status = Status.Paused
    }

    /** Android 9+ only grants USB access to video devices to apps holding CAMERA. */
    fun onCameraPermission(granted: Boolean, intent: Intent? = null) {
        cameraPermission = granted
        if (!granted) status = Status.NeedCameraPermission else findCamera(intent)
    }

    /** Uses the device from a USB_DEVICE_ATTACHED launch intent, else the first UVC device present. */
    fun findCamera(intent: Intent? = null) {
        if (!cameraPermission || camera != null) return
        val fromIntent = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        }
        (fromIntent ?: usb.deviceList.values.firstOrNull(UvcCamera::isUvc))?.let(::connect)
    }

    private fun connect(device: UsbDevice) {
        if (!cameraPermission || camera != null || !UvcCamera.isUvc(device)) return
        if (!usb.hasPermission(device)) {
            status = Status.NeedUsbPermission
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE))
            return
        }
        io {
            // Permission result, attach broadcast and launch intent can all race to get here.
            if (camera != null) return@io
            status = Status.Connecting("Opening ${device.productName ?: device.deviceName}")
            try {
                camera = UvcCamera.open(usb, device)
                requestedPayload = null
                unreliable.clear()
                if (uiVisible) startStream() else status = Status.Paused
            } catch (e: IOException) {
                status = Status.Failed(e.message ?: "Cannot open the camera")
            }
        }
    }

    private fun disconnect() = io {
        val wasRecording = recorder != null
        stopRecording(if (wasRecording) "The camera was disconnected. Recording saved" else null)
        closeCamera()
        status = Status.NoCamera
    }

    private fun closeCamera() {
        camera?.stop()
        camera?.close()
        camera = null
        active = null
    }

    /** Sets the stream up again, e.g. after the manual setup changed. A running recording continues. */
    fun restart() = io { startStream() }

    /** Forgets which bandwidths failed and probes again from what the camera asks for. */
    fun searchAgain() = io {
        unreliable.clear()
        requestedPayload = null
        startStream()
    }

    /** A manual setup if one is saved, otherwise automatic probing. Call with [lock] held. */
    private suspend fun startStream(): Boolean {
        val cam = camera ?: return false
        cam.stop()
        active = null
        probes = emptyList()
        settings.manualConfig(cam.key)?.let { manual ->
            val mode = cam.modeFor(manual)
            val outcome = if (mode == null) Outcome.START_FAILED else attempt(cam, mode, manual, manual = true)
            if (outcome == Outcome.OK) return onStreaming()
            status = Status.Failed("${outcome.problem} with the manual setup. Change it in settings, or switch back to Auto.")
            return false
        }

        val mode = autoMode(cam.modes) ?: run {
            val offered = cam.modes.map { it.format }.distinct().joinToString(", ")
            status = Status.Failed("The camera has no MJPEG or YUY2 mode. It offers ${offered.ifEmpty { "nothing" }}.")
            return false
        }
        val interval = mode.intervals.firstOrNull { it == mode.defaultInterval } ?: mode.intervals.first()
        val base = StreamConfig(mode.formatIndex, mode.width, mode.height, fpsOf(interval), 0)

        // Bandwidth beyond what the frames need does not change the picture, so the camera's own
        // request is the best setting. Smaller ones are only for phones that cannot sustain it,
        // largest first, and only if they deliver complete frames.
        var last = Outcome.START_FAILED
        if (requestedPayload == null || requestedPayload !in unreliable) {
            last = attempt(cam, mode, base, manual = false)
            if (last == Outcome.OK) return onStreaming()
        }
        val requested = requestedPayload ?: Int.MAX_VALUE
        for (alt in cam.altSettings.filter { it.bytes < requested && it.bytes !in unreliable }.sortedByDescending { it.bytes }) {
            last = attempt(cam, mode, base.copy(maxPayload = alt.bytes), manual = false)
            if (last == Outcome.OK) return onStreaming()
        }
        // No setting was clean, e.g. with a poor picture source: take the one that came closest.
        probes.filter { it.outcome == Outcome.INCOMPLETE && it.complete > 0 }.maxByOrNull { it.complete }?.let { best ->
            if (attempt(cam, mode, base.copy(maxPayload = best.maxPayload), manual = false, lenient = true) == Outcome.OK) {
                return onStreaming()
            }
        }
        status = if (recorder != null) Status.Connecting("No usable stream yet. The recording continues and resumes with the picture.")
        else Status.Failed("${last.problem} at every USB bandwidth. Try a manual setup in settings.")
        return false
    }

    private fun onStreaming(): Boolean {
        status = Status.Streaming
        if (autoRecord && recorder == null) startRecording()
        return true
    }

    /**
     * Starts [config] and checks it: frames must arrive, and over one second at most 1 in 20
     * may be incomplete. Leaves the stream running only on [Outcome.OK].
     */
    private suspend fun attempt(cam: UvcCamera, mode: FrameMode, config: StreamConfig, manual: Boolean, lenient: Boolean = manual): Outcome {
        val bandwidth = if (config.maxPayload == 0) "the camera's bandwidth"
        else "alt ${altFor(cam.altSettings, config.maxPayload)?.alt}, ${config.maxPayload} bytes"
        status = Status.Connecting("${mode.resolution} ${mode.format} at ${config.fps} fps with $bandwidth")
        Log.i(TAG, "trying ${mode.resolution} ${mode.format} ${config.fps} fps, $bandwidth")
        active = null
        lastCounts = 0L to 0L
        val outcome = check(cam, mode, config, manual, lenient)
        val payload = if (config.maxPayload > 0) config.maxPayload else requestedPayload
        val alt = active?.alt ?: payload?.let { altFor(cam.altSettings, it) }
        probes = probes + Probe(config.maxPayload, alt, outcome, lastCounts.first, lastCounts.second)
        if (outcome == Outcome.OK) {
            badBaseline = renderer.badFrames.get()
            incompleteFrames = 0
        }
        return outcome
    }

    /** Complete and incomplete frames of the last [check] window. */
    private var lastCounts = 0L to 0L

    /**
     * Starts [config] and judges [CHECK_FRAMES] frames after the first [SKIP_FRAMES]. Strict: at
     * most 1 in 20 incomplete. Lenient: any complete frame. Leaves the stream running on OK.
     */
    private suspend fun check(cam: UvcCamera, mode: FrameMode, config: StreamConfig, manual: Boolean, lenient: Boolean): Outcome {
        renderer.mode = mode
        val requested = try {
            cam.start(config, renderer)
        } catch (e: IOException) {
            Log.w(TAG, "start failed: ${e.message}")
            cam.stop()
            return Outcome.START_FAILED
        }
        requestedPayload = requested
        val effective = if (config.maxPayload > 0) minOf(config.maxPayload, requested) else requested

        val seen = { renderer.frames.get() + renderer.badFrames.get() }
        if (!awaitFrames(seen, 1, FIRST_FRAME_MS)) {
            Log.w(TAG, "no frames")
            cam.stop()
            return Outcome.NO_FRAMES
        }
        // Frames may come slower than negotiated (dim webcams, PAL sources): allow twice the time.
        val frameMs = 1000L / config.fps.coerceAtLeast(1)
        awaitFrames(seen, SKIP_FRAMES, 2 * SKIP_FRAMES * frameMs)
        val good0 = renderer.frames.get()
        val bad0 = renderer.badFrames.get()
        awaitFrames(seen, CHECK_FRAMES, 2 * CHECK_FRAMES * frameMs)
        val good = renderer.frames.get() - good0
        val bad = renderer.badFrames.get() - bad0
        lastCounts = good to bad
        // Lenient: any image beats none, e.g. when the source itself is bad.
        val usable = good > 0 && (lenient || bad * 20 <= good + bad)
        if (!usable) {
            Log.w(TAG, "$good complete, $bad incomplete frames")
            cam.stop()
            return Outcome.INCOMPLETE
        }
        active = ActiveStream(config, mode, requested, altFor(cam.altSettings, effective), manual)
        return Outcome.OK
    }

    /** Waits until [count] more frames were seen or [timeoutMs] passed; true if they arrived. */
    private suspend fun awaitFrames(seen: () -> Long, count: Int, timeoutMs: Long): Boolean {
        val target = seen() + count
        return withTimeoutOrNull(timeoutMs) { while (seen() < target) delay(10) } != null
    }

    private fun stalled(now: Long): Boolean {
        val fps = active?.config?.fps?.coerceAtLeast(1) ?: return false
        return now - renderer.lastFrameAt > STALL_FRAMES * 1000L / fps
    }

    /** Measures fps and restarts a stream that stopped delivering frames. */
    private suspend fun monitor() {
        var last = renderer.frames.get()
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val frames = renderer.frames.get()
            fps = (frames - last) * 1000.0 / (now - lastAt)
            last = frames
            lastAt = now
            if (status == Status.Streaming) incompleteFrames = renderer.badFrames.get() - badBaseline
            if (status == Status.Streaming && stalled(now)) recover()
        }
    }

    /**
     * The stream stopped delivering frames. That can be the USB link or the camera itself (an
     * analog receiver losing its signal), so the same setup is tried again before a smaller
     * bandwidth. A running recording stays open meanwhile; it only ends on disconnect or stop.
     */
    private fun recover() = scope.launch(Dispatchers.IO) {
        val recovered = lock.withLock {
            val stream = active ?: return@launch
            if (status != Status.Streaming || !stalled(SystemClock.elapsedRealtime())) return@launch
            val cam = camera ?: return@launch
            Log.w(TAG, "stream stalled at ${stream.alt}")
            status = Status.Connecting("The stream stopped, restarting it")
            cam.stop()
            probes = emptyList()
            if (attempt(cam, stream.mode, stream.config, stream.manual) == Outcome.OK) return@withLock onStreaming()
            // It starts but does not last: this phone cannot sustain the bandwidth.
            if (!stream.manual) unreliable += if (stream.config.maxPayload == 0) stream.requestedPayload else stream.config.maxPayload
            startStream()
        }
        // Keep trying for as long as there is a recording to continue.
        if (!recovered) {
            while (true) {
                delay(3000)
                val done = lock.withLock {
                    when {
                        camera == null || recorder == null || status == Status.Streaming -> true
                        else -> startStream()
                    }
                }
                if (done) break
            }
        }
    }

    fun toggleRecording() = io {
        if (recorder != null) stopRecording() else if (status == Status.Streaming) startRecording()
    }

    fun stopRecordingFromNotification() = io { stopRecording() }

    private fun startRecording() {
        val stream = active ?: return
        // Encode at the rate frames actually arrive, which can be below the negotiated one.
        val rate = fps.roundToInt().takeIf { it > 0 }?.coerceAtMost(stream.config.fps) ?: stream.config.fps
        try {
            lateinit var rec: Recorder
            rec = Recorder.start(app, stream.mode.width, stream.mode.height, rate, codec) {
                io { if (recorder === rec) stopRecording("The encoder failed (is storage full?). Recording saved") }
            }
            recorder = rec
            renderer.setEncoder(rec.surface)
            recording = Recording(rec.name, SystemClock.elapsedRealtime())
            ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        } catch (e: Exception) {
            message = "Cannot record: ${e.message}"
        }
    }

    private fun stopRecording(reason: String? = null) {
        val rec = recorder ?: return
        renderer.setEncoder(null)
        recorder = null
        recording = null
        val saved = rec.stop()
        app.stopService(Intent(app, RecordingService::class.java))
        message = when {
            !saved -> "The recording was empty and was discarded"
            reason != null -> "$reason as ${rec.name}"
            else -> "Saved to Movies/${Recorder.FOLDER}/${rec.name}"
        }
        if (!uiVisible) pause()
    }

    fun updateCodec(v: Codec) { settings.codec = v; codec = v }
    fun updateAutoRecord(v: Boolean) { settings.autoRecord = v; autoRecord = v }

    /** null switches back to automatic setup. */
    fun applyManual(config: StreamConfig?) {
        val cam = camera ?: return
        settings.setManualConfig(cam.key, config)
        restart()
    }

    fun manualConfig(): StreamConfig? = camera?.let { settings.manualConfig(it.key) }

    companion object {
        private const val TAG = "UsbCamera"
        private const val ACTION_USB_PERMISSION = "com.example.usbcamerarecorder.USB_PERMISSION"

        /** UVC cameras can take over a second to deliver their first frame (sensor and exposure startup). */
        private const val FIRST_FRAME_MS = 3000L

        /** Frames right after a start are often cut short; they are not judged. */
        private const val SKIP_FRAMES = 3

        /** Frames judged per setup attempt: one second at 30 fps. */
        private const val CHECK_FRAMES = 30

        /** A stream counts as stalled after this many frame intervals without a complete frame: 2 s at 30 fps. */
        private const val STALL_FRAMES = 60

        /**
         * Largest mode up to 1080p (bigger frames decode too slowly in software). MJPEG if the
         * camera has it: uncompressed modes need far more USB bandwidth and an extra conversion.
         */
        fun autoMode(modes: List<FrameMode>): FrameMode? {
            val usable = modes.filter { it.supported }
            return usable.filter { it.format == "MJPEG" }.ifEmpty { usable }
                .sortedBy { it.width * it.height }
                .let { sorted -> sorted.lastOrNull { it.width * it.height <= 1920 * 1080 } ?: sorted.firstOrNull() }
        }

        /** The altsetting libuvc picks for a payload: the first one whose packets hold it. */
        fun altFor(alts: List<AltSetting>, payload: Int) = alts.firstOrNull { it.bytes >= payload }
    }
}

fun UvcCamera.modeFor(config: StreamConfig) = modes.firstOrNull {
    it.supported && it.formatIndex == config.formatIndex && it.width == config.width && it.height == config.height &&
        it.intervals.any { i -> fpsOf(i) == config.fps }
}
