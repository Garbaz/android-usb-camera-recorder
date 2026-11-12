package io.github.garbaz.usbcamerarecorder

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
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

/** AAC audio parameters. */
data class AudioEncoding(val bitrate: Int, val sampleRate: Int, val channels: Int)

/**
 * Encoding as the phone records its own videos (its camcorder profiles). The video bitrate is the
 * one at a similar resolution, scaled to the stream by pixel rate, as CameraX does. No tuning of our own.
 */
object Profiles {
    private val qualities = listOf(
        CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
        CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_CIF, CamcorderProfile.QUALITY_QVGA,
    )

    private data class Profile(val codec: Int, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    fun videoBitrate(context: Context, codec: Codec, width: Int, height: Int, fps: Int): Int {
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
        return cameraIds(context).flatMap { id ->
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

    /** The audio parameters of the phone's best camcorder profile, encoded as AAC (what MP4 files carry). */
    fun audio(context: Context): AudioEncoding = cameraIds(context).firstNotNullOfOrNull { id ->
        qualities.filter { CamcorderProfile.hasProfile(id, it) }.firstNotNullOfOrNull { quality ->
            if (Build.VERSION.SDK_INT >= 31) {
                CamcorderProfile.getAll(id.toString(), quality)?.audioProfiles?.firstOrNull()
                    ?.let { AudioEncoding(it.bitrate, it.sampleRate, it.channels.coerceIn(1, 2)) }
            } else {
                @Suppress("DEPRECATION")
                val p = CamcorderProfile.get(id, quality)
                AudioEncoding(p.audioBitRate, p.audioSampleRate, p.audioChannels.coerceIn(1, 2))
            }
        }
    } ?: FALLBACK_AUDIO

    /** Only for phones without any camcorder profile: common AAC settings. */
    private val FALLBACK_AUDIO = AudioEncoding(128_000, 48_000, 2)

    // Camcorder profiles are keyed by the built-in cameras' numeric ids.
    private fun cameraIds(context: Context) =
        context.getSystemService(CameraManager::class.java)?.cameraIdList.orEmpty().mapNotNull { it.toIntOrNull() }

    /** Only for phones without any camcorder profile: a common 0.2 bits per pixel. */
    private fun fallback(width: Int, height: Int, fps: Int) = (width * height * fps * 0.2).toInt()
}

/**
 * Where recordings go: by default Movies/[Recorder.FOLDER] via MediaStore, or a folder the user
 * picked with the system folder picker (a document tree URI).
 */
class SaveLocation(private val context: Context, val tree: Uri?) {
    val label: String
        get() = tree?.let(::treePath) ?: "Movies/${Recorder.FOLDER}"

    /** Creates [name] and returns its URI plus how to finish it: publish, or delete if empty. */
    fun create(name: String): Pair<Uri, (Boolean) -> Unit> {
        val resolver = context.contentResolver
        if (tree == null) {
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/${Recorder.FOLDER}")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                },
            ) ?: throw IOException("MediaStore refused to create $name")
            return uri to { ok ->
                if (ok) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else resolver.delete(uri, null, null)
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, "video/mp4", name)
            ?: throw IOException("Cannot create $name in $label")
        return uri to { ok -> if (!ok) DocumentsContract.deleteDocument(resolver, uri) }
    }

    /**
     * Device storage document IDs are "volume:path" ("primary:Movies/FPV"), so the path can be shown
     * relative to internal storage, or prefixed with the SD card's volume ID. Other providers get the
     * folder's display name.
     */
    private fun treePath(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == "com.android.externalstorage.documents" && ':' in id) {
            val (volume, path) = id.split(':', limit = 2)
            return if (volume == "primary") path.ifEmpty { "Internal storage" } else "$volume/$path".trimEnd('/')
        }
        return treeName(tree)
    }

    private fun treeName(tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: tree.lastPathSegment.orEmpty()
}

/**
 * Records the frames drawn onto [surface] to an MP4 at a [SaveLocation], with the sound of a
 * microphone if given. The file starts with the first frame.
 */
class Recorder private constructor(
    val name: String,
    val surface: Surface,
    private val video: MediaCodec,
    private val videoThread: Thread,
    private val audio: AudioCapture?,
    private val mp4: Mp4,
    private val pfd: ParcelFileDescriptor,
    private val finish: (Boolean) -> Unit,
) {
    /** Finalizes the file. Returns false (and deletes it) if nothing was recorded. */
    fun stop(): Boolean {
        audio?.stop()
        try {
            video.signalEndOfInputStream()
        } catch (_: IllegalStateException) {
            // The encoder failed already.
        }
        videoThread.join(STOP_TIMEOUT_MS)
        val ok = mp4.finish()
        video.release()
        surface.release()
        pfd.close()
        finish(ok)
        return ok
    }

    companion object {
        const val FOLDER = "USB Camera Recorder"

        /**
         * Records [microphone] too, if given; [onAudioLost] runs when that is not or no longer possible,
         * and the video continues without it. [onError] runs on an encoder thread, e.g. when storage runs full.
         */
        fun start(
            context: Context,
            location: SaveLocation,
            width: Int,
            height: Int,
            fps: Int,
            codec: Codec,
            microphone: AudioDeviceInfo?,
            onAudioLost: () -> Unit,
            onError: () -> Unit,
        ): Recorder {
            if (codec.capabilities(width, height) == null) {
                throw IOException("This phone has no ${codec.label} encoder for ${width}x$height")
            }
            val name = "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.mp4"
            val (uri, finish) = location.create(name)
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $name")
            } catch (e: Exception) {
                finish(false)
                throw e
            }
            // Encoders want even dimensions. A key frame every second, as CameraX records.
            val format = MediaFormat.createVideoFormat(codec.mime, width and 1.inv(), height and 1.inv()).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, Profiles.videoBitrate(context, codec, width, height, fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            var video: MediaCodec? = null
            var mp4: Mp4? = null
            try {
                video = MediaCodec.createEncoderByType(codec.mime)
                video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = video.createInputSurface()
                mp4 = Mp4(pfd.fileDescriptor, withAudio = microphone != null)
                video.start()
                val audio = microphone?.let { AudioCapture.start(it, Profiles.audio(context), mp4, onAudioLost) }
                val encoder = video
                val muxer = mp4
                val videoThread = thread(name = "Video encoder") {
                    try {
                        while (!drain(encoder, 100_000, muxer::addVideo, muxer::writeVideo)) Unit
                    } catch (e: Exception) {
                        Log.w(TAG, "video encoder failed", e)
                        onError()
                    }
                }
                return Recorder(name, surface, video, videoThread, audio, mp4, pfd, finish)
            } catch (e: Exception) {
                video?.release()
                mp4?.finish()
                pfd.close()
                finish(false)
                throw e
            }
        }
    }
}

private const val TAG = "Recorder"
private const val STOP_TIMEOUT_MS = 3000L

/** Moves an encoder's output on until none is ready within [timeoutUs]; returns true at the end of the stream. */
private fun drain(
    codec: MediaCodec,
    timeoutUs: Long,
    onFormat: (MediaFormat) -> Unit,
    write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
): Boolean {
    val info = MediaCodec.BufferInfo()
    while (true) {
        val index = codec.dequeueOutputBuffer(info, timeoutUs)
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
            index >= 0 -> {
                val buffer = codec.getOutputBuffer(index)
                // Codec config data is part of the format.
                if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    write(buffer, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
    }
}

/**
 * The MP4 file, written from the video and the audio thread. A muxer needs every track before it
 * starts, so video waits for the audio format, which follows moments after the start, at most
 * [AUDIO_WAIT_US]. Times are relative to the first frame; earlier audio is left out.
 */
private class Mp4(fd: FileDescriptor, private var withAudio: Boolean) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var startUs = -1L
    private var frames = 0
    private val waiting = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

    @Synchronized
    fun addVideo(format: MediaFormat) {
        videoTrack = muxer.addTrack(format)
        startIfReady()
    }

    /** False if the file already started without audio. */
    @Synchronized
    fun addAudio(format: MediaFormat): Boolean {
        if (started) return false
        audioTrack = muxer.addTrack(format)
        startIfReady()
        return true
    }

    @Synchronized
    fun withoutAudio() {
        withAudio = false
        startIfReady()
    }

    @Synchronized
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (startUs < 0) startUs = info.presentationTimeUs
        if (started) return write(videoTrack, buffer, info)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val copy = ByteBuffer.allocate(info.size).put(buffer)
        copy.flip()
        waiting += copy to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        if (info.presentationTimeUs - startUs > AUDIO_WAIT_US) withoutAudio()
    }

    @Synchronized
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started && audioTrack >= 0 && info.presentationTimeUs >= startUs) write(audioTrack, buffer, info)
    }

    /** Returns whether the file has any video; releases the muxer. */
    @Synchronized
    fun finish(): Boolean {
        withAudio = false
        startIfReady()
        val ok = frames > 0
        if (ok) muxer.stop()
        muxer.release()
        return ok
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val relative = MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs - startUs, info.flags) }
        muxer.writeSampleData(track, buffer, relative)
        if (track == videoTrack) frames++
    }

    private fun startIfReady() {
        if (started || videoTrack < 0 || (withAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        waiting.forEach { (buffer, info) -> write(videoTrack, buffer, info) }
        waiting.clear()
    }

    companion object {
        const val AUDIO_WAIT_US = 1_000_000L
    }
}

/**
 * Records a microphone into the audio track of an [Mp4] as AAC. Android treats the chosen
 * microphone only as a preference and switches to another, like the phone's own, when it goes
 * away. So each block is checked to come from the microphone, and is encoded only once the block
 * after it passed the check too, so a switch is caught before anything from elsewhere is
 * encoded. At the first block from elsewhere, audio ends for this file.
 */
private class AudioCapture private constructor(
    private val record: AudioRecord,
    private val encoder: MediaCodec,
    private val microphone: AudioDeviceInfo,
    private val sampleRate: Int,
    private val frameBytes: Int,
    private val mp4: Mp4,
    private val onLost: () -> Unit,
) {
    @Volatile private var stopping = false
    @Volatile private var rerouted = false
    private val routing = AudioRouting.OnRoutingChangedListener { router ->
        router.routedDevice?.let { if (it.id != microphone.id) rerouted = true }
    }
    private val worker: Thread

    init {
        record.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        worker = thread(name = "Audio encoder") { run() }
    }

    fun stop() {
        stopping = true
        worker.join(STOP_TIMEOUT_MS)
    }

    /** Null while Android has not routed the recording yet. */
    private fun fromMicrophone(): Boolean? = if (rerouted) false else record.routedDevice?.let { it.id == microphone.id }

    private fun run() {
        var trackAdded = false
        var rejected = false // the file started without audio, its format came too late
        var lost = false
        val onFormat = { format: MediaFormat -> if (mp4.addAudio(format)) trackAdded = true else rejected = true }
        try {
            record.startRecording()
            val block = ByteArray(BLOCK_FRAMES * frameBytes)
            var held: ByteArray? = null
            var heldUs = 0L
            var frames = 0L
            var lastUs = -1L
            val timestamp = AudioTimestamp()
            while (!stopping) {
                val read = record.read(block, 0, block.size)
                if (read <= 0) {
                    lost = true // the microphone is gone
                    break
                }
                val count = read / frameBytes
                // When the block's first frame was captured; kept increasing for the muxer.
                val us = if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    (timestamp.nanoTime + (frames - timestamp.framePosition) * 1_000_000_000L / sampleRate) / 1000
                } else {
                    System.nanoTime() / 1000 - count * 1_000_000L / sampleRate
                }.coerceAtLeast(lastUs + 1)
                lastUs = us
                frames += count
                when (fromMicrophone()) {
                    null -> held = null
                    false -> {
                        lost = true
                        break
                    }
                    true -> {
                        held?.let { encode(it, heldUs, onFormat) }
                        held = block.copyOf(read)
                        heldUs = us
                    }
                }
                if (rejected) break
            }
            record.stop()
            if (!lost && !rejected && fromMicrophone() == true) held?.let { encode(it, heldUs, onFormat) }
            val input = encoder.dequeueInputBuffer(100_000)
            if (input >= 0) encoder.queueInputBuffer(input, 0, 0, lastUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            while (!drain(encoder, 10_000, onFormat, mp4::writeAudio) && SystemClock.elapsedRealtime() < deadline) Unit
        } catch (e: Exception) {
            Log.w(TAG, "audio failed", e)
            lost = true
        } finally {
            record.removeOnRoutingChangedListener(routing)
            record.release()
            encoder.release()
            if (!trackAdded) mp4.withoutAudio()
        }
        if ((lost || rejected) && !stopping) onLost()
    }

    private fun encode(pcm: ByteArray, us: Long, onFormat: (MediaFormat) -> Unit) {
        val input = encoder.dequeueInputBuffer(100_000)
        if (input >= 0) {
            encoder.getInputBuffer(input)?.put(pcm)
            encoder.queueInputBuffer(input, 0, pcm.size, us, 0)
        }
        drain(encoder, 0, onFormat, mp4::writeAudio)
    }

    companion object {
        /** One AAC frame. */
        private const val BLOCK_FRAMES = 1024

        /** Null if the microphone cannot be recorded; the [Mp4] then goes on without audio. */
        @SuppressLint("MissingPermission") // the caller only passes a microphone with the permission granted
        fun start(microphone: AudioDeviceInfo, settings: AudioEncoding, mp4: Mp4, onLost: () -> Unit): AudioCapture? {
            var record: AudioRecord? = null
            var encoder: MediaCodec? = null
            return try {
                val mask = if (settings.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val frameBytes = 2 * settings.channels
                val minimum = AudioRecord.getMinBufferSize(settings.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                // The source meant for recording video; the preferred device routes it to the microphone.
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(settings.sampleRate).setChannelMask(mask).build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimum, BLOCK_FRAMES * frameBytes) * 4)
                    .build()
                if (!record.setPreferredDevice(microphone)) throw IOException("Cannot select ${microphone.productName}")
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(
                        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, settings.sampleRate, settings.channels).apply {
                            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
                            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        },
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    start()
                }
                AudioCapture(record, encoder, microphone, settings.sampleRate, frameBytes, mp4, onLost)
            } catch (e: Exception) {
                Log.w(TAG, "cannot record audio", e)
                record?.release()
                encoder?.release()
                mp4.withoutAudio()
                onLost()
                null
            }
        }
    }
}
