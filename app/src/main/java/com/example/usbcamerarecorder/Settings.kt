package com.example.usbcamerarecorder

import android.content.Context
import androidx.core.content.edit

/** User preferences plus the last working stream setup per camera (by VID:PID). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var codec: Codec
        get() = enumOr(prefs.getString("codec", null), Codec.H264)
        set(v) = prefs.edit { putString("codec", v.name) }

    var autoRecord: Boolean
        get() = prefs.getBoolean("autoRecord", false)
        set(v) = prefs.edit { putBoolean("autoRecord", v) }

    /** A setup the user picked; used as-is instead of searching. */
    fun manualConfig(camera: String) = StreamConfig.decode(prefs.getString("manual:$camera", null))
    fun setManualConfig(camera: String, config: StreamConfig?) =
        prefs.edit { if (config == null) remove("manual:$camera") else putString("manual:$camera", config.encode()) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T) =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
