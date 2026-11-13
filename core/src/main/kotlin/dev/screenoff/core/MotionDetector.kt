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
    val cellThreshold: Float = lerp(30f, 6f, s)

    /** Number of changed cells needed to call it motion. */
    val minChangedCells: Int = (lerp(0.03f, 0.003f, s) * cells).roundToInt().coerceAtLeast(1)

    private val background = FloatArray(cells)
    private val current = FloatArray(cells)
    private val diffs = FloatArray(cells)
    private val sorted = FloatArray(cells)
    private var frames = 0
    private var consecutive = 0

    /** Frames analyzed since the last reset. */
    val framesAnalyzed: Int get() = frames

    /** Changed cells in the last analyzed frame, for the on-screen meter. */
    var lastChangedCells: Int = 0
        private set

    fun reset() {
        frames = 0
        consecutive = 0
        lastChangedCells = 0
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

        for (i in 0 until cells) diffs[i] = current[i] - background[i]

        // Auto exposure brightens or darkens the whole picture when something enters it.
        // Remove that shared shift (the median difference) so only local change counts.
        diffs.copyInto(sorted)
        sorted.sort()
        val shift = (sorted[cells / 2 - 1] + sorted[cells / 2]) / 2f

        var changed = 0
        for (i in 0 until cells) {
            if (abs(diffs[i] - shift) > cellThreshold) changed++
        }
        // A sudden change of the whole picture is an event too: something right in front
        // of the lens, a light switched on, the phone moved. Slow drifts (daylight, exposure
        // settling) stay below the threshold because the background follows them.
        val sceneChanged = abs(shift) > cellThreshold
        if (sceneChanged) changed = cells
        lastChangedCells = changed

        for (i in 0 until cells) {
            val alpha = if (sceneChanged || abs(diffs[i] - shift) > cellThreshold) ALPHA_CHANGED else ALPHA_STILL
            background[i] += alpha * diffs[i]
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
        const val ALPHA_STILL = 0.1f
        const val ALPHA_CHANGED = 0.03f
        const val WARMUP_FRAMES = 5
        const val CONSECUTIVE_FRAMES = 2

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
