package io.github.garbaz.usbcamerarecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Runs while a recording does, or continuous recording waits for a picture: the notification
 * keeps the process alive without a visible activity, the wake lock keeps the CPU (and so USB
 * transfers and the encoder) running with the screen off. Managed by [CameraController].
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stopFromNotification()
            return START_NOT_STICKY
        }
        // The camera type covers capturing video in the background; it is started from the
        // visible activity, as Android requires for camera use.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbCameraRecorder:recording")
                .apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        /** Refreshes the notification when recording starts or stops within a continuous session. */
        fun update(context: Context) =
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context))

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
            )
            val controller = context.controller
            val recording = controller.recording
            val state = when {
                recording != null -> R.string.notification_recording
                else -> R.string.notification_waiting
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 0, Intent(context, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle(controller.camera?.name)
                .setContentText(context.getString(state))
                .setSubText(if (controller.continuous) context.getString(R.string.continuous) else null)
                .apply {
                    if (recording != null) {
                        setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - recording.startedAt))
                        setUsesChronometer(true)
                        setShowWhen(true)
                    } else {
                        setShowWhen(false)
                    }
                }
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.stop), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
