package io.timelimit.android.child

import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.derived.CategoryRelatedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

class SchedulesTest {
    private val games = Category(
        id = "games1", childId = "child1", title = "Игры", blockedMinutesInWeek = ImmutableBitmask(BitSet()),
        extraTimeInMillis = 0, extraTimeDay = -1, temporarilyBlocked = false, temporarilyBlockedEndTime = 0,
        baseVersion = "", assignedAppsVersion = "", timeLimitRulesVersion = "", usedTimesVersion = "", tasksVersion = "",
        parentCategoryId = "", blockAllNotifications = false, timeWarnings = 0, minBatteryLevelWhileCharging = 0,
        minBatteryLevelMobile = 0, sort = 0, disableLimitsUntil = 0, flags = 0, blockNotificationDelay = 0
    )

    private fun ban(id: String, days: Int, start: Int, end: Int) = TimeLimitRule(
        id = id, categoryId = games.id, applyToExtraTimeUsage = true, dayMask = days.toByte(), maximumTimeInMillis = 0,
        startMinuteOfDay = start, endMinuteOfDay = end, sessionDurationMilliseconds = 0, sessionPauseMilliseconds = 0,
        perDay = false, expiresAt = null
    )

    @Test
    fun sleepAsTheWebConsoleWritesItIsOneModeAcrossMidnight() {
        val related = CategoryRelatedData(
            games, listOf(ban("evenin", 127, 21 * 60, 24 * 60 - 1), ban("mornin", 127, 0, 7 * 60 - 1)),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )

        val bans = Schedules.readBans(listOf(related))
        assertEquals(1, bans.size)
        assertEquals(Schedules.Kind.Sleep, Schedules.kindOf(bans[0].start, bans[0].end))

        val tuesday2200 = ModeClock.DAY + 22 * 60
        val blocked = Schedules.blockedMinutes(related)
        assertTrue(blocked[tuesday2200])
        assertEquals(9 * 60, ModeClock.minutesUntilOpen(blocked, tuesday2200))
        assertEquals(Schedules.Kind.Sleep, Schedules.kindAt(blocked, tuesday2200))
    }
}
