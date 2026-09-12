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
package io.timelimit.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentManager
import io.timelimit.android.ui.account.DeleteRegistrationScreen
import io.timelimit.android.ui.authentication.AuthenticateByMailScreen
import io.timelimit.android.ui.diagnose.deviceowner.DeviceOwnerScreen
import io.timelimit.android.ui.manage.category.blocked_times.BlockedTimesScreen
import io.timelimit.android.ui.manage.child.ManageChildScreen
import io.timelimit.android.ui.manage.child.primarydevice.CurrentDeviceScreen
import io.timelimit.android.ui.manage.child.usagehistory.UsageHistoryScreen
import io.timelimit.android.ui.manage.child.urlfilter.UrlFilterScreen
import io.timelimit.android.ui.manage.child.appusage.AppUsageScreen
import io.timelimit.android.ui.manage.device.manage.permission.ManageDevicePermissionScreen
import io.timelimit.android.ui.manage.device.manage.user.ManageDeviceUserScreen
import io.timelimit.android.ui.model.Screen
import io.timelimit.android.ui.overview.overview.OverviewScreen
import io.timelimit.android.ui.setup.selectmode.SelectConnectedModeScreen
import io.timelimit.android.ui.setup.SetupDevicePermissionsScreen
import io.timelimit.android.ui.setup.parent.ConfirmNewParentAccount
import io.timelimit.android.ui.setup.parent.ParentBaseConfiguration
import io.timelimit.android.ui.setup.parent.ParentSetupConsent
import io.timelimit.android.ui.setup.parent.SignInWrongMailAddress
import io.timelimit.android.ui.setup.parent.SignupBlockedScreen
import io.timelimit.android.ui.setup.privacy.SetupConnectedModePrivacyScreen
import io.timelimit.android.ui.setup.selectmode.SelectModeScreen

@Composable
fun ScreenMultiplexer(
    screen: Screen?,
    fragmentManager: FragmentManager,
    fragmentIds: MutableSet<Int>,
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues
) {
    when (screen) {
        null -> {/* nothing to do */ }
        is Screen.FragmentScreen -> FragmentScreen(screen, fragmentManager, fragmentIds, modifier = modifier.padding(paddingValues))
        is Screen.OverviewScreen -> OverviewScreen(screen.content, modifier = modifier, paddingValues = paddingValues)
        is Screen.ManageDeviceUserScreen -> ManageDeviceUserScreen(screen.items, screen.actions, screen.overlay, modifier.padding(paddingValues))
        is Screen.DeviceOwnerScreen -> DeviceOwnerScreen(screen.content, modifier = modifier.padding(paddingValues))
        is Screen.SetupDevicePermissionsScreen -> SetupDevicePermissionsScreen(screen, modifier.padding(paddingValues))
        is Screen.ManageDevicePermissions -> ManageDevicePermissionScreen(screen.content, modifier.padding(paddingValues))
        is Screen.SetupConnectModePrivacyScreen -> SetupConnectedModePrivacyScreen(screen.customServerDomain, screen.accept, modifier.padding(paddingValues))
        is Screen.SetupSelectConnectedModeScreen -> SelectConnectedModeScreen(mailLogin = screen.mailLogin, codeLogin = screen.codeLogin, modifier = modifier.padding(paddingValues))
        is Screen.SetupSelectModeScreen -> SelectModeScreen(selectLocal = screen.selectLocal, selectConnected = screen.selectConnected, selectUninstall = screen.selectUninstall, modifier = modifier.padding(paddingValues))
        is Screen.DeleteRegistration -> DeleteRegistrationScreen(screen.content, modifier.padding(paddingValues))
        is Screen.ManageBlockedTimes -> BlockedTimesScreen(screen.content, screen.intro, modifier.padding(paddingValues))
        is Screen.ChildUsageHistory -> UsageHistoryScreen(screen.content, modifier.padding(paddingValues))
        is Screen.ManageChildUrlFilter -> UrlFilterScreen(screen.content, modifier.padding(paddingValues))
        is Screen.ManageChildAppUsage -> AppUsageScreen(screen.content, modifier.padding(paddingValues))
        is Screen.SetupParentMailAuthentication -> AuthenticateByMailScreen(screen.content, modifier.padding(paddingValues))
        is Screen.SignupBlocked -> SignupBlockedScreen(modifier.padding(paddingValues))
        is Screen.SignInWrongMailAddress -> SignInWrongMailAddress(modifier.padding(paddingValues))
        is Screen.ConfirmNewParentAccount -> ConfirmNewParentAccount(confirm = screen.confirm, reject = screen.reject, modifier = modifier.padding(paddingValues))
        is Screen.ParentBaseConfiguration -> ParentBaseConfiguration(content = screen.content, modifier = modifier.padding(paddingValues))
        is Screen.ParentSetupConsent -> ParentSetupConsent(content = screen.content, errorDialog = screen.errorDialog, modifier = modifier.padding(paddingValues))
        is Screen.ManageChildCurrentDeviceScreen -> CurrentDeviceScreen(screen = screen, modifier = modifier.padding(paddingValues))
        is Screen.ManageChildScreen -> ManageChildScreen(intro = screen.intro, screen = screen.content, modifier = modifier.padding(paddingValues))
    }
}