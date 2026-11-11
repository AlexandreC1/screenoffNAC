package dev.screenoff.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecState
import dev.screenoff.core.RecorderStatus
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.R
import dev.screenoff.recorder.ui.MainActivity

/**
 * Notifications. The ongoing one is always shown while the camera is in use
 * and is never hidden or disguised.
 */
object Notifier {
    const val ONGOING_ID = 1
    private const val ERROR_ID = 2
    private const val CH_RECORDING = "recording"
    private const val CH_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun ongoing(context: Context, status: RecorderStatus, currentLocked: Boolean): Notification {
        val title = when (status.state) {
            RecState.RECORDING ->
                if (status.mode == RecMode.MOTION) context.getString(R.string.notif_recording_motion)
                else context.getString(R.string.notif_recording)
            RecState.ARMED -> context.getString(R.string.notif_armed)
            RecState.FINALIZING -> context.getString(R.string.notif_saving)
            RecState.IDLE -> context.getString(R.string.notif_starting)
        }
        val builder = NotificationCompat.Builder(context, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_stop), servicePending(context, Actions.STOP, 10))
        if (status.state == RecState.RECORDING) {
            if (currentLocked) builder.setContentText(context.getString(R.string.notif_clip_kept))
            else builder.addAction(0, context.getString(R.string.action_keep), servicePending(context, Actions.LOCK_CURRENT, 11))
        }
        return builder.build()
    }

    fun updateOngoing(context: Context, status: RecorderStatus, currentLocked: Boolean) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ONGOING_ID, ongoing(context, status, currentLocked))
    }

    /** Tells the owner why recording stopped or could not start. */
    fun error(context: Context, message: String) {
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(context.getString(R.string.notif_stopped))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ERROR_ID, n) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun servicePending(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode, Intent(context, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
