package io.timelimit.android.child

import io.timelimit.android.data.model.UserType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiChoiceTest {
    @Test
    fun theLauncherSendsAChildToWhatCanAndAParentToTheirScreens() {
        assertEquals(WhatCanActivity::class.java, UiChoice.launcherTarget(UserType.Child))
        assertEquals(ParentActivity::class.java, UiChoice.launcherTarget(UserType.Parent))
        assertNull(UiChoice.launcherTarget(null))
    }

    @Test
    fun onlyTheOldInterfaceSuspendsApps() {
        assertTrue(UiChoice.suspendsApps(systemLevelBlocking = true, newUi = false))
        assertFalse(UiChoice.suspendsApps(systemLevelBlocking = true, newUi = true))
        assertFalse(UiChoice.suspendsApps(systemLevelBlocking = false, newUi = false))
    }
}
