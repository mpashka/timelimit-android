/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.logic

import io.timelimit.android.logic.ForegroundTimeAggregation.Event.EndAll
import io.timelimit.android.logic.ForegroundTimeAggregation.Event.Paused
import io.timelimit.android.logic.ForegroundTimeAggregation.Event.Resumed
import org.junit.Assert.assertEquals
import org.junit.Test

// @tag:category-limits
class ForegroundTimeAggregationTest {
    private fun aggregate(vararg events: ForegroundTimeAggregation.Event) =
        ForegroundTimeAggregation.aggregate(events.toList(), start = 1000, end = 2000)

    @Test
    fun simpleIntervalsAreSummed() {
        assertEquals(
            mapOf("a" to 300L, "b" to 100L),
            aggregate(
                Resumed(1100, "a", "A"), Paused(1200, "a", "A"),
                Resumed(1200, "b", "B"), Paused(1300, "b", "B"),
                Resumed(1300, "a", "A"), Paused(1500, "a", "A")
            )
        )
    }

    @Test
    fun overlappingActivitiesOfOnePackageAreCountedOnce() {
        assertEquals(
            mapOf("a" to 400L),
            aggregate(
                Resumed(1100, "a", "A1"), Resumed(1200, "a", "A2"),
                Paused(1300, "a", "A1"), Paused(1500, "a", "A2")
            )
        )
    }

    @Test
    fun pauseWithoutResumeCountsFromStart() {
        assertEquals(mapOf("a" to 250L, "b" to 100L), aggregate(Paused(1250, "a", "A"), Resumed(1300, "b", "B"), Paused(1400, "b", "B")))
    }

    @Test
    fun pauseWithoutResumeCoversOtherActivitiesOfThePackage() {
        assertEquals(mapOf("a" to 500L), aggregate(Resumed(1100, "a", "A2"), Paused(1200, "a", "A2"), Paused(1500, "a", "A1")))
    }

    @Test
    fun unfinishedIntervalIsCountedUntilEnd() {
        assertEquals(mapOf("a" to 200L), aggregate(Resumed(1800, "a", "A")))
    }

    @Test
    fun deviceShutdownEndsEverything() {
        assertEquals(
            mapOf("a" to 100L),
            aggregate(Resumed(1100, "a", "A"), EndAll(1200), Paused(1900, "a", "A"))
        )
    }

    @Test
    fun eventsOutsideOfTheRangeAreClipped() {
        assertEquals(mapOf("a" to 100L, "b" to 50L), aggregate(Resumed(900, "a", "A"), Paused(1100, "a", "A"), Resumed(1950, "b", "B"), Paused(2100, "b", "B")))
    }
}
