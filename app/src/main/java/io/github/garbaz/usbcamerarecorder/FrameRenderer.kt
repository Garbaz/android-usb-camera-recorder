package io.github.garbaz.usbcamerarecorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodes MJPEG or YUY2 frames and draws each one onto the preview surface and, while recording,
 * the encoder's input surface. Runs on libuvc's callback thread.
 */
class FrameRenderer : UvcNative.FrameSink {
    private val lock = Any()
    private var preview: Surface? = null
    private var encoder: Surface? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Complete, decoded frames since creation. */
    val frames = AtomicLong()

    /** Frames that arrived incomplete (lost USB packets) or could not be decoded; they are not shown. */
    val badFrames = AtomicLong()

    /** [SystemClock.elapsedRealtime] of the last good frame. */
    @Volatile var lastFrameAt = 0L
        private set

    /** Whether the last good frame showed a picture; see [hasPicture]. */
    @Volatile var signal = false
        private set

    /** [SystemClock.elapsedRealtime] of the last frame with a picture. */
    @Volatile var lastSignalAt = 0L
        private set

    /** When set, frames without a picture are not passed to the encoder. */
    @Volatile var recordOnlySignal = false

    /** Set before starting a stream; frames are interpreted accordingly. */
    @Volatile var mode: FrameMode? = null

    fun setPreview(surface: Surface?) = synchronized(lock) { preview = surface }

    fun setEncoder(surface: Surface?) = synchronized(lock) { encoder = surface }

    override fun onFrame(data: ByteArray, length: Int) {
        val mode = mode ?: return
        val bmp = when (mode.format) {
            "MJPEG" -> if (complete(data, length)) decode(data, length) else null
            // Uncompressed: a frame is complete when it has all its bytes.
            "YUY2" -> if (length >= mode.width * mode.height * 2) decodeYuy2(data, mode.width, mode.height) else null
            else -> null
        }
        if (bmp == null) {
            badFrames.incrementAndGet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        signal = hasPicture(bmp)
        if (signal) lastSignalAt = now
        synchronized(lock) {
            preview?.let { draw(it, bmp) }
            if (signal || !recordOnlySignal) encoder?.let { draw(it, bmp) }
        }
        lastFrameAt = now
        frames.incrementAndGet()
    }

    /**
     * A JPEG ends with the end-of-image marker FF D9; a frame cut short by lost packets does not.
     * Some cameras pad frames after the marker, so trailing zero (and fill) bytes are skipped first.
     */
    private fun complete(data: ByteArray, length: Int): Boolean {
        var end = length
        while (end > 0 && (data[end - 1] == 0.toByte() || data[end - 1] == 0xFF.toByte())) end--
        return end >= 2 && data[end - 2] == 0xFF.toByte() && data[end - 1] == 0xD9.toByte()
    }

    private fun decode(data: ByteArray, length: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = bitmap
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(data, 0, length, opts)
        } catch (_: IllegalArgumentException) {
            // The reusable bitmap no longer fits (resolution changed).
            BitmapFactory.decodeByteArray(data, 0, length, BitmapFactory.Options().apply { inMutable = true })
        }
        if (bmp != null) bitmap = bmp
        return bmp
    }

    /**
     * Capture devices send a single flat colour (blue or black) while their input has no signal;
     * a real picture, even static noise, varies. Samples a 32 x 24 grid.
     */
    private fun hasPicture(bmp: Bitmap): Boolean {
        // Per colour channel: a flat colour has a narrow range in each, whatever the colour.
        val lo = intArrayOf(0xFF, 0xFF, 0xFF)
        val hi = intArrayOf(0, 0, 0)
        for (y in 0 until 24) for (x in 0 until 32) {
            val c = bmp.getPixel((2 * x + 1) * bmp.width / 64, (2 * y + 1) * bmp.height / 48)
            for (ch in 0..2) {
                val v = (c shr (8 * ch)) and 0xFF
                if (v < lo[ch]) lo[ch] = v
                if (v > hi[ch]) hi[ch] = v
            }
        }
        // Tolerance for compression artefacts on the flat frame.
        return (0..2).any { hi[it] - lo[it] > 16 }
    }

    /** Android converts YUY2 only via JPEG; fine for the low resolutions YUY2 cameras deliver at speed. */
    private fun decodeYuy2(data: ByteArray, width: Int, height: Int): Bitmap? {
        val jpeg = ByteArrayOutputStream()
        YuvImage(data, ImageFormat.YUY2, width, height, null).compressToJpeg(Rect(0, 0, width, height), 95, jpeg)
        return decode(jpeg.toByteArray(), jpeg.size())
    }

    /** Stretches the frame over the whole surface; callers size surfaces to the video's aspect. */
    private fun draw(surface: Surface, bmp: Bitmap) {
        try {
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: IllegalStateException) {
            // Surface is being torn down; the frame is dropped.
        } catch (_: IllegalArgumentException) {
        }
    }
}
