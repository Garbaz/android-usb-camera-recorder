package io.github.garbaz.usbcamerarecorder

/** Bindings to cpp/uvccam.c (libuvc on top of libusb). Not thread-safe: call from one thread. */
object UvcNative {
    init {
        System.loadLibrary("uvccam")
    }

    /** Called on libuvc's callback thread; [data] is reused for the next frame. */
    fun interface FrameSink {
        fun onFrame(data: ByteArray, length: Int)
    }

    /** Wraps an opened usbfs fd. Returns a handle for the other calls. */
    external fun open(fd: Int): Long

    /** Per frame descriptor: formatIndex, subtype, fourcc, frameIndex, w, h, defaultInterval, n, intervals[n]. */
    external fun formats(handle: Long): IntArray

    /** Per isochronous altsetting: interfaceNumber, altSetting, bytesPerInterval, transactionsPerInterval. */
    external fun altSettings(handle: Long): IntArray

    /**
     * Negotiates and starts streaming. [maxPayload] > 0 caps the bytes per microframe,
     * which selects a smaller altsetting. Returns the payload size the device asked for.
     */
    external fun start(
        handle: Long, formatIndex: Int, width: Int, height: Int, fps: Int, maxPayload: Int, sink: FrameSink,
    ): Int

    external fun stop(handle: Long)

    external fun close(handle: Long)
}
