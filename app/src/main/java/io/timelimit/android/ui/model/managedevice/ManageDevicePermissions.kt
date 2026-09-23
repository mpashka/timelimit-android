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
package io.timelimit.android.ui.model.managedevice

import io.timelimit.android.data.model.Device
import io.timelimit.android.data.model.DevicePlatform
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.integration.platform.SystemPermission
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.ui.manage.device.manage.permission.PermissionScreenContent
import io.timelimit.android.ui.model.ActivityCommand
import io.timelimit.android.ui.model.BackStackItem
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.model.State
import io.timelimit.android.ui.model.setup.SetupLocalModePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.*

object ManageDevicePermissions {
    fun processState(
        scope: CoroutineScope,
        logic: AppLogic,
        activityCommand: SendChannel<ActivityCommand>,
        stateLive: Flow<State.ManageDevice.Permissions>,
        parentBackStackLive: Flow<List<BackStackItem>>,
        deviceLive: SharedFlow<Device>,
        updateState: ((State.ManageDevice.Permissions) -> State) -> Unit
    ): Flow<Screen> {
        val isCurrentDeviceLive = isCurrentDevice(logic, deviceLive).shareIn(scope, SharingStarted.Lazily, 1)
        val deviceStatusLive = isCurrentDeviceLive.transformLatest { isCurrentDevice ->
            if (isCurrentDevice) emitAll(SetupLocalModePermissions.deviceStatus(logic.platformIntegration))
            else emitAll(statusFromDevice(deviceLive))
        }

        return combine(
            stateLive, deviceStatusLive, isCurrentDeviceLive, parentBackStackLive
        ) { state, deviceStatus, isCurrentDevice, parentBackStack ->
            Screen.ManageDevicePermissions(
                state,
                PermissionScreenContent(
                    status = deviceStatus,
                    dialog = state.currentDialog?.let { dialog ->
                        PermissionScreenContent.Dialog(
                            permission = dialog,
                            launchSystemSettings = if (isCurrentDevice) ({
                                if (dialog == SystemPermission.DeviceAdmin && deviceStatus.protectionLevel == ProtectionLevel.DeviceOwner) {
                                    updateState {
                                        State.ManageDevice.DeviceOwner(it.copy(currentDialog = null))
                                    }
                                } else {
                                    activityCommand.trySend(ActivityCommand.LaunchSystemSettings(dialog))

                                    updateState { it.copy(currentDialog = null) }
                                }
                            }) else null,
                            close = { updateState { it.copy(currentDialog = null) } }
                        )
                    },
                    showDetails = { permission -> updateState { it.copy(currentDialog = permission) } },
                    offerWidget = isCurrentDevice
                ),
                parentBackStack
            )
        }
    }

    private fun isCurrentDevice(logic: AppLogic, deviceLive: Flow<Device>): Flow<Boolean> {
        val ownDeviceIdLive = logic.database.config().getOwnDeviceIdFlow()

        return combine(ownDeviceIdLive, deviceLive) { deviceId, device -> device.id == deviceId }
    }

    private fun statusFromDevice(deviceLive: Flow<Device>): Flow<PermissionScreenContent.Status> = deviceLive.map { device ->
        PermissionScreenContent.Status(
            notificationAccess = device.currentNotificationAccessPermission,
            protectionLevel = device.currentProtectionLevel,
            maxProtectionLevel = null,
            usageStats = device.currentUsageStatsPermission,
            overlay = device.currentOverlayPermission,
            accessibility = device.accessibilityServiceEnabled,
            isQOrLater = device.qOrLater,
            androidPlatformLevel = if (device.platformType == DevicePlatform.ANDROID) device.platformLevel else 0
        )
    }.distinctUntilChanged()
}