package dev.screenoff.core

import dev.screenoff.core.RecorderMachine.Effect
import dev.screenoff.core.RecorderMachine.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderMachineTest {

    private fun start(mode: RecMode, now: Long = 0) =
        Event.Start(mode, now, segmentLengthMs = 60_000, postRollMs = 10_000)

    @Test
    fun continuousStartStop() {
        val m = RecorderMachine()
        assertEquals(listOf(Effect.OpenCamera(RecMode.CONTINUOUS), Effect.StartSegment), m.on(start(RecMode.CONTINUOUS)))
        assertEquals(RecState.RECORDING, m.state)
        assertEquals(listOf(Effect.StopSegment), m.on(Event.Stop(5_000)))
        assertEquals(RecState.FINALIZING, m.state)
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish(null)), m.on(Event.SegmentFinalized(5_100)))
        assertEquals(RecState.IDLE, m.state)
        assertNull(m.mode)
    }

    @Test
    fun secondStartIsIgnoredWhileRunning() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        assertTrue(m.on(start(RecMode.MOTION)).isEmpty())
        assertEquals(RecMode.CONTINUOUS, m.mode)
    }

    @Test
    fun rotatesWhenSegmentLengthReached() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        assertTrue(m.on(Event.Tick(59_000)).isEmpty())
        assertEquals(listOf(Effect.StopSegment), m.on(Event.Tick(60_000)))
        assertTrue("no second stop while rotating", m.on(Event.Tick(61_000)).isEmpty())
        assertEquals(listOf(Effect.StartSegment), m.on(Event.SegmentFinalized(61_200)))
        assertEquals(RecState.RECORDING, m.state)
        assertTrue(m.on(Event.Tick(120_000)).isEmpty())
        assertEquals(listOf(Effect.StopSegment), m.on(Event.Tick(121_200)))
    }

    @Test
    fun stopDuringRotationDoesNotStartANewSegment() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        m.on(Event.Tick(60_000))
        assertTrue(m.on(Event.Stop(60_100)).isEmpty())
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish(null)), m.on(Event.SegmentFinalized(60_300)))
    }

    @Test
    fun motionModeArmsRecordsAndRearms() {
        val m = RecorderMachine()
        assertEquals(listOf(Effect.OpenCamera(RecMode.MOTION)), m.on(start(RecMode.MOTION)))
        assertEquals(RecState.ARMED, m.state)
        assertTrue(m.on(Event.Tick(30_000)).isEmpty())
        assertEquals(listOf(Effect.StartSegment), m.on(Event.Motion(31_000)))
        assertEquals(RecState.RECORDING, m.state)
        m.on(Event.Motion(35_000))
        assertTrue(m.on(Event.Tick(44_000)).isEmpty())
        assertEquals(listOf(Effect.StopSegment), m.on(Event.Tick(45_000)))
        assertTrue(m.on(Event.SegmentFinalized(45_200)).isEmpty())
        assertEquals(RecState.ARMED, m.state)
    }

    @Test
    fun motionDuringPostRollFinalizeStartsNextClip() {
        val m = RecorderMachine()
        m.on(start(RecMode.MOTION))
        m.on(Event.Motion(1_000))
        m.on(Event.Tick(11_000))
        m.on(Event.Motion(11_100))
        assertEquals(listOf(Effect.StartSegment), m.on(Event.SegmentFinalized(11_300)))
        assertEquals(RecState.RECORDING, m.state)
    }

    @Test
    fun motionIsIgnoredInContinuousMode() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        assertTrue(m.on(Event.Motion(1_000)).isEmpty())
    }

    @Test
    fun stopWhileArmedFinishesImmediately() {
        val m = RecorderMachine()
        m.on(start(RecMode.MOTION))
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish(null)), m.on(Event.Stop(1_000)))
    }

    @Test
    fun failWhileRecordingSavesTheClipThenReportsTheError() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        assertEquals(listOf(Effect.StopSegment), m.on(Event.Fail(2_000, "Battery low")))
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish("Battery low")), m.on(Event.SegmentFinalized(2_200)))
    }

    @Test
    fun firstErrorWins() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        m.on(Event.Fail(2_000, "first"))
        m.on(Event.Fail(2_050, "second"))
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish("first")), m.on(Event.SegmentFinalized(2_200)))
    }

    @Test
    fun unexpectedFinalizeEndsWithError() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        val fx = m.on(Event.SegmentFinalized(3_000, failed = true, reason = "camera lost"))
        assertEquals(listOf(Effect.CloseCamera, Effect.Finish("camera lost")), fx)
        assertEquals(RecState.IDLE, m.state)
    }

    @Test
    fun finalizeTimeoutForcesIdle() {
        val m = RecorderMachine()
        m.on(start(RecMode.CONTINUOUS))
        m.on(Event.Stop(1_000))
        assertTrue(m.on(Event.Tick(10_000)).isEmpty())
        val fx = m.on(Event.Tick(1_000 + RecorderMachine.FINALIZE_TIMEOUT_MS))
        assertEquals(2, fx.size)
        assertEquals(RecState.IDLE, m.state)
    }

    @Test
    fun errorIsClearedOnNextStart() {
        val m = RecorderMachine()
        m.on(start(RecMode.MOTION))
        m.on(Event.Fail(1, "x"))
        m.on(start(RecMode.MOTION, 10))
        assertNull(m.error)
    }

    @Test
    fun eventsWhileIdleDoNothing() {
        val m = RecorderMachine()
        assertTrue(m.on(Event.Stop(1)).isEmpty())
        assertTrue(m.on(Event.Tick(1)).isEmpty())
        assertTrue(m.on(Event.Motion(1)).isEmpty())
        assertTrue(m.on(Event.Fail(1, "x")).isEmpty())
        assertTrue(m.on(Event.SegmentFinalized(1)).isEmpty())
    }
}
