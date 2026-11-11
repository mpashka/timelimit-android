/*
 * TimeLimit Copyright <C> 2019 - 2025 Jonas Lochmann
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

import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.backup.DatabaseBackup
import io.timelimit.android.data.devicename.DeviceName
import io.timelimit.android.data.model.ConsentFlags
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.ApplyServerDataStatus
import io.timelimit.android.sync.network.NewDeviceInfo
import io.timelimit.android.sync.network.ParentPassword
import io.timelimit.android.sync.network.ServerDataStatus
import io.timelimit.android.sync.network.StatusOfMailAddress
import io.timelimit.android.sync.network.api.ConflictHttpError
import io.timelimit.android.sync.network.api.UnauthorizedHttpError
import io.timelimit.android.ui.diagnose.exception.ExceptionUtil
import io.timelimit.android.ui.model.ActivityCommand
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.model.State
import io.timelimit.android.ui.model.flow.Case
import io.timelimit.android.ui.model.flow.splitConflated
import io.timelimit.android.ui.model.mailauthentication.MailAuthentication
import io.timelimit.android.ui.setup.SetupUnprovisionedCheck
import io.timelimit.android.ui.view.NotifyPermissionCard
import io.timelimit.android.update.UpdateIntegration
import io.timelimit.android.update.UpdateUtil
import io.timelimit.android.work.PeriodicSyncInBackgroundWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.Serializable

object SetupParentHandling {
    data class NewUserDetails(
        val parentName: String,
        val password: String,
        val password2: String
    ): Serializable {
        companion object {
            val empty = NewUserDetails("", "", "")
        }

        private val nameReady get() = parentName.isNotBlank()
        private val passwordReady get() = password == password2 && password.isNotEmpty()
        val ready get() = nameReady && passwordReady
    }

    data class ParentBaseConfiguration(
        val mail: String,
        val showLimitedProInfo: Boolean,
        val deviceName: String,
        val newUserDetails: NewUserDetails?,
        val actions: Actions
    ) {
        data class Actions(
            val newUserActions: NewUserActions?,
            val updateDeviceName: (String) -> Unit,
            val next: (() -> Unit)?
        )

        data class NewUserActions(
            val updateParentName: (String) -> Unit,
            val updatePassword: (String) -> Unit,
            val updatePassword2: (String) -> Unit,
        )
    }

    data class ParentSetupConsent(
        val backgroundSync: Boolean,
        val notificationAccess: NotifyPermissionCard.Status,
        val showEnableUpdates: Boolean,
        val enableUpdates: Boolean,
        val actions: Actions?
    ) {
        data class Actions(
            val updateBackgroundSync: (Boolean) -> Unit,
            val updateNotificationAccess: (NotifyPermissionCard.Status) -> Unit,
            val updateEnableUpdates: (Boolean) -> Unit,
            val requestNotifyPermission: () -> Unit,
            val skipNotifyPermission: () -> Unit,
            val next: (() -> Unit)?
        )
    }

    fun handle(
        logic: AppLogic,
        activityCommand: SendChannel<ActivityCommand>,
        permissionsChanged: SharedFlow<Unit>,
        stateLive: Flow<State.Setup.ParentModeSetup>,
        updateState: ((State.Setup.ParentModeSetup) -> State) -> Unit
    ): Flow<Screen> = stateLive.splitConflated(
        Case.simple<_, _, State.Setup.ParentMailAuthentication> { handleMailAuthentication(logic, scope, share(it), updateMethod(updateState)) },
        Case.simple<_, _, State.Setup.SignUpBlocked> { handleSignUpBlocked(it) },
        Case.simple<_, _, State.Setup.SignInWrongMailAddress> { handleSignUpWrongMailAddress(it) },
        Case.simple<_, _, State.Setup.ConfirmNewParentAccount> { handleConfirmNewParentAccount(logic, it, updateMethod(updateState)) },
        Case.simple<_, _, State.Setup.ParentBaseConfiguration> { handleParentBaseConfiguration(it, updateMethod(updateState)) },
        Case.simple<_, _, State.Setup.ParentConsent> { handleParentConsent(logic, scope, activityCommand, permissionsChanged, share(it), updateMethod(updateState)) },
    )

    private fun handleMailAuthentication(
        logic: AppLogic,
        scope: CoroutineScope,
        stateLive: SharedFlow<State.Setup.ParentMailAuthentication>,
        updateState: ((State.Setup.ParentMailAuthentication) -> State) -> Unit
    ): Flow<Screen> = flow {
        val snackbarHostState = SnackbarHostState()

        val nestedLice = MailAuthentication.handle(
            logic = logic,
            scope = scope,
            snackbarHostState = snackbarHostState,
            stateLive = stateLive.map { it.content },
            updateState = { modifier -> updateState { it.copy(content = modifier(it.content)) } },
            processAuthToken = { token ->
                val status = logic.serverLogic.getServerConfigCoroutine().api.getStatusByMailToken(token)

                val deviceName = Threads.database.executeAndWait {
                    DeviceName.getDeviceNameSync(logic.context)
                }

                if (status.status == StatusOfMailAddress.MailAddressWithFamily) updateState {
                    val prev = it.copy(content = MailAuthentication.State.initial)

                    State.Setup.ParentBaseConfiguration(
                        previousState = prev,
                        previousParentMailAuthentication = prev,
                        mailAuthToken = token,
                        mailStatus = status,
                        deviceName = deviceName,
                        newUser = null
                    )
                } else if (status.canCreateFamily) updateState { State.Setup.ConfirmNewParentAccount(it, token, status) }
                else updateState { State.Setup.SignUpBlocked(it.copy(content = MailAuthentication.State.initial)) }
            }
        )

        emitAll(combine(stateLive, nestedLice) { state, nested ->
            Screen.SetupParentMailAuthentication(state, nested, snackbarHostState)
        })
    }

    private fun handleSignUpBlocked(
        stateLive: Flow<State.Setup.SignUpBlocked>
    ): Flow<Screen> = stateLive.map { Screen.SignupBlocked(it) }

    private fun handleSignUpWrongMailAddress(
        stateLive: Flow<State.Setup.SignInWrongMailAddress>
    ): Flow<Screen> = stateLive.map { Screen.SignInWrongMailAddress(it) }

    private fun handleConfirmNewParentAccount(
        logic: AppLogic,
        stateLive: Flow<State.Setup.ConfirmNewParentAccount>,
        updateState: ((State.Setup.ConfirmNewParentAccount) -> State) -> Unit
    ): Flow<Screen> = flow {
        val deviceName = Threads.database.executeAndWait {
            DeviceName.getDeviceNameSync(logic.context)
        }

        val confirm = { updateState {
            State.Setup.ParentBaseConfiguration(
                previousState = it,
                previousParentMailAuthentication = it.previousParentMailAuthentication,
                mailAuthToken = it.mailAuthToken,
                mailStatus = it.mailStatus,
                deviceName = deviceName,
                newUser = NewUserDetails.empty
            )
        } }

        val reject = { updateState {
            State.Setup.SignInWrongMailAddress(it)
        } }

        emitAll(stateLive.map { Screen.ConfirmNewParentAccount(it, confirm = confirm, reject = reject) })
    }

    private fun handleParentBaseConfiguration(
        stateLive: Flow<State.Setup.ParentBaseConfiguration>,
        updateState: ((State.Setup.ParentBaseConfiguration) -> State) -> Unit
    ): Flow<Screen> {
        fun deviceReady(state: State.Setup.ParentBaseConfiguration) = state.deviceName.isNotBlank()
        fun newUserReady(state: State.Setup.ParentBaseConfiguration) = state.newUser == null || state.newUser.ready

        fun ready(state: State.Setup.ParentBaseConfiguration) = newUserReady(state) && deviceReady(state)

        val next = { updateState {
            if (ready(it)) State.Setup.ParentConsent(
                it,
                backgroundSync = false,
                notificationAccess = NotifyPermissionCard.Status.Unknown,
                enableUpdates = false,
                error = null
            )
            else it
        }}

        return stateLive.map { state ->
            val newUserDetails = if (state.newUser != null) NewUserDetails(
                parentName = state.newUser.parentName,
                password = state.newUser.password,
                password2 = state.newUser.password2
            ) to ParentBaseConfiguration.NewUserActions(
                updateParentName = { v -> updateState { s -> s.copy(newUser = s.newUser?.copy(parentName = v)) } },
                updatePassword = { v -> updateState { s -> s.copy(newUser = s.newUser?.copy(password = v)) } },
                updatePassword2 = { v -> updateState { s -> s.copy(newUser = s.newUser?.copy(password2 = v)) } }
            ) else null

            val actions = ParentBaseConfiguration.Actions(
                newUserActions = newUserDetails?.second,
                updateDeviceName = { v -> updateState { it.copy(deviceName = v) } },
                next = if (ready(state)) next else null
            )

            val content = ParentBaseConfiguration(
                mail = state.mailStatus.mail,
                showLimitedProInfo = !state.mailStatus.alwaysPro,
                deviceName = state.deviceName,
                newUserDetails = newUserDetails?.first,
                actions = actions
            )

            Screen.ParentBaseConfiguration(
                state = state,
                content = content
            )
        }
    }

    private fun handleParentConsent(
        logic: AppLogic,
        scope: CoroutineScope,
        activityCommand: SendChannel<ActivityCommand>,
        permissionsChanged: SharedFlow<Unit>,
        stateLive: SharedFlow<State.Setup.ParentConsent>,
        updateState: ((State.Setup.ParentConsent) -> State) -> Unit
    ): Flow<Screen> = flow {
        val isWorkingLive = MutableStateFlow(false)
        val snackbarHostState = SnackbarHostState()
        var lastError: Job? = null

        val actions = ParentSetupConsent.Actions(
            updateBackgroundSync = { v -> updateState { it.copy(backgroundSync = v) } },
            updateNotificationAccess = { v -> updateState { it.copy(notificationAccess = v) } },
            updateEnableUpdates = { v -> updateState { it.copy(enableUpdates = v) } },
            requestNotifyPermission = {
                activityCommand.trySend(ActivityCommand.RequestNotifyPermission)
            },
            skipNotifyPermission = { updateState { it.copy(notificationAccess = NotifyPermissionCard.Status.SkipGrant) } },
            next = { scope.launch { if (isWorkingLive.compareAndSet(expect = false, update = true)) try {
                val state = stateLive.first()
                val database = logic.database
                val serverConfig = logic.serverLogic.getServerConfigCoroutine()
                val api = serverConfig.api

                lastError?.cancel(); lastError = null

                val deviceModelName = Threads.database.executeAndWait {
                    DeviceName.getDeviceNameSync(logic.context)
                }

                val mailToken = state.baseConfig.mailAuthToken
                val parentDevice = NewDeviceInfo(model = deviceModelName)
                val deviceName = state.baseConfig.deviceName

                val result = if (state.baseConfig.newUser != null) {
                    val parentPassword = ParentPassword.createCoroutine(state.baseConfig.newUser.password, null)
                    val parentName = state.baseConfig.newUser.parentName
                    val timeZone = logic.timeApi.getSystemTimeZone().id

                    val registerResponse = api.createFamilyByMailToken(
                        mailToken = mailToken,
                        parentPassword = parentPassword,
                        parentDevice = parentDevice,
                        deviceName = deviceName,
                        parentName = parentName,
                        timeZone = timeZone
                    )

                    Result(
                        deviceAuthToken = registerResponse.deviceAuthToken,
                        ownDeviceId = registerResponse.ownDeviceId,
                        serverDataStatus = registerResponse.data
                    )
                } else {
                    val signInResponse = api.signInToFamilyByMailToken(
                        mailToken = mailToken,
                        parentDevice = parentDevice,
                        deviceName = deviceName
                    )

                    Result(
                        deviceAuthToken = signInResponse.deviceAuthToken,
                        ownDeviceId = signInResponse.ownDeviceId,
                        serverDataStatus = signInResponse.data
                    )
                }

                Threads.database.executeAndWait {
                    database.runInTransaction {
                        SetupUnprovisionedCheck.checkSync(logic.database)

                        database.deleteAllData()

                        database.config().setCustomServerUrlSync(serverConfig.customServerUrl)
                        database.config().setOwnDeviceIdSync(result.ownDeviceId)
                        database.config().setDeviceAuthTokenSync(result.deviceAuthToken)
                        database.config().setEnableBackgroundSync(state.backgroundSync)

                        database.config().setConsentFlagSync(
                            ConsentFlags.BLOCK_USER_SWITCH_BY_DEFAULT,
                            true
                        )

                        ApplyServerDataStatus.applyServerDataStatusSync(result.serverDataStatus, logic.database, logic.platformIntegration)
                    }
                }

                DatabaseBackup.with(logic.context).tryCreateDatabaseBackupAsync()

                if (state.backgroundSync) {
                    PeriodicSyncInBackgroundWorker.enable(logic.context)
                }

                UpdateUtil.setEnableChecks(logic.context, state.enableUpdates)

                updateState { State.LaunchState }
            } catch (ex: ConflictHttpError) {
                snackbarHostState.showSnackbar(logic.context.getString(R.string.error_server_rejected))

                updateState { it.baseConfig.previousParentMailAuthentication }
            } catch (ex: UnauthorizedHttpError) {
                snackbarHostState.showSnackbar(logic.context.getString(R.string.error_server_rejected))

                updateState { it.baseConfig.previousParentMailAuthentication }
            } catch (ex: Exception) {
                lastError = launch {
                    val result = snackbarHostState.showSnackbar(
                        logic.context.getString(R.string.error_network),
                        logic.context.getString(R.string.generic_show_details),
                        SnackbarDuration.Long
                    )

                    if (result == SnackbarResult.ActionPerformed) updateState {
                        it.copy(error = ExceptionUtil.format(ex))
                    }
                }
            } finally {
                isWorkingLive.value = false
            } } }
        )

        scope.launch { permissionsChanged.collect {
            updateState {
                it.copy(notificationAccess = NotifyPermissionCard.updateStatus(it.notificationAccess, logic.context))
            }
        } }

        val showEnableUpdates = UpdateIntegration.doesSupportUpdates(logic.context)

        emitAll(combine(stateLive, isWorkingLive) { state, isWorking ->
            val notificationAccess = NotifyPermissionCard.updateStatus(state.notificationAccess, logic.context)

            val content = ParentSetupConsent(
                backgroundSync = state.backgroundSync,
                notificationAccess = notificationAccess,
                enableUpdates = state.enableUpdates,
                showEnableUpdates = showEnableUpdates,
                actions = if (isWorking) null else actions.copy(
                    next = if (NotifyPermissionCard.canProceed(notificationAccess)) actions.next else null
                )
            )

            Screen.ParentSetupConsent(
                state = state,
                content = content,
                snackbarHostState = snackbarHostState,
                errorDialog = state.error?.let { error ->
                    Pair(error) { updateState { it.copy(error = null) } }
                }
            )
        })
    }

    internal data class Result(
        val deviceAuthToken: String,
        val ownDeviceId: String,
        val serverDataStatus: ServerDataStatus
    )
}