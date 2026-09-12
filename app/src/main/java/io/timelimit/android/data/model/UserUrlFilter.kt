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

// @tag:url-filter @tag:device-owner
data class UserUrlFilter(
    val enabled: Boolean,
    val allow: List<String>,
    val block: List<String>
) {
    companion object {
        const val MAX_ENTRIES = 1000
        const val MAX_ENTRY_LENGTH = 256

        val BROWSER_PACKAGES = listOf("com.android.chrome", "com.chrome.beta")

        fun getListProblem(list: List<String>): String? {
            if (list.size > MAX_ENTRIES) return "more than $MAX_ENTRIES entries"

            val seen = mutableSetOf<String>()

            for (entry in list) {
                if (entry.isEmpty()) return "empty entry"
                if (entry.length > MAX_ENTRY_LENGTH) return "entry longer than $MAX_ENTRY_LENGTH chars"
                if (entry.any { it.code < 32 || it.code == 127 }) return "entry contains control characters"
                if (!seen.add(entry)) return "duplicate entry"
            }

            return null
        }

        fun parseList(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // empty map means: clear all restrictions of the browser
        fun toChromeRestrictions(filter: UserUrlFilter?): Map<String, Any> =
            if (filter == null || !filter.enabled) emptyMap()
            else mapOf(
                "URLAllowlist" to filter.allow,
                "URLBlocklist" to filter.block,
                "IncognitoModeAvailability" to 1,
                "DnsOverHttpsMode" to "off"
            )
    }

    init {
        getListProblem(allow)?.let { throw IllegalArgumentException("allow: $it") }
        getListProblem(block)?.let { throw IllegalArgumentException("block: $it") }
    }
}
