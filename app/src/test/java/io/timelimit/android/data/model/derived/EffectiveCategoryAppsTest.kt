package io.timelimit.android.data.model.derived

import io.timelimit.android.data.model.CategoryApp
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveCategoryAppsTest {
    private fun effective(vararg categoryApps: CategoryApp) = UserRelatedData
        .effectiveCategoryApps(categoryApps.toList(), deviceId = "thisDevice")
        .map { it.categoryId to it.appSpecifierString }
        .toSet()

    @Test
    fun sharedRecordAppliesWithoutARecordOfThisDevice() {
        assertEquals(
            setOf("educa1" to "at.tomtasche.reader"),
            effective(CategoryApp("educa1", "at.tomtasche.reader"))
        )
    }

    @Test
    fun recordOfThisDeviceReplacesTheSharedOne() {
        assertEquals(
            setOf("reade1" to "at.tomtasche.reader@thisDevice"),
            effective(
                CategoryApp("educa1", "at.tomtasche.reader"),
                CategoryApp("reade1", "at.tomtasche.reader@thisDevice")
            )
        )
    }

    @Test
    fun recordOfAnotherDeviceDoesNotApply() {
        assertEquals(
            setOf("educa1" to "at.tomtasche.reader"),
            effective(
                CategoryApp("educa1", "at.tomtasche.reader"),
                CategoryApp("games1", "at.tomtasche.reader@otherDevice")
            )
        )
    }

    @Test
    fun recordOfThisDeviceReplacesSharedRecordOfASingleActivity() {
        assertEquals(
            setOf("reade1" to "at.tomtasche.reader@thisDevice"),
            effective(
                CategoryApp("educa1", "at.tomtasche.reader:at.tomtasche.reader.Main"),
                CategoryApp("reade1", "at.tomtasche.reader@thisDevice")
            )
        )
    }

    // the same order of importance as in AppBaseHandling.calculate: a record of the whole app wins
    // over a record of a single activity only when the app record is the one of this device
    @Test
    fun sharedRecordOfTheWholeAppSurvivesAnActivityRecordOfThisDevice() {
        assertEquals(
            setOf(
                "educa1" to "at.tomtasche.reader",
                "reade1" to "at.tomtasche.reader:at.tomtasche.reader.Main@thisDevice"
            ),
            effective(
                CategoryApp("educa1", "at.tomtasche.reader"),
                CategoryApp("reade1", "at.tomtasche.reader:at.tomtasche.reader.Main@thisDevice")
            )
        )
    }

    @Test
    fun otherAppsKeepTheirSharedRecords() {
        assertEquals(
            setOf("educa1" to "org.wikipedia", "reade1" to "at.tomtasche.reader@thisDevice"),
            effective(
                CategoryApp("educa1", "at.tomtasche.reader"),
                CategoryApp("educa1", "org.wikipedia"),
                CategoryApp("reade1", "at.tomtasche.reader@thisDevice")
            )
        )
    }
}
