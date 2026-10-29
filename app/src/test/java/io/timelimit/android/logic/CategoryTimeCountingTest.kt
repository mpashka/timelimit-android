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

// @tag:category-time
class CategoryTimeCountingTest {
    private val user = User(
        id = "child1", name = "Миша", password = "", secondPasswordSalt = "", type = UserType.Child,
        timeZone = "Europe/Berlin", disableLimitsUntil = 0, mail = "", currentDevice = "",
        categoryForNotAssignedApps = "", relaxPrimaryDevice = false, mailNotificationFlags = 0, flags = 0
    )

    private val now = 1_790_000_000_000

    private fun handling(disableLimitsUntil: Long): CategoryItselfHandling {
        val category = Category(
            id = "study1", childId = user.id, title = "Учёба",
            blockedMinutesInWeek = ImmutableBitmask(java.util.BitSet()),
            extraTimeInMillis = 0, extraTimeDay = -1, temporarilyBlocked = false, temporarilyBlockedEndTime = 0,
            baseVersion = "", assignedAppsVersion = "", timeLimitRulesVersion = "", usedTimesVersion = "", tasksVersion = "",
            parentCategoryId = "", blockAllNotifications = false, timeWarnings = 0, minBatteryLevelWhileCharging = 0,
            minBatteryLevelMobile = 0, sort = 0, disableLimitsUntil = disableLimitsUntil, flags = 0, blockNotificationDelay = 0
        )
        val related = CategoryRelatedData(category, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        return CategoryItselfHandling.calculate(
            categoryRelatedData = related,
            user = UserRelatedData(user, listOf(related), emptyList()),
            batteryStatus = BatteryStatus.assumeFull,
            shouldTrustTimeTemporarily = true,
            timeInMillis = now,
            assumeCurrentDevice = true,
            currentNetworkId = null,
            hasPremiumOrLocalMode = true
        )
    }

    @Test
    fun categoryWithoutRulesRecordsTimeUnlessLimitsAreLifted() {
        assertTrue(handling(disableLimitsUntil = 0).shouldCountTime)
        assertFalse(handling(disableLimitsUntil = now + 3_600_000).shouldCountTime)
    }
}
