package dev.screenoff.recorder.data

import android.content.Context
import dev.screenoff.core.CameraFacing
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecorderConfig
import dev.screenoff.core.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings, kept in SharedPreferences. Source of truth for the service and the UI. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(migrateThenRead())
    val config: StateFlow<RecorderConfig> = _config

    @Synchronized
    fun update(transform: (RecorderConfig) -> RecorderConfig) {
        val next = transform(_config.value).sanitized()
        prefs.edit()
            .putString(K_MODE, next.mode.name)
            .putString(K_FACING, next.cameraFacing.name)
            .putString(K_QUALITY, next.quality.name)
            .putBoolean(K_AUDIO, next.audioEnabled)
            .putInt(K_SEGMENT, next.segmentLengthSec)
            .putLong(K_CAP, next.capBytes)
            .putLong(K_FLOOR, next.freeSpaceFloorBytes)
            .putString(K_FOLDER, next.folderTreeUri)
            .putInt(K_SENSITIVITY, next.motionSensitivity)
            .putInt(K_POSTROLL, next.postRollSec)
            .putInt(K_BATTERY, next.lowBatteryStopPercent)
            .apply()
        _config.value = next
    }

    /** v2: sound is the point of the app, so audio is turned on for existing installs too. */
    private fun migrateThenRead(): RecorderConfig {
        if (prefs.getInt(K_VERSION, 1) < 2) {
            prefs.edit().putBoolean(K_AUDIO, true).putInt(K_VERSION, 2).apply()
        }
        return read()
    }

    private fun read(): RecorderConfig {
        val d = RecorderConfig()
        return RecorderConfig(
            mode = enumOr(prefs.getString(K_MODE, null), d.mode),
            cameraFacing = enumOr(prefs.getString(K_FACING, null), d.cameraFacing),
            quality = enumOr(prefs.getString(K_QUALITY, null), d.quality),
            audioEnabled = prefs.getBoolean(K_AUDIO, d.audioEnabled),
            segmentLengthSec = prefs.getInt(K_SEGMENT, d.segmentLengthSec),
            capBytes = prefs.getLong(K_CAP, d.capBytes),
            freeSpaceFloorBytes = prefs.getLong(K_FLOOR, d.freeSpaceFloorBytes),
            folderTreeUri = prefs.getString(K_FOLDER, null),
            motionSensitivity = prefs.getInt(K_SENSITIVITY, d.motionSensitivity),
            postRollSec = prefs.getInt(K_POSTROLL, d.postRollSec),
            lowBatteryStopPercent = prefs.getInt(K_BATTERY, d.lowBatteryStopPercent),
        ).sanitized()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val K_MODE = "mode"
        const val K_FACING = "cameraFacing"
        const val K_QUALITY = "quality"
        const val K_AUDIO = "audioEnabled"
        const val K_SEGMENT = "segmentLengthSec"
        const val K_CAP = "capBytes"
        const val K_FLOOR = "freeSpaceFloorBytes"
        const val K_FOLDER = "folderTreeUri"
        const val K_SENSITIVITY = "motionSensitivity"
        const val K_POSTROLL = "postRollSec"
        const val K_BATTERY = "lowBatteryStopPercent"
        const val K_VERSION = "configVersion"
    }
}
