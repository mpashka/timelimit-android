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
package io.timelimit.android.ui.model.managedevice

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.lifecycle.asFlow
import io.timelimit.android.BuildConfig
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.model.App
import io.timelimit.android.data.model.Device
import io.timelimit.android.integration.platform.DeviceOwnerApi
import io.timelimit.android.integration.platform.PlatformIntegration
import io.timelimit.android.integration.platform.ProtectionLevel
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.ui.diagnose.exception.ExceptionUtil
import io.timelimit.android.ui.model.AuthenticationModelApi
import io.timelimit.android.ui.model.BackStackItem
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.model.State
import io.timelimit.android.ui.model.flow.Case
import io.timelimit.android.ui.model.flow.splitConflated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.Serializable

object DeviceOwnerHandling {
    private const val LOG_TAG = "DeviceOwnerHandling"

    data class OwnerState(
        val apps: List<String> = emptyList(),
        val dialog: Dialog? = null,
        val organizationName: OrganizationName = OrganizationName.Original
    ): Serializable {
        sealed class Dialog: Serializable

        data class AppListDialog(
            val filter: String = ""
        ): Dialog()

        data class TransferOwnershipDialog(
            val packageName: String
        ): Dialog()

        data class ErrorDialog(
            val message: String
        ): Dialog()
    }

    sealed class OrganizationName: Serializable {
        object Original: OrganizationName()
        object Error: OrganizationName()
        class Modified(val value: String): OrganizationName()
    }

    sealed class OwnerScreen {
        object Error: OwnerScreen()

        data class Normal(
            val isParentAuthenticated: Boolean,
            val organizationName: String,
            val dialog: Dialog?,
            val scopes: List<DeviceOwnerApi.DelegationScope>,
            val apps: List<AppInfo>,
            val actions: Actions
        ): OwnerScreen() {
            data class AppInfo(
                val packageName: String,
                val title: String?,
                val icon: Drawable?,
                val scopes: Set<DeviceOwnerApi.DelegationScope>
            )

            sealed class Dialog

            data class AppListDialog(
                val filter: String,
                val apps: List<App>
            ): Dialog()

            data class TransferOwnershipDialog(
                val packageName: String,
                val confirm: () -> Unit,
                val cancel: () -> Unit
            ): Dialog()

            data class ErrorDialog(
                val message: String,
                val close: () -> Unit
            ): Dialog()

            data class Actions(
                val updateOrganizationName: ((String) -> Unit)?,
                val showAppListDialog: () -> Unit,
                val dismissAppListDialog: () -> Unit,
                val addApp: (String) -> Unit,
                val updateScopeEnabled: (String, DeviceOwnerApi.DelegationScope, Boolean) -> Unit,
                val transferOwnership: (String) -> Unit,
                val updateDialogSearch: (String) -> Unit
            )
        }
    }

    fun processState(
        logic: AppLogic,
        scope: CoroutineScope,
        authentication: AuthenticationModelApi,
        deviceLive: SharedFlow<Device>,
        ownerStateLive: SharedFlow<State.ManageDevice.DeviceOwner>,
        backStackLive: Flow<List<BackStackItem>>,
        updateState: ((State.ManageDevice.DeviceOwner) -> State) -> Unit
    ): Flow<Screen> {
        val snackbarHostState = SnackbarHostState()

        val isMatchingDeviceLive = combine(deviceLive, logic.deviceId.asFlow()) { device, id ->
            device.id == id && device.currentProtectionLevel == ProtectionLevel.DeviceOwner
        }.distinctUntilChanged()

        val screenLive = getScreen(
            logic,
            ownerStateLive.map { it.details },
            isMatchingDeviceLive,
            scope,
            authentication,
            snackbarHostState,
            updateState = { transformState ->
                updateState {
                    it.copy(details = transformState(it.details))
                }
            }
        )

        return combine(ownerStateLive, screenLive, backStackLive) { state, screen, backStack ->
            Screen.DeviceOwnerScreen(state, screen, backStack, snackbarHostState)
        }
    }

