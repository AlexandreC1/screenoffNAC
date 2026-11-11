package dev.screenoff.core

import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val w = 320
    private val h = 240
    private val rnd = Random(42)

    /** Flat grey scene with sensor noise, optionally with a bright block. */
    private fun frame(base: Int = 100, noise: Int = 3, blockX: Int = -1, blockSize: Int = 60): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = base + rnd.nextInt(noise * 2 + 1) - noise
            if (blockX >= 0 && x in blockX until blockX + blockSize && y in 90 until 90 + blockSize) v = 220
            buf.put(y * w + x, v.coerceIn(0, 255).toByte())
        }
        return buf
    }

    private fun warm(d: MotionDetector) = repeat(10) { assertFalse(d.analyze(frame(), w, h, w)) }

    @Test
    fun staticSceneWithNoiseNeverTriggers() {
        val d = MotionDetector(sensitivity = 100)
        repeat(100) { assertFalse(d.analyze(frame(), w, h, w)) }
    }

    @Test
    fun movingBlockTriggersWithinAFewFrames() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        var triggered = false
        for (i in 0 until 6) if (d.analyze(frame(blockX = 20 + i * 30), w, h, w)) triggered = true
        assertTrue(triggered)
    }

    @Test
    fun globalLightChangeDoesNotTrigger() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        repeat(10) { assertFalse(d.analyze(frame(base = 180), w, h, w)) }
    }

    @Test
    fun goesQuietAfterMotionEnds() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        for (i in 0 until 6) d.analyze(frame(blockX = 20 + i * 30), w, h, w)
        // The block has left the frame. Trailing background recovers, then it must stay quiet.
        var lastTrue = -1
        for (i in 0 until 80) if (d.analyze(frame(), w, h, w)) lastTrue = i
        assertTrue("still triggering at frame $lastTrue", lastTrue < 40)
    }

    @Test
    fun lowSensitivityIgnoresSmallObject() {
        val d = MotionDetector(sensitivity = 0)
        warm(d)
        for (i in 0 until 6) assertFalse(d.analyze(frame(blockX = 20 + i * 30, blockSize = 20), w, h, w))
    }

    /** Plain scene with one bright rectangle anywhere. */
    private fun rect(base: Int, x0: Int, y0: Int, bw: Int, bh: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = base + rnd.nextInt(7) - 3
            if (x in x0 until x0 + bw && y in y0 until y0 + bh) v = 230
            buf.put(y * w + x, v.coerceIn(0, 255).toByte())
        }
        return buf
    }

    @Test
    fun motionIsDetectedWhileExposureShifts() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        var triggered = false
        // The whole picture brightens as the camera adjusts, and something moves at the same time.
        for (i in 0 until 6) if (d.analyze(rect(100 + i * 12, 20 + i * 30, 60, 60, 80), w, h, w)) triggered = true
        assertTrue(triggered)
    }

    @Test
    fun largeNearbyObjectIsMotionNotLighting() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        var triggered = false
        // Covers about 65 percent of the picture, like someone right in front of the phone.
        for (i in 0 until 4) if (d.analyze(rect(100, 0, 0, 250, 200), w, h, w)) triggered = true
        assertTrue(triggered)
    }

    @Test
    fun personSizedObjectAcrossTheRoomTriggersAtDefaultSensitivity() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        var triggered = false
        // About 30 by 70 pixels in a 320 by 240 picture, moving sideways.
        for (i in 0 until 6) if (d.analyze(rect(90, 40 + i * 25, 100, 30, 70), w, h, w)) triggered = true
        assertTrue(triggered)
    }

    @Test
    fun reportsChangedCellsForTheMeter() {
        val d = MotionDetector(sensitivity = 50)
        warm(d)
        d.analyze(rect(100, 0, 0, 100, 100), w, h, w)
        assertTrue(d.lastChangedCells > 0)
        assertTrue(d.framesAnalyzed > 10)
    }

    @Test
    fun tooSmallFrameIsIgnored() {
        assertFalse(MotionDetector(50).analyze(ByteBuffer.allocate(16), 4, 4, 4))
    }
}
