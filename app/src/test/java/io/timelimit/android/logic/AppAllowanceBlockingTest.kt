package io.timelimit.android.logic

import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

class AppAllowanceBlockingTest {
    private val user = User(
        id = "child1", name = "Миша", password = "", secondPasswordSalt = "", type = UserType.Child,
        timeZone = "Europe/Berlin", disableLimitsUntil = 0, mail = "", currentDevice = "",
        categoryForNotAssignedApps = "", relaxPrimaryDevice = false, mailNotificationFlags = 0, flags = 0
    )

    private fun handling(wholeWeekMode: Boolean, closedByParent: Boolean): CategoryItselfHandling {
        val category = Category(
            id = "games1", childId = user.id, title = "Игры",
            blockedMinutesInWeek = ImmutableBitmask(BitSet().apply { if (wholeWeekMode) set(0, Category.BLOCKED_MINUTES_IN_WEEK_LENGTH) }),
            extraTimeInMillis = 0, extraTimeDay = -1, temporarilyBlocked = closedByParent, temporarilyBlockedEndTime = 0,
            baseVersion = "", assignedAppsVersion = "", timeLimitRulesVersion = "", usedTimesVersion = "", tasksVersion = "",
            parentCategoryId = "", blockAllNotifications = false, timeWarnings = 0, minBatteryLevelWhileCharging = 0,
            minBatteryLevelMobile = 0, sort = 0, disableLimitsUntil = 0, flags = 0, blockNotificationDelay = 0
        )
        val related = CategoryRelatedData(category, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        return CategoryItselfHandling.calculate(
            categoryRelatedData = related,
            user = UserRelatedData(user, listOf(related), emptyList()),
            batteryStatus = BatteryStatus.assumeFull,
            shouldTrustTimeTemporarily = true,
            timeInMillis = 1_790_000_000_000,
            assumeCurrentDevice = true,
            currentNetworkId = null,
            hasPremiumOrLocalMode = true
        )
    }

    @Test
    fun allowanceOpensThroughModeButNotThroughParentsClosing() {
        assertTrue(handling(wholeWeekMode = true, closedByParent = false).shouldBlockActivities(appAllowed = false))
        assertFalse(handling(wholeWeekMode = true, closedByParent = false).shouldBlockActivities(appAllowed = true))
        assertTrue(handling(wholeWeekMode = false, closedByParent = true).shouldBlockActivities(appAllowed = true))
    }
}
