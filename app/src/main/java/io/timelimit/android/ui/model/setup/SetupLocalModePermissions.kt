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
package io.timelimit.android.ui.model.setup

import androidx.compose.material.SnackbarHostState
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.crypto.Curve25519
import io.timelimit.android.data.Database
import io.timelimit.android.integration.platform.PlatformIntegration
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.ui.manage.device.manage.permission.PermissionScreenContent
import io.timelimit.android.ui.model.ActivityCommand
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.model.State
import io.timelimit.android.ui.setup.SetupUnprovisionedCheck
import io.timelimit.android.util.AndroidVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

object SetupLocalModePermissions {
    fun handle(
        logic: AppLogic,
        scope: CoroutineScope,
        activityCommand: SendChannel<ActivityCommand>,
        stateLive: Flow<State.Setup.DevicePermissions>,
        updateState: ((State.Setup.DevicePermissions) -> State) -> Unit
    ): Flow<Screen> {
        val snackbarHostState = SnackbarHostState()

        val deviceStatusLive = deviceStatus(logic.platformIntegration)

        fun launch(action: suspend () -> Unit) {
            scope.launch {
                try {
                    action()
                } catch (ex: Exception) {
                    snackbarHostState.showSnackbar(logic.context.getString(R.string.error_general))
                }
            }
        }

        return combine(stateLive, deviceStatusLive) { state, deviceStatus ->
            Screen.SetupDevicePermissionsScreen(
                state = state,
                content = PermissionScreenContent(
                    status = deviceStatus,
                    dialog = state.currentDialog?.let {
                        if (it is State.Setup.DevicePermissions.SystemPermissionDialog) it.permission
                        else null
                    }?.let { dialog ->
                        PermissionScreenContent.Dialog(
                            permission = dialog,
                            launchSystemSettings = {
                                activityCommand.trySend(ActivityCommand.LaunchSystemSettings(dialog))

                                updateState { it.copy(currentDialog = null) }
                            },
                            close = { updateState { it.copy(currentDialog = null) } }
                        )
                    },
                    showDetails = { permission -> updateState { it.copy(
                        currentDialog = State.Setup.DevicePermissions.SystemPermissionDialog(permission)
                    ) } },
                    offerWidget = true
                ),
                requestKeyMode = { updateState { it.copy(currentDialog = State.Setup.DevicePermissions.ParentKeyDialog) } },
                next = { updateState { State.Setup.LocalMode(it) } },
                keyDialog = if (state.currentDialog == State.Setup.DevicePermissions.ParentKeyDialog) Screen.SetupDevicePermissionsScreen.KeyDialog(
                    confirm = { launch {
                        updateState { it.copy(currentDialog = null) }

                        if (logic.platformIntegration.getCurrentProtectionLevel() == ProtectionLevel.DeviceOwner) {
                            snackbarHostState.showSnackbar(logic.context.getString(R.string.setup_select_mode_parent_key_error_owner))
                        } else {
                            setupParentKeyMode(logic.database)
                        }
                    } },
                    cancel = { updateState { it.copy(currentDialog = null) } }
                ) else null,
                snackbarHostState = snackbarHostState
            )
        }
    }

    fun deviceStatus(platformIntegration: PlatformIntegration): Flow<PermissionScreenContent.Status> = flow {
        while (true) {
            emit(
                PermissionScreenContent.Status(
                    notificationAccess = platformIntegration.getNotificationAccessPermissionStatus(),
                    protectionLevel = platformIntegration.getCurrentProtectionLevel(),
                    maxProtectionLevel = platformIntegration.maximumProtectionLevel,
                    usageStats = platformIntegration.getForegroundAppPermissionStatus(),
                    overlay = platformIntegration.getDrawOverOtherAppsPermissionStatus(true),
                    accessibility = platformIntegration.isAccessibilityServiceEnabled(),
                    isQOrLater = AndroidVersion.qOrLater,
                    androidPlatformLevel = AndroidVersion.platformLevel
                )
            )

            delay(2000)
        }
    }.distinctUntilChanged()

    private suspend fun setupParentKeyMode(database: Database) {
        val keys = Threads.crypto.executeAndWait { Curve25519.generateKeyPair() }

        Threads.database.executeAndWait {
            database.runInTransaction {
                SetupUnprovisionedCheck.checkSync(database)

                if (database.config().getParentModeKeySync() == null)
                    database.config().setParentModeKeySync(keys)
            }
        }
    }
}