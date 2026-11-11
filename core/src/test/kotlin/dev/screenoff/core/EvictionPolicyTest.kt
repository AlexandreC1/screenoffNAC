package dev.screenoff.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionPolicyTest {

    private fun seg(id: String, at: Long, size: Long, locked: Boolean = false) =
        Segment(id, "file:///$id", RecMode.CONTINUOUS, at, 60_000, size, locked)

    private val big = Long.MAX_VALUE / 4

    @Test
    fun nothingToDoUnderCap() {
        val plan = EvictionPolicy.plan(listOf(seg("a", 1, 100)), capBytes = 1000, nextSegmentBytes = 100, deviceFreeBytes = big, freeSpaceFloorBytes = 0)
        assertTrue(plan.satisfied)
        assertTrue(plan.victims.isEmpty())
    }

    @Test
    fun evictsOldestFirstUntilItFits() {
        val segs = listOf(seg("c", 3, 400), seg("a", 1, 400), seg("b", 2, 400))
        val plan = EvictionPolicy.plan(segs, capBytes = 1000, nextSegmentBytes = 400, deviceFreeBytes = big, freeSpaceFloorBytes = 0)
        assertTrue(plan.satisfied)
        assertEquals(listOf("a", "b"), plan.victims.map { it.id })
    }

    @Test
    fun lockedClipsAreNeverVictims() {
        val segs = listOf(seg("a", 1, 400, locked = true), seg("b", 2, 400), seg("c", 3, 400))
        val plan = EvictionPolicy.plan(segs, capBytes = 1000, nextSegmentBytes = 400, deviceFreeBytes = big, freeSpaceFloorBytes = 0)
        assertTrue(plan.satisfied)
        assertEquals(listOf("b", "c"), plan.victims.map { it.id })
    }

    @Test
    fun unsatisfiedWhenOnlyLockedRemain() {
        val segs = listOf(seg("a", 1, 600, locked = true), seg("b", 2, 600, locked = true))
        val plan = EvictionPolicy.plan(segs, capBytes = 1000, nextSegmentBytes = 400, deviceFreeBytes = big, freeSpaceFloorBytes = 0)
        assertFalse(plan.satisfied)
        assertTrue(plan.victims.isEmpty())
    }

    @Test
    fun freeSpaceFloorForcesEvictionEvenUnderCap() {
        val segs = listOf(seg("a", 1, 300), seg("b", 2, 300))
        val plan = EvictionPolicy.plan(segs, capBytes = 10_000, nextSegmentBytes = 200, deviceFreeBytes = 400, freeSpaceFloorBytes = 500)
        assertTrue(plan.satisfied)
        assertEquals(listOf("a"), plan.victims.map { it.id })
    }

    @Test
    fun estimateIncludesMargin() {
        // 8 Mbps for 60 s is 60 MB, plus 15 percent.
        assertEquals(69_000_000L, EvictionPolicy.estimateSegmentBytes(8_000_000, 60))
    }
}
