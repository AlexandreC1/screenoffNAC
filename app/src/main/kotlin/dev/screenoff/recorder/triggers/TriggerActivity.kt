package dev.screenoff.recorder.triggers

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import dev.screenoff.core.RecMode
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.Diag
import dev.screenoff.recorder.Graph
import dev.screenoff.recorder.R
import dev.screenoff.recorder.RecorderBus
import dev.screenoff.recorder.ui.MainActivity

/**
 * Invisible trampoline used by the tile, the widget and the launcher shortcuts.
 *
 * Android only lets an app create a camera foreground service while one of
 * its activities is visible. This activity becomes visible for an instant,
 * starts or stops the recorder in onResume, and closes itself.
 */
class TriggerActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        dispatch()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun dispatch() {
        val running = RecorderBus.isRunning
        Diag.log("trigger ${intent?.action} running=$running")
        val config = Graph.config.config.value
        when (intent?.action) {
            Actions.STOP -> Triggers.stop(this)
            Actions.START_CONTINUOUS -> if (!running) start(RecMode.CONTINUOUS)
            Actions.START_MOTION -> if (!running) start(RecMode.MOTION)
            else -> if (running) Triggers.stop(this) else start(config.mode)
        }
    }

    private fun start(mode: RecMode) {
        if (!Permissions.ready(this, Graph.config.config.value)) {
            Diag.log("trigger refused, missing ${Permissions.missing(this, Graph.config.config.value)}")
            // Permissions can only be requested from the real UI.
            Toast.makeText(this, R.string.toast_permissions, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Triggers.start(this, mode)
    }
}
