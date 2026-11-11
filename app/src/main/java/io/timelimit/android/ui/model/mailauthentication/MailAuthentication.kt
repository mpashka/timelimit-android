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
package io.timelimit.android.ui.model.mailauthentication

import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import io.timelimit.android.R
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.network.api.*
import io.timelimit.android.ui.diagnose.exception.ExceptionUtil
import io.timelimit.android.util.MailValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Serializable

object MailAuthentication {
    sealed class State: java.io.Serializable {
        companion object {
            val initial = State.EnterMailAddress()
        }

        abstract val error: ErrorDialog?
        abstract fun withError(error: ErrorDialog?): State

        sealed class InitialState: State()

        data class EnterMailAddress(val mail: String = "", override val error: ErrorDialog? = null): InitialState() {
            override fun withError(error: ErrorDialog?) = copy(error = error)
        }

        data class ConfirmMailSending(val mail: String, override val error: ErrorDialog? = null): InitialState() {
            override fun withError(error: ErrorDialog?) = copy(error = error)
        }

        data class PickMailAddress(
            val originalMail: String,
            val options: List<String>,
            val selectedIndex: Int? = null,
            override val error: ErrorDialog? = null
        ): State() {
            init { if (selectedIndex != null) {
                if (selectedIndex < 0 || selectedIndex >= options.size) throw IllegalArgumentException()
            } }

            override fun withError(error: ErrorDialog?) = copy(error = error)
        }

        data class EnterReceivedCode(
            val mail: String,
            val serverToken: String,
            val codeInput: String,
            override val error: ErrorDialog?,
            val initialState: InitialState
        ): State() {
            override fun withError(error: ErrorDialog?) = copy(error = error)
        }
    }

    sealed class Screen {
        abstract val error: Error?

        class EnterMailAddress(
            val mail: String,
            override val error: Error?,
            val actions: Actions?
        ): Screen() {
            class Actions(
                val updateMailAddress: (String) -> Unit,
                val confirm: () -> Unit
            )
        }

        class ConfirmMailSending(
            val mail: String,
            override val error: Error?,
            val confirm: (() -> Unit)?
        ): Screen()

        class PickMailAddress(
            val options: List<String>,
            val selectedIndex: Int?,
            override val error: Error?,
            val actions: Actions?
        ): Screen() {
            class Actions(
                val updateSelectedIndex: (Int) -> Unit,
                val back: () -> Unit,
                val confirm: (() -> Unit)?,
            )
        }

        class EnterReceivedCode(
            val mail: String,
            val codeInput: String,
            override val error: Error?,
            val actions: Actions?
        ): Screen() {
            class Actions(
                val updateCodeInput: (String) -> Unit,
                val back: (() -> Unit)?,
                val confirmCodeInput: () -> Unit
            )
        }

        data class Error(val dialog: ErrorDialog, val close: () -> Unit)
    }

    sealed class ErrorDialog: Serializable {
        object RateLimit: ErrorDialog()
        object BlockedMailServer: ErrorDialog()
        object MailAddressNotAllowed: ErrorDialog()
        object BlockedForIntegrityReasons: ErrorDialog()
        object TemporarilyBlockedMailServer: ErrorDialog()
        data class ExceptionDetails(val message: String): ErrorDialog()
    }

    fun handle(
        logic: AppLogic,
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        stateLive: Flow<State>,
        updateState: ((State) -> State) -> Unit,
        processAuthToken: suspend (String) -> Unit
    ): Flow<Screen> {
        var lastErrorJob: Job? = null
        val isWorkingLive = MutableStateFlow(false)

        fun showGenericExceptionMessage(ex: Exception) {
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

                if (result == SnackbarResult.ActionPerformed) {
                    val message = ExceptionUtil.format(ex)

                    updateState { it.withError(error = ErrorDialog.ExceptionDetails(message)) }
                }
            }
        }

        suspend fun confirmMailAddressInternal(mail: String, initialState: State.InitialState) {
            try {
                val serverConfiguration = logic.serverLogic.getServerConfigCoroutine()

                val serverToken = serverConfiguration.api.sendMailLoginCode(
                    mail = mail,
                    locale = logic.context.resources.configuration.locale.language,
                    deviceAuthToken = serverConfiguration.deviceAuthToken.ifEmpty { null }
                )

                updateState { State.EnterReceivedCode(
                    mail = mail,
                    serverToken = serverToken,
                    codeInput = "",
                    error = null,
                    initialState = initialState
                ) }
            } catch (ex: TooManyRequestsHttpError) {
                updateState { it.withError(error = ErrorDialog.RateLimit) }
            } catch (ex: MailServerBlacklistedException) {
                updateState { it.withError(error = ErrorDialog.BlockedMailServer) }
            } catch (ex: MailAddressNotWhitelistedException) {
                updateState { it.withError(error = ErrorDialog.MailAddressNotAllowed) }
            } catch (ex: MailLoginBlockedForIntegrityReasonsException) {
                updateState { it.withError(error = ErrorDialog.BlockedForIntegrityReasons) }
            } catch (ex: MailServerTemporarilyBlacklistedException) {
                updateState { it.withError(error = ErrorDialog.TemporarilyBlockedMailServer) }
            } catch (ex: Exception) {
                showGenericExceptionMessage(ex)
            }
        }

