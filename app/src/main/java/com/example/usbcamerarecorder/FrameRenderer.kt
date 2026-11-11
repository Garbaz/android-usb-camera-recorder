package com.example.usbcamerarecorder

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
        synchronized(lock) {
            preview?.let { draw(it, bmp) }
            encoder?.let { draw(it, bmp) }
        }
        lastFrameAt = SystemClock.elapsedRealtime()
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