    private fun getScreen(
        logic: AppLogic,
        state: Flow<OwnerState>,
        isMatchingDeviceLive: Flow<Boolean>,
        scope: CoroutineScope,
        authentication: AuthenticationModelApi,
        snackbarHostState: SnackbarHostState,
        updateState: ((OwnerState) -> OwnerState) -> Unit
    ): Flow<OwnerScreen> = flow<OwnerScreen> {
        val owner = logic.platformIntegration.deviceOwner
        val scopes = owner.delegations

        fun launch(action: suspend () -> Unit) {
            scope.launch {
                try {
                    action()
                } catch (ex: Exception) {
                    val result = snackbarHostState.showSnackbar(
                        logic.context.getString(R.string.error_general),
                        logic.context.getString(R.string.generic_show_details),
                        SnackbarDuration.Short
                    )

                    if (result == SnackbarResult.ActionPerformed) updateState {
                        it.copy(dialog = OwnerState.ErrorDialog(ExceptionUtil.format(ex)))
                    }
                }
            }
        }

        val refreshSignal = Channel<Unit>(Channel.CONFLATED)

        val hadUpdateOrganizationNameErrorLive = state.map { it.organizationName == OrganizationName.Error }.distinctUntilChanged()
        val isParentAuthenticatedLive = authentication.authenticatedParentOnly.map { it != null }.distinctUntilChanged()

        val organizationNameLive = state.map { it.organizationName }.distinctUntilChanged().map {
            when (it) {
                is OrganizationName.Modified -> it.value
                is OrganizationName.Error, OrganizationName.Original -> logic.database.config().getCustomOrganizationName() ?: ""
            }
        }

        val actions = OwnerScreen.Normal.Actions(
            updateOrganizationName = { organizationName ->
                updateState { state ->
                    if (state.organizationName == OrganizationName.Error) state
                    else state.copy(organizationName = OrganizationName.Modified(organizationName))
                }

                launch {
                    if (isParentAuthenticatedLive.first()) try {
                        owner.setOrganizationName(organizationName)

                        Threads.database.executeAndWait {
                            logic.database.config().setCustomOrganizationName(organizationName)
                        }
                    } catch (ex: Exception) {
                        updateState { it.copy(organizationName = OrganizationName.Error) }

                        throw ex
                    } else updateState { state ->
                        if (state.organizationName == OrganizationName.Error) state
                        else state.copy(organizationName = OrganizationName.Original)
                    }
                }
            },
            addApp = { packageName ->
                launch {
                    if (authentication.authenticatedParentOnly.first() != null) updateState { state ->
                        state.copy(apps = state.apps + packageName, dialog = null)
                    } else updateState { it.copy(dialog = null) }
                }
            },
            dismissAppListDialog = {
                updateState { it.copy(dialog = null) }
            },
            showAppListDialog = {
                launch {
                    authentication.doParentAuthentication()?.also {
                        updateState { it.copy(dialog = OwnerState.AppListDialog()) }
                    }
                }
            },
            updateScopeEnabled = { packageName, scope, enable ->
                launch {
                    if (authentication.authenticatedParentOnly.first() != null) {
                        val current = owner.getDelegations()[packageName] ?: emptySet()
                        val new = if (enable) current + scope else current - scope

                        owner.setDelegations(packageName, new)

                        refreshSignal.send(Unit)
                    } else authentication.doParentAuthentication()
                }
            },
            transferOwnership = { packageName ->
                launch {
                    owner.transferOwnership(packageName, dryRun = true)

                    if (authentication.doParentAuthentication() != null) {
                        updateState { it.copy(dialog = OwnerState.TransferOwnershipDialog(packageName)) }
                    }
                }
            },
            updateDialogSearch = { filter ->
                updateState {
                    if (it.dialog is OwnerState.AppListDialog) it.copy(dialog = it.dialog.copy(filter = filter))
                    else it
                }
            }
        )

        val appsLive = getApps(
            logic.platformIntegration,
            refreshSignal,
            manualAppsLive = state.map { it.apps },
            extendManualApps = { newApps -> updateState { it.copy(apps = it.apps + newApps) } }
        )

        val dialogLive = getNullableDialog(
            logic.platformIntegration,
            state.map { it.dialog },
            authentication,
            updateState,
            ::launch
        )

        emitAll(
            combine(
                combine(appsLive, dialogLive) { a, b -> Pair(a, b) },
                hadUpdateOrganizationNameErrorLive, isParentAuthenticatedLive, organizationNameLive,
                isMatchingDeviceLive
            ) { appsAndDialog, hadUpdateOrganizationNameError, isParentAuthenticated, organizationName, isMatchingDevice ->
                val (apps, dialog) = appsAndDialog

                if (isMatchingDevice) OwnerScreen.Normal(
                    isParentAuthenticated = isParentAuthenticated,
                    organizationName = organizationName,
                    dialog = dialog,
                    scopes = scopes,
                    apps = apps,
                    actions = actions.copy(
                        updateOrganizationName =
                        if (hadUpdateOrganizationNameError || !isParentAuthenticated) null
                        else actions.updateOrganizationName
                    )
                ) else OwnerScreen.Error
            }
        )
    }.catch {
        if (BuildConfig.DEBUG) {
            Log.w(LOG_TAG, "error during generating screen", it)
        }

        emit(OwnerScreen.Error)
    }

