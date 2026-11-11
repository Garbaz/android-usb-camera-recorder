package io.github.garbaz.usbcamerarecorder

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

/** A frame descriptor of the camera: one resolution in one format, with its frame intervals. */
data class FrameMode(
    val formatIndex: Int,
    val format: String,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val defaultInterval: Int,
    /** Frame intervals in 100 ns units, shortest (fastest) first. */
    val intervals: List<Int>,
) {
    /** Formats [FrameRenderer] can draw. */
    val supported get() = format == "MJPEG" || format == "YUY2"
    val resolution get() = "$width x $height"
}

/** Integer fps as libuvc matches it against the frame intervals. */
fun fpsOf(interval: Int) = 10_000_000 / interval

/**
 * An isochronous alternate setting: [bytes] per 125 µs microframe, sent as [transactions]
 * packets (high-speed endpoints carry up to 3 per microframe).
 */
data class AltSetting(val iface: Int, val alt: Int, val bytes: Int, val transactions: Int) {
    val singlePacket get() = transactions == 1
    val packets get() = if (singlePacket) "$bytes bytes" else "$transactions x ${bytes / transactions} bytes"
}

/** What gets negotiated: [maxPayload] 0 lets the device choose the bandwidth. */
data class StreamConfig(val formatIndex: Int, val width: Int, val height: Int, val fps: Int, val maxPayload: Int) {
    fun encode() = "$formatIndex,$width,$height,$fps,$maxPayload"

    companion object {
        fun decode(s: String?) = s?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 5 }
            ?.let { (f, w, h, fps, p) -> StreamConfig(f, w, h, fps, p) }
    }
}

class UvcCamera private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val handle: Long,
) : Closeable {
    val modes: List<FrameMode> = parseModes(UvcNative.formats(handle))
    val altSettings: List<AltSetting> = UvcNative.altSettings(handle).toList().chunked(4)
        .map { (i, a, b, t) -> AltSetting(i, a, b, t) }.sortedBy { it.bytes }

    val name: String get() = device.productName?.trim().orEmpty().ifEmpty { device.deviceName }
    val key: String get() = "%04x:%04x".format(device.vendorId, device.productId)

    private var streaming = false

    /** Returns the bytes per microframe the device asked for. */
    fun start(config: StreamConfig, sink: UvcNative.FrameSink): Int {
        stop()
        return UvcNative.start(
            handle, config.formatIndex, config.width, config.height, config.fps, config.maxPayload, sink,
        ).also { streaming = true }
    }

    fun stop() {
        if (streaming) UvcNative.stop(handle)
        streaming = false
    }

    override fun close() {
        UvcNative.close(handle)
        connection.close()
    }

    companion object {
        fun isUvc(device: UsbDevice) = (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }

        fun open(usb: UsbManager, device: UsbDevice): UvcCamera {
            val connection = usb.openDevice(device) ?: throw IOException("Could not open ${device.deviceName}")
            val handle = try {
                UvcNative.open(connection.fileDescriptor)
            } catch (e: IOException) {
                connection.close()
                throw e
            }
            return UvcCamera(device, connection, handle)
        }

        private fun parseModes(raw: IntArray): List<FrameMode> {
            val modes = mutableListOf<FrameMode>()
            var i = 0
            while (i < raw.size) {
                val n = raw[i + 7]
                modes += FrameMode(
                    formatIndex = raw[i],
                    format = formatName(raw[i + 1], raw[i + 2]),
                    frameIndex = raw[i + 3],
                    width = raw[i + 4],
                    height = raw[i + 5],
                    defaultInterval = raw[i + 6],
                    intervals = raw.slice(i + 8 until i + 8 + n).sorted(),
                )
                i += 8 + n
            }
            return modes
        }

        // VS descriptor subtypes, UVC 1.5 table A-6.
        private fun formatName(subtype: Int, fourcc: Int) = when (subtype) {
            0x06 -> "MJPEG"
            0x04, 0x10 -> String(ByteArray(4) { (fourcc shr (8 * it)).toByte() }, Charsets.US_ASCII)
                .trim { it <= ' ' }.ifEmpty { "RAW" }
            else -> "0x%02x".format(subtype)
        }
    }
}
