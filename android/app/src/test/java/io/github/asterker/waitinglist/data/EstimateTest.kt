package io.github.asterker.waitinglist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateTest {
    private val day = 24L * 60 * 60 * 1000
    private val joined = "03 Feb 2026"
    private val joinedAt = Estimate.joinedAt(joined)!!

    private fun entry(position: Int, joinPosition: Int?, history: List<PositionSample>) = WaitingListEntry(
        department = "ATC", name = "S2 Training", position = position, joined = joined,
        onRoster = null, theoryPassed = null, history = history, joinPosition = joinPosition,
    )

    @Test
    fun readsJoinDate() {
        assertEquals(Estimate.joinedAt("03 Sep 2026")!! - Estimate.joinedAt("03 Aug 2026")!!, 31 * day)
    }

    @Test
    fun joinPositionGivesAnEstimateStraightAway() {
        // Joined at #45, tracked for just a day at #11, 300 days later: 34 places in 300 days.
        val now = joinedAt + 300 * day
        val estimate = Estimate.of(entry(11, 45, listOf(PositionSample(now - day, 11))), now)
        assertTrue(estimate is Estimate.Ready)
        estimate as Estimate.Ready
        assertTrue(estimate.fromJoin)
        assertEquals(34.0 / 300 * 30.44, estimate.placesPerMonth, 0.01)
        assertEquals(10 / (34.0 / 300), estimate.days, 0.5)
    }

    @Test
    fun withoutJoinPositionNeedsTracking() {
        val now = joinedAt + 300 * day
        val estimate = Estimate.of(entry(11, null, listOf(PositionSample(now - day, 11))), now)
        assertTrue(estimate is Estimate.Gathering)
    }
}
