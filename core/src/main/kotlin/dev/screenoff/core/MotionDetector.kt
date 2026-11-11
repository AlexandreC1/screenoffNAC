package dev.screenoff.core

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frame-difference motion detector over a coarse luma grid.
 * No Android types, so it runs in plain JVM tests.
 * Not thread safe: call from one analysis thread.
 */
class MotionDetector(
    sensitivity: Int,
    private val gridW: Int = 32,
    private val gridH: Int = 24,
) {
    private val cells = gridW * gridH
    private val s = sensitivity.coerceIn(0, 100) / 100f

    /** Per-cell luma difference (0..255) that counts as a change. */
    val cellThreshold: Float = lerp(40f, 8f, s)

    /** Number of changed cells needed to call it motion. */
    val minChangedCells: Int = (lerp(0.04f, 0.004f, s) * cells).roundToInt().coerceAtLeast(1)

    private val background = FloatArray(cells)
    private val current = FloatArray(cells)
    private var frames = 0
    private var consecutive = 0

    fun reset() {
        frames = 0
        consecutive = 0
    }

    /**
     * @param luma the Y plane, read with absolute gets, position is not changed
     * @return true while motion is being observed
     */
    fun analyze(luma: ByteBuffer, width: Int, height: Int, rowStride: Int): Boolean {
        if (width < gridW || height < gridH) return false
        fillGrid(luma, width, height, rowStride)
        frames++
        if (frames == 1) {
            current.copyInto(background)
            return false
        }

        var changed = 0
        for (i in 0 until cells) {
            if (abs(current[i] - background[i]) > cellThreshold) changed++
        }

        // A change across most of the frame is exposure or lighting, not an intruder.
        if (changed > cells * GLOBAL_CHANGE_FRACTION) {
            current.copyInto(background)
            consecutive = 0
            return false
        }

        for (i in 0 until cells) {
            val diff = current[i] - background[i]
            val alpha = if (abs(diff) > cellThreshold) ALPHA_CHANGED else ALPHA_STILL
            background[i] += alpha * diff
        }

        if (frames <= WARMUP_FRAMES) {
            consecutive = 0
            return false
        }
        consecutive = if (changed >= minChangedCells) consecutive + 1 else 0
        return consecutive >= CONSECUTIVE_FRAMES
    }

    private fun fillGrid(luma: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        val limit = luma.limit()
        for (cy in 0 until gridH) {
            val y0 = cy * height / gridH
            val y1 = (cy + 1) * height / gridH
            val stepY = ((y1 - y0) / 4).coerceAtLeast(1)
            for (cx in 0 until gridW) {
                val x0 = cx * width / gridW
                val x1 = (cx + 1) * width / gridW
                val stepX = ((x1 - x0) / 4).coerceAtLeast(1)
                var sum = 0
                var n = 0
                var y = y0
                while (y < y1) {
                    val row = y * rowStride
                    var x = x0
                    while (x < x1) {
                        val idx = row + x
                        if (idx < limit) {
                            sum += luma.get(idx).toInt() and 0xFF
                            n++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                current[cy * gridW + cx] = if (n == 0) 0f else sum.toFloat() / n
            }
        }
    }

    private companion object {
        const val GLOBAL_CHANGE_FRACTION = 0.6f
        const val ALPHA_STILL = 0.1f
        const val ALPHA_CHANGED = 0.02f
        const val WARMUP_FRAMES = 5
        const val CONSECUTIVE_FRAMES = 2

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
