/*
 * TimeLimit Copyright <C> 2019 - 2023 Jonas Lochmann
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
package io.timelimit.android.ui.manage.device.manage.permission

import io.timelimit.android.integration.platform.NewPermissionStatus
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.RuntimePermissionStatus
import io.timelimit.android.integration.platform.SystemPermission

data class PermissionScreenContent(
    val status: Status,
    val dialog: Dialog?,
    val showDetails: (SystemPermission) -> Unit,
    // @tag:new-ui
    val offerWidget: Boolean = false
) {
    data class Status(
        val notificationAccess: NewPermissionStatus,
        val protectionLevel: ProtectionLevel,
        val maxProtectionLevel: ProtectionLevel?,
        val usageStats: RuntimePermissionStatus,
        val overlay: RuntimePermissionStatus,
        val accessibility: Boolean,
        val isQOrLater: Boolean,
        val androidPlatformLevel: Int
    )

    data class Dialog(
        val permission: SystemPermission,
        val launchSystemSettings: (() -> Unit)?,
        val close: () -> Unit
    )
}