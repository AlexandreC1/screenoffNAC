package dev.screenoff.recorder.triggers

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecorderConfig
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.Graph
import dev.screenoff.recorder.RecorderBus
import dev.screenoff.recorder.service.Notifier
import dev.screenoff.recorder.service.RecorderService

object Permissions {
    /** Runtime permissions recording needs with the given settings. */
    fun required(config: RecorderConfig): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (config.audioEnabled) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missing(context: Context, config: RecorderConfig): List<String> =
        required(config).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun ready(context: Context, config: RecorderConfig): Boolean = missing(context, config).isEmpty()
}

/**
 * The only way to start or stop the recorder.
 * [start] must be called while an activity of this app is visible, because
 * Android refuses to create a camera foreground service from the background.
 */
object Triggers {

    /** @return false if recording could not be started */
    fun start(context: Context, mode: RecMode = Graph.config.config.value.mode): Boolean {
        if (RecorderBus.isRunning) return true
        if (!Permissions.ready(context, Graph.config.config.value)) return false
        return try {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(Actions.SERVICE_START)
                .putExtra(Actions.EXTRA_MODE, mode.name)
            ContextCompat.startForegroundService(context, intent)
            Log.i("ScreenOff", "start requested, mode=$mode")
            true
        } catch (e: Exception) {
            Log.e("ScreenOff", "start failed", e)
            Notifier.error(context, "Could not start recording: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun stop(context: Context) {
        if (!RecorderBus.isRunning) return
        sendToRunningService(context, Actions.STOP)
    }

    /** Protects the clip being recorded from the loop. */
    fun lockCurrent(context: Context) {
        if (!RecorderBus.isRunning) return
        sendToRunningService(context, Actions.LOCK_CURRENT)
    }

    private fun sendToRunningService(context: Context, action: String) {
        try {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        } catch (e: Exception) {
            // The service died between the check and the call. Nothing to stop.
        }
    }

    /** Pushes the current state to the tile and the widget. */
    fun refreshSurfaces(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, RecorderTileService::class.java))
        }
        runCatching { RecorderWidget.updateAll(context) }
    }
}
