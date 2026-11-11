package io.timelimit.android.ui.widget

import io.timelimit.android.child.ModeClock
import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Bar
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Item
import io.timelimit.api.ModeKind
import io.timelimit.api.WeekLimit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.BitSet

class TimesWidgetModelTest {
    private val minute = 60_000L

    @Test
    fun barsFillAsTimeIsSpentAndTurnRedWhenNothingIsLeft() {
        assertEquals(null, TimesWidgetModel.item("free", "Всегда можно", null, null, null))
        assertEquals(
            Item("games", "Игры", Bar(25, false), Bar(80, false)),
            TimesWidgetModel.item("games", "Игры", 45 * minute, 60 * minute, WeekLimit(240 * minute, 300 * minute))
        )
        assertEquals(
            Item("video", "Видео", Bar(100, true), null),
            TimesWidgetModel.item("video", "Видео", 0, 30 * minute, null)
        )
        assertEquals(
            Item("games", "Игры", null, Bar(100, true)),
            TimesWidgetModel.item("games", "Игры", 0, null, WeekLimit(300 * minute, 300 * minute))
        )
    }

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

    // sleep 21:00–07:00 every day, study 08:00–14:00 on weekdays
    private val modes = listOf(CategoryRelatedData(
        games,
        listOf(ban("evenin", 127, 21 * 60, 24 * 60 - 1), ban("mornin", 127, 0, 7 * 60 - 1), ban("study1", 31, 8 * 60, 14 * 60 - 1)),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
    ))
    private val tuesday = ModeClock.DAY

    private fun at(hours: Int, minutes: Int) = tuesday + hours * 60 + minutes

    @Test
    fun ringShowsTheShareOfTheOpenGapBeforeTheNextMode() {
        // open since 14:00, sleep at 21:00: 3 of 7 hours left at 18:00
        val mode = TimesWidgetModel.mode(modes, at(18, 0), 0)!!

        assertEquals(ModeKind.Sleep, mode.kind)
        assertEquals(180, mode.startsIn)
        assertEquals(42, mode.leftPercent)
        assertEquals(180 * minute, mode.from)
        assertEquals(780 * minute, mode.until)
    }

    @Test
    fun ringShowsTheShareOfTheModeLeftWhileItGoesOn() {
        // study 08:00–14:00, at 12:30: 90 of 360 minutes left
        val mode = TimesWidgetModel.mode(modes, at(12, 30), 0)!!

        assertEquals(ModeKind.Study, mode.kind)
        assertEquals(null, mode.startsIn)
        assertEquals(25, mode.leftPercent)
        assertEquals(90 * minute, mode.until)
        assertEquals(-270 * minute, mode.from)
    }
}
