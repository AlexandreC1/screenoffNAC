package dev.screenoff.recorder.triggers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.screenoff.core.RecState
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.R
import dev.screenoff.recorder.RecorderBus

/** Home screen widget: one button that toggles recording through [TriggerActivity]. */
class RecorderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val state = RecorderBus.status.value.state
            val label = context.getString(
                when (state) {
                    RecState.IDLE -> R.string.widget_start
                    RecState.ARMED -> R.string.widget_armed
                    RecState.RECORDING -> R.string.widget_recording
                    RecState.FINALIZING -> R.string.state_saving
                }
            )
            val toggle = PendingIntent.getActivity(
                context, 30,
                Intent(context, TriggerActivity::class.java).setAction(Actions.TOGGLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_recorder).apply {
                setTextViewText(R.id.widget_label, label)
                setInt(
                    R.id.widget_root, "setBackgroundResource",
                    if (state == RecState.IDLE) R.drawable.widget_bg_idle else R.drawable.widget_bg_active,
                )
                setOnClickPendingIntent(R.id.widget_root, toggle)
            }
            for (id in ids) manager.updateAppWidget(id, views)
        }
    }
}
