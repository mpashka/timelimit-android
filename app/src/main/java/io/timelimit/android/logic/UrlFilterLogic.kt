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
package io.timelimit.android.logic

import io.timelimit.android.async.Threads
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.UserUrlFilter

// @tag:url-filter @tag:device-owner
class UrlFilterLogic(private val appLogic: AppLogic) {
    private var lastApplied: Map<String, Any>? = null

    init {
        appLogic.deviceUserEntry.observeForever { user ->
            val filter = user?.takeIf { it.type == UserType.Child }?.urlFilter
            val restrictions = UserUrlFilter.toChromeRestrictions(filter)

            Threads.backgroundOSInteraction.execute { apply(restrictions) }
        }
    }

    private fun apply(restrictions: Map<String, Any>) {
        if (restrictions == lastApplied) return

        val ok = UserUrlFilter.BROWSER_PACKAGES.map { packageName ->
            appLogic.platformIntegration.deviceOwner.setApplicationRestrictions(packageName, restrictions)
        }.all { it }

        // without device owner nothing is remembered, so the next start retries
        if (ok) lastApplied = restrictions
    }
}
