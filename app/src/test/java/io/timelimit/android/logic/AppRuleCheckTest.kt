package io.timelimit.android.logic

import io.timelimit.android.data.model.AppRule
import io.timelimit.android.logic.AppRuleCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRuleCheckTest {
    private val tuesday = 1
    private val today = 20354

    @Test
    fun weekendOnlyAndOwnLimitAcrossTablets() {
        val weekendOnly = AppRule("com.roblox.client", days = 96, limitMinutes = -1, usedDay = 0, usedMs = 0)
        assertEquals(Verdict.NotToday(96, 4), AppRuleCheck.check(weekendOnly, tuesday, today, 0))
        assertEquals(Verdict.ClosedAlways, AppRuleCheck.check(weekendOnly.copy(days = 0), tuesday, today, 0))

        val halfHour = AppRule("com.roblox.client", days = 127, limitMinutes = 30, usedDay = today, usedMs = 25 * 60_000L)
        assertEquals(Verdict.Open, AppRuleCheck.check(halfHour, tuesday, today, 4 * 60_000L))
        assertEquals(Verdict.LimitOver, AppRuleCheck.check(halfHour, tuesday, today, 5 * 60_000L))
        assertEquals(Verdict.Open, AppRuleCheck.check(halfHour.copy(usedDay = today - 1), tuesday, today, 5 * 60_000L))
    }
}
