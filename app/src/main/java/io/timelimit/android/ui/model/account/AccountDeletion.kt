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
package io.timelimit.android.ui.model.account

import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.lifecycle.asFlow
import io.timelimit.android.R
import io.timelimit.android.data.model.UserType
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.network.api.GoneHttpError
import io.timelimit.android.sync.network.api.HttpError
import io.timelimit.android.ui.diagnose.exception.ExceptionUtil
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.model.State
import io.timelimit.android.ui.model.flow.Case
import io.timelimit.android.ui.model.flow.splitConflated
import io.timelimit.android.ui.model.mailauthentication.MailAuthentication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Serializable

object AccountDeletion {
    sealed class MyState: Serializable {
        data class Preparing(
            val mailToAuthToken: Map<String, String> = emptyMap(),
            val didConfirmPremiumLoss: Boolean = false,
            val currentMailAuthentication: Map<String, MailAuthentication.State> = emptyMap(),
            val errorDialog: String? = null
        ): MyState()

        object Done: MyState()
    }

    sealed class MyScreen {
        object NoAccount: MyScreen()

        class MailConfirmation(val content: MailAuthentication.Screen): MyScreen()

        class FinalConfirmation(
            val didConfirmPremiumLoss: Boolean,
            val actions: Actions?,
            val errorDialog: ErrorDialog?
        ): MyScreen() {
            data class Actions(
                val updateConfirmPremiumLoss: (Boolean) -> Unit,
                val finalConfirmation: (() -> Unit)?
            )

            data class ErrorDialog(val message: String, val close: () -> Unit)
        }

        object Done: MyScreen()
    }

    internal sealed class ScreenOrMail {
        data class Screen(val screen: MyScreen): ScreenOrMail()
        data class Mail(val mail: String): ScreenOrMail()
    }

    fun handle(
        logic: AppLogic,
        scope: CoroutineScope,
        stateLive: SharedFlow<State.DeleteAccount>,
        updateState: ((State.DeleteAccount) -> State) -> Unit
    ): Flow<Screen> {
        val snackbarHostState = SnackbarHostState()

        val screenLive = handleInternal(
            logic,
            snackbarHostState,
            scope,
            stateLive.map { it.content }
        ) { modifier -> updateState { oldState ->
            oldState.copy(content = modifier(oldState.content))
        } }

        return combine(stateLive, screenLive) { state, screen ->
            Screen.DeleteRegistration(state, screen, snackbarHostState)
        }
    }

    private fun handleInternal(
        logic: AppLogic,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope,
        stateLive: Flow<MyState>,
        updateState: ((MyState) -> MyState) -> Unit
    ): Flow<MyScreen> = stateLive.splitConflated(
        Case.simple<_, _, MyState.Preparing> {
            handlePreparing(
                logic,
                snackbarHostState,
                scope,
                share(it),
                updateMethod(updateState)
            )
        },
        Case.simple<_, _, MyState.Done> { flowOf(MyScreen.Done) },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun handlePreparing(
        logic: AppLogic,
        snackbarHostState: SnackbarHostState,
        scope: CoroutineScope,
        stateLive: SharedFlow<MyState.Preparing>,
        updateState: ((MyState.Preparing) -> MyState) -> Unit
    ): Flow<MyScreen> {
        var lastErrorJob: Job? = null

        val isWorkingLive = MutableStateFlow(false)

        val isLocalModeLive = logic.fullVersion.isLocalMode.asFlow()

        val linkedParentMailAddressesLive = logic.database.user().getAllUsersFlow().map { users ->
            users
                .filter { it.type == UserType.Parent }
                .map { it.mail }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        return combine(
            stateLive, isLocalModeLive, linkedParentMailAddressesLive, isWorkingLive
        ) { state, isLocalMode, linkedParentMailAddresses, isWorking ->
            val remainingMailAddresses = linkedParentMailAddresses - state.mailToAuthToken.keys
            val remainingMailAddress = remainingMailAddresses.minOrNull()

            if (isLocalMode) ScreenOrMail.Screen(MyScreen.NoAccount)
            else if (remainingMailAddress != null) ScreenOrMail.Mail(remainingMailAddress)
            else {
                val finalConfirmation: () -> Unit = { scope.launch {
                    lastErrorJob?.cancel()

                    if (isWorkingLive.compareAndSet(expect = false, update = true)) try {
                        val serverConfiguration = logic.serverLogic.getServerConfigCoroutine()

                        serverConfiguration.api.requestAccountDeletion(
                            deviceAuthToken = serverConfiguration.deviceAuthToken,
                            mailAuthTokens = state.mailToAuthToken.values.toList()
                        )

                        updateState { MyState.Done }
                    } catch (ex: GoneHttpError) {
                        lastErrorJob = scope.launch {
                            snackbarHostState.showSnackbar(
                                logic.context.getString(R.string.error_server_client_deprecated)
                            )
                        }
                    } catch (ex: Exception) {
                        lastErrorJob = scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                logic.context.getString(
                                    when (ex) {
                                        is HttpError -> R.string.error_server_rejected
                                        is IOException -> R.string.error_network
                                        else -> R.string.error_general
                                    }
                                ),
                                actionLabel = logic.context.getString(R.string.generic_show_details),
                                duration = SnackbarDuration.Short
                            )

                            if (result == SnackbarResult.ActionPerformed) updateState {
                                it.copy(errorDialog = ExceptionUtil.format(ex))
                            }
                        }
                    } finally {
                        isWorkingLive.value = false
                    }
                } }

                ScreenOrMail.Screen(MyScreen.FinalConfirmation(
                    didConfirmPremiumLoss = state.didConfirmPremiumLoss,
                    actions = if (isWorking) null else MyScreen.FinalConfirmation.Actions(
                        updateConfirmPremiumLoss = { updateState { oldState -> oldState.copy(didConfirmPremiumLoss = it) } },
                        finalConfirmation = if (state.didConfirmPremiumLoss) finalConfirmation else null
                    ),
                    errorDialog = state.errorDialog?.let { message ->
                        MyScreen.FinalConfirmation.ErrorDialog(
                            message = message,
                            close = { updateState { it.copy(errorDialog = null) } }
                        )
                    }
                ))
            }
        }.distinctUntilChanged().transformLatest { screenOrMail ->
            when (screenOrMail) {
                is ScreenOrMail.Screen -> emit(screenOrMail.screen)
                is ScreenOrMail.Mail -> emitAll(
                    MailAuthentication.handle(
                        logic,
                        scope,
                        snackbarHostState,
                        stateLive.map { state ->
                            state.currentMailAuthentication[screenOrMail.mail] ?: MailAuthentication.State.ConfirmMailSending(screenOrMail.mail)
                        },
                        updateState = { modifier ->
                            updateState { oldState ->
                                oldState.copy(
                                    currentMailAuthentication = oldState.currentMailAuthentication + Pair(
                                        screenOrMail.mail,
                                        modifier(
                                            oldState.currentMailAuthentication[screenOrMail.mail] ?: MailAuthentication.State.ConfirmMailSending(screenOrMail.mail)
                                        )
                                    )
                                )
                            }
                        },
                        processAuthToken = { mailAuthToken ->
                            updateState { oldState ->
                                oldState.copy(
                                    mailToAuthToken = oldState.mailToAuthToken + Pair(screenOrMail.mail, mailAuthToken)
                                )
                            }
                        }
                    ).map { MyScreen.MailConfirmation(it) }
                )
            }
        }
    }
}