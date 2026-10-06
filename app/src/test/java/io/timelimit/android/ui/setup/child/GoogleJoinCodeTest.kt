package io.timelimit.android.ui.setup.child

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// @tag:family-join-google
class GoogleJoinCodeTest {
    @Test
    fun codeIs32LowercaseAlphanumericCharsAndRandom() {
        val code = GoogleJoinCode.generate()

        assertTrue(code, Regex("[a-z0-9]{32}").matches(code))
        assertNotEquals(code, GoogleJoinCode.generate())
    }
}