        fun confirmMailAddress(mail: String, initialState: State.InitialState) {
            scope.launch {
                if (isWorkingLive.compareAndSet(expect = false, update = true)) try {
                    lastErrorJob?.cancel()

                    confirmMailAddressInternal(mail, initialState)
                } finally {
                    isWorkingLive.value = false
                }
            }
        }

        return combine(stateLive, isWorkingLive) { state, isWorking ->
            val error = state.error?.let {
                Screen.Error(it) { updateState{ it.withError(error = null) } }
            }

            when (state) {
                is State.EnterMailAddress -> {
                    val update: ((State.EnterMailAddress) -> State) -> Unit = { modifier ->
                        updateState { oldState ->
                            if (oldState is State.EnterMailAddress) modifier(oldState)
                            else oldState
                        }
                    }

                    val actions = Screen.EnterMailAddress.Actions(
                        updateMailAddress = { mail -> update { it.copy(mail = mail) } },
                        confirm = {
                            val mail = state.mail

                            scope.launch {
                                if (isWorkingLive.compareAndSet(expect = false, update = true)) try {
                                    lastErrorJob?.cancel()

                                    delay(100)

                                    when (val validation = MailValidation.validate(mail)) {
                                        MailValidation.Result.Valid -> confirmMailAddressInternal(mail = mail, initialState = State.EnterMailAddress())
                                        MailValidation.Result.Invalid -> lastErrorJob = scope.launch {
                                            snackbarHostState.showSnackbar(
                                                logic.context.getString(R.string.authenticate_by_mail_snackbar_invalid_address)
                                            )
                                        }
                                        is MailValidation.Result.InvalidWithSuggestion -> {
                                            update { it.copy(mail = validation.suggestion) }

                                            lastErrorJob = scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    logic.context.getString(R.string.authenticate_by_mail_snackbar_invalid_address_suggest)
                                                )
                                            }
                                        }
                                        is MailValidation.Result.ValidWithSuggestion -> update {
                                            State.PickMailAddress(mail, listOf(validation.suggestion, mail))
                                        }
                                    }
                                } finally {
                                    isWorkingLive.value = false
                                }
                            }
                        }
                    )

                    Screen.EnterMailAddress(
                        mail = state.mail,
                        error = error,
                        actions = if (isWorking) null else actions
                    )
                }
                is State.ConfirmMailSending -> {
                    val confirm: () -> Unit = {
                        confirmMailAddress(state.mail, State.ConfirmMailSending(mail = state.mail))
                    }

                    Screen.ConfirmMailSending(
                        mail = state.mail,
                        error = error,
                        confirm = if (isWorking) null else confirm
                    )
                }
                is State.PickMailAddress -> {
                    val update: ((State.PickMailAddress) -> State) -> Unit = { modifier ->
                        updateState { oldState ->
                            if (oldState is State.PickMailAddress) modifier(oldState)
                            else oldState
                        }
                    }

                    val actions = Screen.PickMailAddress.Actions(
                        confirm = if (state.selectedIndex != null) ({
                            confirmMailAddress(state.options[state.selectedIndex], State.EnterMailAddress())
                        }) else null,
                        back = { updateState { State.EnterMailAddress(state.originalMail) } },
                        updateSelectedIndex = { selectedIndex -> update { it.copy(selectedIndex = selectedIndex) } }
                    )

                    Screen.PickMailAddress(
                        options = state.options,
                        selectedIndex = state.selectedIndex,
                        error = error,
                        actions = if (isWorking) null else actions
                    )
                }
                is State.EnterReceivedCode -> {
                    val update: ((State.EnterReceivedCode) -> State) -> Unit = { modifier ->
                        updateState { oldState ->
                            if (oldState is State.EnterReceivedCode) modifier(oldState)
                            else oldState
                        }
                    }

                    val actions = Screen.EnterReceivedCode.Actions(
                        updateCodeInput = { code ->
                            if (!isWorkingLive.value) update { it.copy(codeInput = code) }
                        },
                        back = if (state.initialState is State.EnterMailAddress) ({
                            updateState {
                                state.initialState.copy(mail = state.mail)
                            }
                        }) else null,
                        confirmCodeInput = { scope.launch {
                            if (isWorkingLive.compareAndSet(expect = false, update = true)) try {
                                lastErrorJob?.cancel()

                                val serverConfiguration = logic.serverLogic.getServerConfigCoroutine()

                                val authToken = serverConfiguration.api.signInByMailCode(
                                    mailLoginToken = state.serverToken,
                                    code = state.codeInput
                                )

                                processAuthToken(authToken)
                            } catch (ex: ForbiddenHttpError) {
                                lastErrorJob = scope.launch {
                                    snackbarHostState.showSnackbar(
                                        logic.context.getString(R.string.authenticate_by_mail_snackbar_wrong_code)
                                    )
                                }
                            } catch (ex: GoneHttpError) {
                                snackbarHostState.showSnackbar(
                                    logic.context.getString(R.string.authenticate_by_mail_snackbar_wrong_code)
                                )

                                // go back to first step
                                update { it.initialState }
                            } catch (ex: Exception) {
                                showGenericExceptionMessage(ex)
                            } finally {
                                isWorkingLive.value = false
                            }
                        } }
                    )

                    Screen.EnterReceivedCode(
                        mail = state.mail,
                        codeInput = state.codeInput,
                        error = error,
                        actions = if (isWorking) null else actions
                    )
                }
            }
        }
    }
}