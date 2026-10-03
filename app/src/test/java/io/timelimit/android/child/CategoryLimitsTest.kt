package io.timelimit.android.child

import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.UsedTimeItem
import io.timelimit.android.date.DateInTimezone
import io.timelimit.api.WeekLimit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CategoryLimitsTest {
    private val minute = 60_000
    private val wednesday = DateInTimezone.newInstance(LocalDate.of(2026, 9, 30))
    private val monday = wednesday.firstDayOfWeekAsEpochDay

    private fun rule(id: String, days: Int, minutes: Int, perDay: Boolean) = TimeLimitRule(
        id = id, categoryId = "games1", applyToExtraTimeUsage = false, dayMask = days.toByte(),
        maximumTimeInMillis = minutes * minute, startMinuteOfDay = TimeLimitRule.MIN_START_MINUTE,
        endMinuteOfDay = TimeLimitRule.MAX_END_MINUTE, sessionDurationMilliseconds = 0, sessionPauseMilliseconds = 0,
        perDay = perDay, expiresAt = null
    )

    private fun used(day: Int, minutes: Int) = UsedTimeItem(monday + day, minutes * minute.toLong(), "games1", 0, 24 * 60 - 1)

    private val rules = listOf(
        rule("wkdays", 0b0011111, 60, perDay = true),
        rule("wkends", 0b1100000, 120, perDay = true),
        rule("week01", 0b1111111, 300, perDay = false),
    )

    @Test
    fun weekOverWhileTheDayIsNotShowsTheWeekAndOpensOnMonday() {
        val limits = CategoryLimits(rules, listOf(used(0, 150), used(1, 144), used(2, 6)), wednesday, 0)

        assertEquals(60L * minute, limits.dayLimit)
        assertEquals(WeekLimit(300L * minute, 300L * minute), limits.week)
        assertEquals(5, limits.daysUntilWeekOpens)
    }
}
