package dev.screenoff.core

/**
 * Decides which clips to delete so the next segment fits.
 * Pure function: the caller performs the deletions.
 * Locked clips count toward the cap and are never chosen.
 */
object EvictionPolicy {

    data class Plan(val victims: List<Segment>, val satisfied: Boolean)

    fun estimateSegmentBytes(bitrateBps: Int, seconds: Int): Long =
        (bitrateBps.toLong() / 8L * seconds.toLong() * 115L) / 100L

    fun plan(
        segments: List<Segment>,
        capBytes: Long,
        nextSegmentBytes: Long,
        deviceFreeBytes: Long,
        freeSpaceFloorBytes: Long,
    ): Plan {
        val used = segments.sumOf { it.sizeBytes }
        val capDeficit = used + nextSegmentBytes - capBytes
        val floorDeficit = freeSpaceFloorBytes + nextSegmentBytes - deviceFreeBytes
        val toFree = maxOf(capDeficit, floorDeficit, 0L)
        if (toFree == 0L) return Plan(emptyList(), true)

        val victims = ArrayList<Segment>()
        var freed = 0L
        for (s in segments.filter { !it.locked }.sortedBy { it.startedAtMs }) {
            if (freed >= toFree) break
            victims += s
            freed += s.sizeBytes
        }
        return Plan(victims, freed >= toFree)
    }
}
