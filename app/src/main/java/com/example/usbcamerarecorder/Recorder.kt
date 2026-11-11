package com.example.usbcamerarecorder

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ln

enum class Codec(val label: String, val encoder: Int, val mime: String) {
    H264("H.264", MediaRecorder.VideoEncoder.H264, MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("HEVC", MediaRecorder.VideoEncoder.HEVC, MediaFormat.MIMETYPE_VIDEO_HEVC);

    /** Video capabilities of the first encoder for this codec that handles [width] x [height]. */
    fun capabilities(width: Int, height: Int): MediaCodecInfo.VideoCapabilities? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && mime in it.supportedTypes }
            .sortedBy { !it.isHardwareAccelerated }
            .mapNotNull { it.getCapabilitiesForType(mime).videoCapabilities }
            .firstOrNull { it.isSizeSupported(width and 1.inv(), height and 1.inv()) }

    val available by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && mime in it.supportedTypes }
    }
}

/**
 * The bitrate the phone itself records its camera with at a similar resolution (its camcorder
 * profiles), scaled to the stream by pixel rate, as CameraX does. No tuning of our own.
 */
object Bitrate {
    private val qualities = listOf(
        CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
        CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_CIF, CamcorderProfile.QUALITY_QVGA,
    )

    private data class Profile(val codec: Int, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    fun forStream(context: Context, codec: Codec, width: Int, height: Int, fps: Int): Int {
        val pixels = width.toDouble() * height
        val profiles = profiles(context)
        val nearest = profiles.minByOrNull { abs(ln(it.width.toDouble() * it.height / pixels)) }
            ?: return fallback(width, height, fps)
        // Prefer a profile in the chosen codec at that size; phones often only list H.264.
        val base = profiles.firstOrNull {
            it.codec == codec.encoder && it.width == nearest.width && it.height == nearest.height
        } ?: nearest
        val bps = base.bitrate * (pixels * fps / (base.width.toDouble() * base.height * base.fps))
        return codec.capabilities(width, height)?.bitrateRange?.clamp(bps.toInt()) ?: bps.toInt()
    }

    private fun profiles(context: Context): List<Profile> {
        // Camcorder profiles are keyed by the built-in cameras' numeric ids.
        val ids = context.getSystemService(CameraManager::class.java)?.cameraIdList.orEmpty().mapNotNull { it.toIntOrNull() }
        return ids.flatMap { id ->
            qualities.filter { CamcorderProfile.hasProfile(id, it) }.flatMap { quality ->
                if (Build.VERSION.SDK_INT >= 31) {
                    CamcorderProfile.getAll(id.toString(), quality)?.videoProfiles.orEmpty().filterNotNull()
                        .map { Profile(it.codec, it.width, it.height, it.frameRate, it.bitrate) }
                } else {
                    @Suppress("DEPRECATION")
                    val p = CamcorderProfile.get(id, quality)
                    listOf(Profile(p.videoCodec, p.videoFrameWidth, p.videoFrameHeight, p.videoFrameRate, p.videoBitRate))
                }
            }
        }
    }

    /** Only for phones without any camcorder profile: a common 0.2 bits per pixel. */
    private fun fallback(width: Int, height: Int, fps: Int) = (width * height * fps * 0.2).toInt()
}

/** Records the frames drawn onto [surface] to an MP4 in Movies/[FOLDER] via MediaStore. */
class Recorder private constructor(
    private val context: Context,
    private val recorder: MediaRecorder,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    val name: String,
) {
    val surface: Surface = recorder.surface

    /** Finalizes the file. Returns false (and deletes it) if nothing was recorded. */
    fun stop(): Boolean {
        val ok = try {
            recorder.stop()
            true
        } catch (_: RuntimeException) {
            false // stop() right after start(), before any frame was encoded
        }
        recorder.release()
        pfd.close()
        val resolver = context.contentResolver
        if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(uri, null, null)
        }
        return ok
    }

    companion object {
        const val FOLDER = "USB Camera Recorder"

        /** [onError] runs on a MediaRecorder thread, e.g. when storage runs full. */
        fun start(
            context: Context, width: Int, height: Int, fps: Int, codec: Codec, onError: () -> Unit,
        ): Recorder {
            if (codec.capabilities(width, height) == null) {
                throw IOException("This phone has no ${codec.label} encoder for ${width}x$height")
            }
            val name = "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.mp4"
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$FOLDER")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                },
            ) ?: error("MediaStore refused to create $name")
            val pfd = resolver.openFileDescriptor(uri, "w") ?: error("Cannot open $name")

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            try {
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(pfd.fileDescriptor)
                    setVideoEncoder(codec.encoder)
                    // Encoders want even dimensions.
                    setVideoSize(width and 1.inv(), height and 1.inv())
                    setVideoFrameRate(fps)
                    setVideoEncodingBitRate(Bitrate.forStream(context, codec, width, height, fps))
                    setOnErrorListener { _, _, _ -> onError() }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                recorder.release()
                pfd.close()
                resolver.delete(uri, null, null)
                throw e
            }
            return Recorder(context, recorder, uri, pfd, name)
        }
    }
}
