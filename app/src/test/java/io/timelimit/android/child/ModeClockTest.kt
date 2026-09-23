package io.timelimit.android.child

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.BitSet

class ModeClockTest {
    private val sleep = BitSet(ModeClock.WEEK).apply {
        for (day in 0 until 7) {
            val evening = day * ModeClock.DAY + 21 * 60
            for (minute in evening until evening + 10 * 60) set(minute % ModeClock.WEEK)
        }
    }
    private val tuesday1838 = ModeClock.DAY + 18 * 60 + 38

    @Test
    fun limitOverInTheEveningOpensAfterSleepNotAtMidnight() {
        val nextDay = ModeClock.nextDayStart(tuesday1838)

        assertEquals(7 * 60, ModeClock.minutesUntilOpen(sleep, nextDay % ModeClock.WEEK))
    }

    @Test
    fun nextSleepStartsAt2100AndLastsTenHours() {
        assertEquals(142 to 600, ModeClock.nextWindow(sleep, tuesday1838, ModeClock.DAY))
    }
}
