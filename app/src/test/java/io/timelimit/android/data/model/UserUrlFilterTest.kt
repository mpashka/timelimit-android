/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// @tag:url-filter
class UserUrlFilterTest {
    @Test
    fun enabledFilterBecomesChromePolicies() {
        assertEquals(
            mapOf(
                "URLAllowlist" to listOf("wikipedia.org"),
                "URLBlocklist" to listOf("*", "google.com/search"),
                "IncognitoModeAvailability" to 1,
                "DnsOverHttpsMode" to "off"
            ),
            UserUrlFilter.toChromeRestrictions(UserUrlFilter(true, listOf("wikipedia.org"), listOf("*", "google.com/search")))
        )
    }

    @Test
    fun disabledOrMissingFilterClearsPolicies() {
        assertEquals(emptyMap<String, Any>(), UserUrlFilter.toChromeRestrictions(UserUrlFilter(false, listOf("a.org"), listOf("*"))))
        assertEquals(emptyMap<String, Any>(), UserUrlFilter.toChromeRestrictions(null))
    }

    @Test
    fun listLimitsMirrorTheServer() {
        assertNull(UserUrlFilter.getListProblem(List(1000) { "site$it.org" }))
        assertEquals("more than 1000 entries", UserUrlFilter.getListProblem(List(1001) { "site$it.org" }))
        assertEquals("entry longer than 256 chars", UserUrlFilter.getListProblem(listOf("a".repeat(257))))
        assertEquals("duplicate entry", UserUrlFilter.getListProblem(listOf("a.org", "a.org")))
        assertEquals("empty entry", UserUrlFilter.getListProblem(listOf("")))
        assertEquals("entry contains control characters", UserUrlFilter.getListProblem(listOf("a\tb")))
    }

    @Test
    fun textIsSplitIntoTrimmedDistinctLines() {
        assertEquals(listOf("a.org", "b.org"), UserUrlFilter.parseList(" a.org\n\nb.org \na.org\n"))
    }
}
