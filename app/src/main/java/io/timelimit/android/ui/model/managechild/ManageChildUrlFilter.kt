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
package io.timelimit.android.ui.model.managechild

import androidx.compose.material.SnackbarHostState
import androidx.lifecycle.asFlow
import io.timelimit.android.R
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserUrlFilter
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.actions.UpdateUserUrlFilterAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.android.ui.model.AuthenticationModelApi
import io.timelimit.android.ui.model.BackStackItem
import io.timelimit.android.ui.model.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

// @tag:url-filter
object ManageChildUrlFilter {
    data class Screen(
        val current: UserUrlFilter?,
        val serverSupportsFilter: Boolean,
        val save: (enabled: Boolean, allowText: String, blockText: String) -> Unit
    )

    fun handle(
        logic: AppLogic,
        authentication: AuthenticationModelApi,
        childId: String,
        stateLive: Flow<State.ManageChild.UrlFilter>,
        parentBackStackLive: Flow<List<BackStackItem>>,
        userLive: Flow<User>,
        scope: CoroutineScope
    ): Flow<io.timelimit.android.ui.model.Screen> = flow {
        val snackbarHostState = SnackbarHostState()
        val launch = ManageChildCurrentDevice.buildLaunchFunction(scope, logic.context, snackbarHostState)

        fun save(enabled: Boolean, allowText: String, blockText: String) = launch {
            if (!logic.serverApiLevelLogic.getCoroutine().hasLevelOrIsOffline(UpdateUserUrlFilterAction.MIN_SERVER_API_LEVEL)) {
                snackbarHostState.showSnackbar(logic.context.getString(R.string.url_filter_server_too_old))

                return@launch
            }

            val filter = try {
                UserUrlFilter(
                    enabled = enabled,
                    allow = UserUrlFilter.parseList(allowText),
                    block = UserUrlFilter.parseList(blockText)
                )
            } catch (ex: IllegalArgumentException) {
                snackbarHostState.showSnackbar(logic.context.getString(R.string.url_filter_invalid, ex.message))

                return@launch
            }

            authentication.doParentAuthentication()?.let { parent ->
                ApplyActionUtil.applyParentAction(
                    UpdateUserUrlFilterAction(userId = childId, filter = filter),
                    parent.authentication,
                    logic
                )

                snackbarHostState.showSnackbar(logic.context.getString(R.string.url_filter_saved))
            }
        }

        emitAll(combine(
            stateLive,
            parentBackStackLive,
            userLive,
            logic.serverApiLevelLogic.infoLive.asFlow()
        ) { state, backStack, user, serverApiLevel ->
            io.timelimit.android.ui.model.Screen.ManageChildUrlFilter(
                state,
                Screen(
                    current = user.urlFilter,
                    serverSupportsFilter = serverApiLevel.hasLevelOrIsOffline(UpdateUserUrlFilterAction.MIN_SERVER_API_LEVEL),
                    save = ::save
                ),
                backStack,
                snackbarHostState
            )
        })
    }
}
