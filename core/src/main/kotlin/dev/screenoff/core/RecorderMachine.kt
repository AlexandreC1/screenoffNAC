package dev.screenoff.core

/**
 * The recorder state machine. Pure: it takes events and returns effects for
 * the service to execute. All time values are monotonic milliseconds.
 * Call from one thread only.
 */
class RecorderMachine {

    sealed interface Event {
        data class Start(val mode: RecMode, val nowMs: Long, val segmentLengthMs: Long, val postRollMs: Long) : Event
        data class Stop(val nowMs: Long) : Event
        data class Motion(val nowMs: Long) : Event
        data class Tick(val nowMs: Long) : Event
        /** The current segment file is closed. [failed] means the recorder stopped on its own with an error. */
        data class SegmentFinalized(val nowMs: Long, val failed: Boolean = false, val reason: String? = null) : Event
        data class Fail(val nowMs: Long, val reason: String) : Event
    }

    sealed interface Effect {
        data class OpenCamera(val mode: RecMode) : Effect
        data object StartSegment : Effect
        data object StopSegment : Effect
        data object CloseCamera : Effect
        /** Release everything and stop the service. [error] is shown to the owner when not null. */
        data class Finish(val error: String?) : Effect
    }

    private enum class Pending { ROTATE, QUIET, STOP }

    var state: RecState = RecState.IDLE
        private set
    var mode: RecMode? = null
        private set
    var error: String? = null
        private set

    private var segmentLengthMs = 180_000L
    private var postRollMs = 10_000L
    private var segmentStartMs = 0L
    private var lastMotionMs = 0L
    private var pending: Pending? = null
    private var motionWhilePending = false
    private var finalizingSinceMs = 0L

    fun on(event: Event): List<Effect> = when (event) {
        is Event.Start -> onStart(event)
        is Event.Stop -> onStop(event.nowMs)
        is Event.Motion -> onMotion(event.nowMs)
        is Event.Tick -> onTick(event.nowMs)
        is Event.SegmentFinalized -> onFinalized(event)
        is Event.Fail -> onFail(event)
    }

    private fun onStart(e: Event.Start): List<Effect> {
        if (state != RecState.IDLE) return emptyList()
        mode = e.mode
        error = null
        pending = null
        motionWhilePending = false
        segmentLengthMs = e.segmentLengthMs
        postRollMs = e.postRollMs
        return when (e.mode) {
            RecMode.CONTINUOUS -> {
                state = RecState.RECORDING
                segmentStartMs = e.nowMs
                listOf(Effect.OpenCamera(e.mode), Effect.StartSegment)
            }
            RecMode.MOTION -> {
                state = RecState.ARMED
                listOf(Effect.OpenCamera(e.mode))
            }
        }
    }

    private fun onStop(nowMs: Long): List<Effect> = when (state) {
        RecState.IDLE, RecState.FINALIZING -> emptyList()
        RecState.ARMED -> toIdle()
        RecState.RECORDING -> beginStop(nowMs)
    }

    private fun onMotion(nowMs: Long): List<Effect> {
        if (mode != RecMode.MOTION) return emptyList()
        return when (state) {
            RecState.ARMED -> {
                state = RecState.RECORDING
                segmentStartMs = nowMs
                lastMotionMs = nowMs
                listOf(Effect.StartSegment)
            }
            RecState.RECORDING -> {
                lastMotionMs = nowMs
                if (pending == Pending.QUIET) motionWhilePending = true
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun onTick(nowMs: Long): List<Effect> {
        if (state == RecState.FINALIZING && nowMs - finalizingSinceMs >= FINALIZE_TIMEOUT_MS) {
            if (error == null) error = "Recorder did not finish saving in time"
            return toIdle()
        }
        if (state != RecState.RECORDING || pending != null) return emptyList()
        if (mode == RecMode.MOTION && nowMs - lastMotionMs >= postRollMs) {
            pending = Pending.QUIET
            return listOf(Effect.StopSegment)
        }
        if (nowMs - segmentStartMs >= segmentLengthMs) {
            pending = Pending.ROTATE
            return listOf(Effect.StopSegment)
        }
        return emptyList()
    }

    private fun onFinalized(e: Event.SegmentFinalized): List<Effect> {
        if (state != RecState.RECORDING && state != RecState.FINALIZING) return emptyList()
        if (e.failed) {
            if (error == null) error = e.reason ?: "Recording stopped unexpectedly"
            return toIdle()
        }
        val p = pending
        pending = null
        return when (p) {
            Pending.STOP -> toIdle()
            Pending.ROTATE -> {
                segmentStartMs = e.nowMs
                listOf(Effect.StartSegment)
            }
            Pending.QUIET -> {
                if (motionWhilePending) {
                    motionWhilePending = false
                    segmentStartMs = e.nowMs
                    lastMotionMs = e.nowMs
                    listOf(Effect.StartSegment)
                } else {
                    state = RecState.ARMED
                    emptyList()
                }
            }
            null -> {
                // The recorder closed the file without being asked to.
                if (error == null) error = e.reason ?: "Recording stopped unexpectedly"
                toIdle()
            }
        }
    }

    private fun onFail(e: Event.Fail): List<Effect> {
        if (state == RecState.IDLE) return emptyList()
        if (error == null) error = e.reason
        return when (state) {
            RecState.ARMED -> toIdle()
            RecState.RECORDING -> beginStop(e.nowMs)
            else -> emptyList()
        }
    }

    private fun beginStop(nowMs: Long): List<Effect> {
        val alreadyStopping = pending != null
        pending = Pending.STOP
        state = RecState.FINALIZING
        finalizingSinceMs = nowMs
        return if (alreadyStopping) emptyList() else listOf(Effect.StopSegment)
    }

    private fun toIdle(): List<Effect> {
        state = RecState.IDLE
        mode = null
        pending = null
        motionWhilePending = false
        return listOf(Effect.CloseCamera, Effect.Finish(error))
    }

    companion object {
        const val FINALIZE_TIMEOUT_MS = 15_000L
    }
}
