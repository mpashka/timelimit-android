package io.timelimit.android.child

import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.BitSet

// @tag:category-limits
class CategoryChainTest {
    private val minute = 60_000
    private val user = User(
        id = "child1", name = "Миша", password = "", secondPasswordSalt = "", type = UserType.Child,
        timeZone = "Europe/Berlin", disableLimitsUntil = 0, mail = "", currentDevice = "",
        categoryForNotAssignedApps = "", relaxPrimaryDevice = false, mailNotificationFlags = 0, flags = 0
    )

    private fun category(id: String, title: String, parentId: String, dailyMinutes: Int) = CategoryRelatedData(
        Category(
            id = id, childId = user.id, title = title, blockedMinutesInWeek = ImmutableBitmask(BitSet()),
            extraTimeInMillis = 0, extraTimeDay = -1, temporarilyBlocked = false, temporarilyBlockedEndTime = 0,
            baseVersion = "", assignedAppsVersion = "", timeLimitRulesVersion = "", usedTimesVersion = "", tasksVersion = "",
            parentCategoryId = parentId, blockAllNotifications = false, timeWarnings = 0, minBatteryLevelWhileCharging = 0,
            minBatteryLevelMobile = 0, sort = 0, disableLimitsUntil = 0, flags = 0, blockNotificationDelay = 0
        ),
        listOf(TimeLimitRule(
            id = "r${id.take(5)}", categoryId = id, applyToExtraTimeUsage = false, dayMask = 127, maximumTimeInMillis = dailyMinutes * minute,
            startMinuteOfDay = 0, endMinuteOfDay = 24 * 60 - 1, sessionDurationMilliseconds = 0, sessionPauseMilliseconds = 0,
            perDay = true, expiresAt = null
        )),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
    )

    @Test
    fun subcategoryWithLongerLimitShowsTheParentTimeLeft() {
        val restricted = category("restr1", "Restricted", "", 4)
        val settings = category("sett01", "Settings", "restr1", 10)
        val data = UserRelatedData(user, listOf(restricted, settings), emptyList())
        val cache = CategoryHandlingCache().apply {
            reportStatus(data, BatteryStatus.assumeFull, true, 1_790_000_000_000, true, null, true)
        }

        val chain = CategoryChain(data, "sett01", cache)

        assertEquals(4L * minute, chain.remaining)
        assertEquals("Restricted", chain.limitedBy)
        assertEquals(null, CategoryChain(data, "restr1", cache).limitedBy)
    }
}
