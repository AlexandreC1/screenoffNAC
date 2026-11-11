package dev.screenoff.core

enum class RecMode { CONTINUOUS, MOTION }

enum class RecState { IDLE, ARMED, RECORDING, FINALIZING }

enum class CameraFacing { BACK, FRONT }

/** Bitrates are set explicitly on the encoder so storage use is predictable. */
enum class VideoQuality(val bitrateBps: Int) {
    SD(2_000_000),
    HD(5_000_000),
    FHD(10_000_000),
}

data class RecorderConfig(
    val mode: RecMode = RecMode.CONTINUOUS,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val quality: VideoQuality = VideoQuality.HD,
    val audioEnabled: Boolean = false,
    val segmentLengthSec: Int = 180,
    val capBytes: Long = 4L shl 30,
    val freeSpaceFloorBytes: Long = 500L shl 20,
    /** SAF tree URI of the folder the owner allocated. Null means the app's own movies folder. */
    val folderTreeUri: String? = null,
    val motionSensitivity: Int = 50,
    val postRollSec: Int = 10,
    val lowBatteryStopPercent: Int = 10,
) {
    fun sanitized(): RecorderConfig = copy(
        segmentLengthSec = segmentLengthSec.coerceIn(30, 1800),
        capBytes = capBytes.coerceAtLeast(100L shl 20),
        freeSpaceFloorBytes = freeSpaceFloorBytes.coerceAtLeast(100L shl 20),
        motionSensitivity = motionSensitivity.coerceIn(0, 100),
        postRollSec = postRollSec.coerceIn(3, 120),
        lowBatteryStopPercent = lowBatteryStopPercent.coerceIn(0, 50),
    )
}

/** A finalized, playable clip. Unfinalized files never enter the index. */
data class Segment(
    val id: String,
    val uri: String,
    val mode: RecMode,
    val startedAtMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val locked: Boolean = false,
)

data class RecorderStatus(
    val state: RecState = RecState.IDLE,
    val mode: RecMode? = null,
    /** Wall clock start of the segment being written, when RECORDING. */
    val segmentStartedAtMs: Long? = null,
    val lastError: String? = null,
)
