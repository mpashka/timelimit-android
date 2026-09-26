package io.timelimit.android.logic

import io.timelimit.android.logic.AppActivityReportLogic.Companion.iconsToSend
import org.junit.Assert.assertEquals
import org.junit.Test

// @tag:app-icon
class AppIconsToSendTest {
    @Test
    fun onlyAppsWithoutTheirCurrentVersionSentAreSent() {
        assertEquals(
            listOf("updated", "new", "legacy"),
            iconsToSend(
                current = mapOf("same" to 3L, "updated" to 5L, "new" to 1L, "legacy" to 7L),
                sent = setOf("same:3", "updated:4", "legacy", "removed:2")
            )
        )
    }
}
