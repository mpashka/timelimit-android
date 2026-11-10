package io.timelimit.android.logic

import io.timelimit.android.data.customtypes.ImmutableBitmask
import io.timelimit.android.data.model.AppAllowance
import io.timelimit.android.data.model.AppRule
import io.timelimit.android.data.model.Category
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet
import java.util.TimeZone

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

    @Test
    fun anAllowedAppIsNotFrozenUntilItsAllowanceEnds() {
        val now = 1_790_000_000_000
        val allowances = listOf(AppAllowance("com.mojang.minecraftpe", until = now + 30 * 60_000))
        val modeHandling = handling(wholeWeekMode = true, closedByParent = false)

        fun suspended(at: Long) = SuspendAppsLogic.shouldSuspend(
            packageName = "com.mojang.minecraftpe",
            categoryIds = setOf("games1"),
            allowedCategoryIds = emptySet(),
            allowedPackages = AppAllowance.activePackages(allowances, at, trustTime = true),
            ruleClosedPackages = emptySet(),
            handlingOf = { modeHandling }
        )

        assertFalse(suspended(at = now))
        assertTrue(suspended(at = now + 30 * 60_000))
    }

    @Test
    fun anAllowanceDoesNotUnfreezeWhatTheParentClosed() {
        val closedHandling = handling(wholeWeekMode = false, closedByParent = true)

        assertTrue(SuspendAppsLogic.shouldSuspend(
            packageName = "com.mojang.minecraftpe",
            categoryIds = setOf("games1"),
            allowedCategoryIds = emptySet(),
            allowedPackages = setOf("com.mojang.minecraftpe"),
            ruleClosedPackages = emptySet(),
            handlingOf = { closedHandling }
        ))
    }

    @Test
    fun anAppOutsideItsDaysIsFrozenInAnOpenCategoryUnlessAllowed() {
        val monday = 1_790_000_000_000
        val weekendOnly = listOf(AppRule("com.roblox.client", days = 96, limitMinutes = -1, usedDay = 0, usedMs = 0))
        val openHandling = handling(wholeWeekMode = false, closedByParent = false)

        fun suspended(allowedPackages: Set<String>): Boolean {
            val ruleClosed = AppRuleCheck.closedPackages(
                rules = weekendOnly, allowedPackages = allowedPackages, categoryIdsOf = { setOf("games1") },
                handlingOf = { openHandling }, now = monday, timeZone = TimeZone.getTimeZone("Europe/Berlin"),
                trustTime = true, unsentToday = { 0 }
            )

            return SuspendAppsLogic.shouldSuspend(
                packageName = "com.roblox.client", categoryIds = setOf("games1"), allowedCategoryIds = setOf("games1"),
                allowedPackages = allowedPackages, ruleClosedPackages = ruleClosed, handlingOf = { openHandling }
            )
        }

        assertTrue(suspended(allowedPackages = emptySet()))
        assertFalse(suspended(allowedPackages = setOf("com.roblox.client")))
    }

    @Test
    fun ruleFreezingIsRecountedAtTheChildsMidnight() {
        assertEquals(1_790_028_000_000, SuspendAppsLogic.nextDayStart(1_790_000_000_000, TimeZone.getTimeZone("Europe/Berlin")))
    }
}