    private fun getApps(
        integration: PlatformIntegration,
        refreshSignal: ReceiveChannel<Unit>,
        manualAppsLive: Flow<List<String>>,
        extendManualApps: (List<String>) -> Unit
    ): Flow<List<OwnerScreen.Normal.AppInfo>> {
        val titleAndIconCache = MutableStateFlow(emptyMap<String, Pair<String, Drawable>>())

        fun getTitleAndIcon(packageName: String): Pair<String, Drawable>? {
            return titleAndIconCache.value.get(packageName) ?: run {
                val icon = integration.getAppIcon(packageName)
                val title = integration.getLocalAppTitle(packageName)

                if (icon != null && title != null) titleAndIconCache.updateAndGet {
                    it + Pair(packageName, Pair(title, icon))
                }[packageName]
                else null
            }
        }

        val mapLive = flow {
            while (true) {
                emit(integration.deviceOwner.getDelegations())
                refreshSignal.receive()
            }
        }

        return mapLive.combine(manualAppsLive) { map, manualApps ->
            val newApps = (map.keys - manualApps.toSet()).toList()

            extendManualApps(newApps)

            val allManualApps = manualApps + newApps

            allManualApps.map { packageName ->
                val titleAndIcon = getTitleAndIcon(packageName)

                OwnerScreen.Normal.AppInfo(
                    packageName = packageName,
                    title = titleAndIcon?.first,
                    icon = titleAndIcon?.second,
                    scopes = map[packageName]?.toSet() ?: emptySet()
                )
            }
        }
    }

    private fun getNullableDialog(
        integration: PlatformIntegration,
        state: Flow<OwnerState.Dialog?>,
        authentication: AuthenticationModelApi,
        updateState: ((OwnerState) -> OwnerState) -> Unit,
        launch: (suspend () -> Unit) -> Unit
    ): Flow<OwnerScreen.Normal.Dialog?> = state.splitConflated(
        Case.simple<_, _, OwnerState.AppListDialog> { getAppListDialog(integration, it) },
        Case.simple<_, _, OwnerState.TransferOwnershipDialog> { stateLive ->
            stateLive.map { OwnerScreen.Normal.TransferOwnershipDialog(
                packageName = it.packageName,
                confirm = { launch {
                    try {
                        if (authentication.authenticatedParentOnly.first() == null) throw IllegalStateException()
                        if (!integration.setEnableSystemLockdown(false)) throw IllegalStateException()
                        integration.stopSuspendingForAllApps()
                        integration.deviceOwner.transferOwnership(it.packageName)
                    } finally {
                        updateState { it.copy(dialog = null) }
                    }
                } },
                cancel = { updateState { it.copy(dialog = null) } }
            ) }
        },
        Case.simple<_, _, OwnerState.ErrorDialog> { stateLive ->
            stateLive.map { OwnerScreen.Normal.ErrorDialog(
                message = it.message,
                close = { updateState { it.copy(dialog = null) } }
            ) }
        },
        Case.nil { flowOf(null) }
    )

    private fun getAppListDialog(
        integration: PlatformIntegration,
        state: Flow<OwnerState.AppListDialog>
    ): Flow<OwnerScreen.Normal.AppListDialog> {
        val apps = integration.getLocalApps(IdGenerator.generateId())

        return state.map { dialogState ->
            OwnerScreen.Normal.AppListDialog(
                filter = dialogState.filter,
                apps = apps.filter { app ->
                    dialogState.filter.isEmpty() ||
                            app.packageName.contains(dialogState.filter, ignoreCase = true) ||
                            app.title.contains(dialogState.filter, ignoreCase = true)
                }
            )
        }
    }
}