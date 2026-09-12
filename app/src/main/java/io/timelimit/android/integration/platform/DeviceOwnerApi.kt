/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
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
package io.timelimit.android.integration.platform

interface DeviceOwnerApi {
    class DelegationScope(val label: String)

    val delegations: List<DelegationScope>

    fun setDelegations(packageName: String, scopes: List<DelegationScope>)
    fun getDelegations(): Map<String, List<DelegationScope>>
    fun setOrganizationName(name: String)

    fun transferOwnership(packageName: String, dryRun: Boolean = false)

    // returns true on success; never throws
    fun grantLocationAccess(): Boolean

    // @tag:url-filter @tag:device-owner
    // values: String, Int or List<String>; an empty map clears the restrictions; returns true on success; never throws
    fun setApplicationRestrictions(packageName: String, restrictions: Map<String, Any>): Boolean
}