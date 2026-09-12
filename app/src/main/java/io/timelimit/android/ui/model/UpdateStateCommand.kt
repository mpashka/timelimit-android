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
package io.timelimit.android.ui.model

import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.ui.model.main.OverviewHandling
import io.timelimit.android.ui.model.managechild.ManageChildCategoryList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

sealed class UpdateStateCommand {
    companion object {
        private const val LOG_TAG = "UpdateStateCommand"
    }

    abstract fun transform(state: State): State?

    fun applyTo(state: MutableStateFlow<State>) {
        state.update { oldState ->
            transform(oldState) ?: oldState.also {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "$this.transform() did not transform state")
                }
            }
        }
    }

    object Reset: UpdateStateCommand() {
        override fun transform(state: State) = State.LaunchState
    }
    object BackToPreviousScreen: UpdateStateCommand() {
        override fun transform(state: State): State? = state.previous
    }

    object Overview {
        object LaunchAbout: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.About(state)
                else null
        }
        object AddUser: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.AddUser(state)
                else null
        }
        data class ManageChild(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.ManageChild.Main(state, childId)
                else null
        }
        data class ManageParent(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.ManageParent.Main(state, childId)
                else null
        }
        data class ManageDevice(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.ManageDevice.Main(state, childId)
                else null
        }
        object SetupDevice: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.SetupDevice(state)
                else null
        }

        object Uninstall: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.Uninstall(state)
                else null
        }

        object DeleteAccount: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) State.DeleteAccount(state)
                else null
        }

        object ShowAllUsers: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) state.copy(
                    state = state.state.copy(showAllUsers = true)
                )
                else null
        }

        class ShowMoreDevices(val deviceList: OverviewHandling.OverviewState.DeviceList): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Overview) state.copy(
                    state = state.state.copy(visibleDevices = deviceList)
                )
                else null
        }
    }
    object About {
        object Diagnose: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.About) State.DiagnoseScreen.Main(state)
                else null
        }

        object Purchase: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.About) State.Purchase.Purchase(state)
                else null
        }

        object StayAwesome: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.About) State.Purchase.StayAwesome(state)
                else null
        }
    }
    object AddUser {
        object Leave: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.AddUser) state.previous
                else null
        }
    }

    object ManageDevice {
        data class User(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageDevice.Main) State.ManageDevice.User(state)
                else null
        }
        data class Permissions(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageDevice.Main) State.ManageDevice.Permissions(state)
                else null
        }
        data class Features(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageDevice.Main) State.ManageDevice.Features(state)
                else null
        }
        data class Advanced(val childId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageDevice.Main) State.ManageDevice.Advanced(state)
                else null
        }
        object Leave: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageDevice) state.previousOverview
                else null
        }
        class EnterFromDeviceSetup(val deviceId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.SetupDevice) State.ManageDevice.Main(
                    deviceId = deviceId,
                    previousOverview = state.previousOverview
                )
                else null
        }
    }
    object ManageChild {
        object Apps: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.Apps(state)
                else null
        }
        object Advanced: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.Advanced(state)
                else null
        }
        object ManageCurrentDevice: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Advanced) State.ManageChild.AdvancedCurrentDevice(state)
                else null
        }
        object Contacts: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.Contacts(state)
                else null
        }
        object UrlFilter: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Advanced) State.ManageChild.UrlFilter(state)
                else null
        }
        object AppUsage: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.AppUsage(state)
                else null
        }
        object UsageHistory: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.UsageHistory(state)
                else null
        }
        object Tasks: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) State.ManageChild.Tasks(state)
                else null
        }

        class Category(val childId: String, val categoryId: String): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main && state.childId == childId) State.ManageChild.ManageCategory.Main(previousChild = state, categoryId = categoryId)
                else null
        }

        object BlockedTimes: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.ManageCategory.Main) State.ManageChild.ManageCategory.BlockedTimes(state)
                else null
        }

        object CategoryAdvanced: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.ManageCategory.Main) State.ManageChild.ManageCategory.Advanced(state)
                else null
        }

        object LeaveCategory: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.ManageCategory) state.previousChild
                else null
        }

        object LeaveChild: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild) state.previousOverview
                else null
        }

        data class RememberSpecialMode(val specialMode: ManageChildCategoryList.CategorySpecialMode.NotNone): UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageChild.Main) state.copy(lastSpecialMode = specialMode)
                else null
        }
    }
    object ManageParent {
        object ChangePassword: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.Main) State.ManageParent.ChangePassword(state)
                else null
        }
        object RestorePassword: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.Main) State.ManageParent.RestorePassword(state)
                else null
        }
        object LinkMail: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.Main) State.ManageParent.LinkMail(state)
                else null
        }
        object U2F: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.Main) State.ManageParent.U2F(state)
                else null
        }
        object Leave: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent) when (state) {
                    is State.ManageParent.Main -> state.previous
                    is State.ManageParent.ChangePassword -> state.previousParent.previous
                    is State.ManageParent.U2F -> state.previousParent.previous
                    is State.ManageParent.LinkMail -> state.previousParent.previous
                    is State.ManageParent.RestorePassword -> state.previousParent.previous
                }
                else null
        }

        object LeaveRestorePassword: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.RestorePassword) state.previous
                else null
        }

        object LeaveChangePassword: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.ChangePassword) state.previous
                else null
        }

        object LeaveLinkMail: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.ManageParent.LinkMail) state.previous
                else null
        }
    }
    object Diagnose {
        object Battery: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.Battery(state)
                else null
        }
        object Clock: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.Clock(state)
                else null
        }
        object Connection: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.Connection(state)
                else null
        }
        object Crypto: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.Crypto(state)
                else null
        }
        object ExperimentalFlags: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.ExperimentalFlags(state)
                else null
        }
        object ExitReasons: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.ExitReasons(state)
                else null
        }
        object ForegroundApp: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.ForegroundApp(state)
                else null
        }
        object Sync: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.DiagnoseScreen.Main) State.DiagnoseScreen.Sync(state)
                else null
        }
    }
    object Setup {
        object Help: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Setup.SetupTerms) State.Setup.SetupHelpInfo(state)
                else null
        }

        object SelectMode: UpdateStateCommand() {
            override fun transform(state: State): State? =
                if (state is State.Setup.SetupHelpInfo) State.Setup.SelectMode(state)
                else null
        }
    }

    class RecoverPassword(val parentId: String): UpdateStateCommand() {
        override fun transform(state: State): State? =
            state.find { it is State.Overview }?.let { overview ->
                State.ManageParent.Main(
                    parentId = parentId,
                    previous = overview as State.Overview
                )
            }
    }
}