package io.timelimit.android.child

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiChoiceTest {
    @Test
    fun onlyTheOldInterfaceSuspendsApps() {
        assertTrue(UiChoice.suspendsApps(systemLevelBlocking = true, newUi = false))
        assertFalse(UiChoice.suspendsApps(systemLevelBlocking = true, newUi = true))
        assertFalse(UiChoice.suspendsApps(systemLevelBlocking = false, newUi = false))
    }
}
