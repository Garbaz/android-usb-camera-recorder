package io.github.garbaz.usbcamerarecorder

import android.content.Context
import androidx.core.content.edit

/** User preferences plus the last working stream setup per camera (by VID:PID). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var codec: Codec
        get() = enumOr(prefs.getString("codec", null), Codec.H264)
        set(v) = prefs.edit { putString("codec", v.name) }

    /** A folder picked with the system folder picker; null means Movies/[Recorder.FOLDER]. */
    var saveTree: String?
        get() = prefs.getString("saveTree", null)
        set(v) = prefs.edit { if (v == null) remove("saveTree") else putString("saveTree", v) }

    /** The record button starts continuous recording instead of a single recording. */
    var continuousMode: Boolean
        get() = prefs.getBoolean("continuousMode", false)
        set(v) = prefs.edit { putBoolean("continuousMode", v) }

    /** Continuous recording starts a new file after this many seconds without picture. */
    var splitAfterSeconds: Int
        get() = prefs.getInt("splitAfterSeconds", 5)
        set(v) = prefs.edit { putInt("splitAfterSeconds", v) }

    /** A setup the user picked; used as-is instead of searching. */
    fun manualConfig(camera: String) = StreamConfig.decode(prefs.getString("manual:$camera", null))
    fun setManualConfig(camera: String, config: StreamConfig?) =
        prefs.edit { if (config == null) remove("manual:$camera") else putString("manual:$camera", config.encode()) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T) =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
