package io.timelimit.android.logic

import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.AppRule
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.CategoryApp
import io.timelimit.android.data.model.CategoryFlags
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.BitSet

// @tag:app-hidden
class HiddenPackagesTest {
    private fun category(id: String, parent: String, flags: Long) = Category(
        id = id, childId = "child1", title = id, blockedMinutesInWeek = ImmutableBitmask(BitSet()),
        extraTimeInMillis = 0, extraTimeDay = -1, temporarilyBlocked = false, temporarilyBlockedEndTime = 0,
        baseVersion = "", assignedAppsVersion = "", timeLimitRulesVersion = "", usedTimesVersion = "", tasksVersion = "",
        parentCategoryId = parent, blockAllNotifications = false, timeWarnings = 0, minBatteryLevelWhileCharging = 0,
        minBatteryLevelMobile = 0, sort = 0, disableLimitsUntil = 0, flags = flags, blockNotificationDelay = 0
    )

    @Test
    fun hiddenRulesAndCategoriesWithSubcategories() {
        val result = SuspendAppsLogic.hiddenPackages(
            appRules = listOf(
                AppRule("com.roblox.client", days = 127, limitMinutes = -1, usedDay = 0, usedMs = 0, hidden = true),
                AppRule("com.weekend", days = 96, limitMinutes = -1, usedDay = 0, usedMs = 0)
            ),
            categories = listOf(
                category("games1", "", CategoryFlags.HIDDEN),
                category("shoot1", "games1", 0),
                category("schoo1", "", CategoryFlags.HAS_BLOCKED_NETWROK_LIST)
            ),
            categoryApps = listOf(
                CategoryApp("games1", "com.game"),
                CategoryApp("shoot1", "com.shooter:com.shooter.Main"),
                CategoryApp("shoot1", "com.elsewhere@otherDevice"),
                CategoryApp("games1", ".feature.wifi"),
                CategoryApp("schoo1", "com.school")
            ),
            deviceId = "thisDevice"
        )

        assertEquals(setOf("com.roblox.client", "com.game", "com.shooter"), result)
    }
}
