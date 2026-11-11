package dev.screenoff.recorder.triggers

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.screenoff.core.RecState
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.R
import dev.screenoff.recorder.RecorderBus

/** Quick Settings tile: tap to start with the configured mode, tap again to stop. */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderBus.isRunning) {
            Triggers.stop(this)
            return
        }
        val intent = Intent(this, TriggerActivity::class.java)
            .setAction(Actions.TOGGLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = RecorderBus.status.value.state
        tile.state = if (state == RecState.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(
                when (state) {
                    RecState.IDLE -> R.string.state_idle
                    RecState.ARMED -> R.string.state_armed
                    RecState.RECORDING -> R.string.state_recording
                    RecState.FINALIZING -> R.string.state_saving
                }
            )
        }
        tile.updateTile()
    }
}
