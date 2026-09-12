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

import androidx.compose.material.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Info
import io.timelimit.android.R
import io.timelimit.android.ui.manage.device.manage.permission.PermissionScreenContent
import io.timelimit.android.ui.model.account.AccountDeletion
import io.timelimit.android.ui.model.managedevice.DeviceOwnerHandling
import io.timelimit.android.ui.model.intro.IntroHandling
import io.timelimit.android.ui.model.mailauthentication.MailAuthentication
import io.timelimit.android.ui.model.main.OverviewHandling
import io.timelimit.android.ui.model.managechild.ManageCategoryBlockedTimes
import io.timelimit.android.ui.model.managechild.ManageChildCategoryList
import io.timelimit.android.ui.model.managechild.ManageChildCurrentDevice
import io.timelimit.android.ui.model.managechild.ManageChildUsageHistory
import io.timelimit.android.ui.model.managechild.ManageChildUrlFilter
import io.timelimit.android.ui.model.managechild.ManageChildAppUsage
import io.timelimit.android.ui.model.managedevice.ManageDeviceUser
import io.timelimit.android.ui.model.setup.SetupParentHandling

sealed class Screen(
    val state: State,
    val toolbarIcons: List<Menu.Icon> = emptyList(),
    val toolbarOptions: List<Menu.Dropdown> = emptyList()
) {
    open class FragmentScreen(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        val fragment: FragmentState
    ): Screen(state, toolbarIcons, toolbarOptions)

    class OverviewScreen(
        state: State,
        val content: OverviewHandling.OverviewScreen,
        override val snackbarHostState: SnackbarHostState
    ): Screen(
        state,
        listOf(Menu.Icon(
            Icons.Outlined.Info,
            R.string.main_tab_about,
            UpdateStateCommand.Overview.LaunchAbout
        )),
        listOf(
            Menu.Dropdown(
                R.string.main_tab_uninstall,
                UpdateStateCommand.Overview.Uninstall
            ),
            Menu.Dropdown(
                R.string.account_deletion_title,
                UpdateStateCommand.Overview.DeleteAccount
            )
        )
    ), ScreenWithAuthenticationFab, ScreenWithSnackbar

    class ManageChildScreen(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        val intro: IntroHandling.Screen,
        childName: String,
        val content: ManageChildCategoryList.Screen,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state, toolbarIcons, toolbarOptions), ScreenWithBackStack, ScreenWithTitle, ScreenWithAuthenticationFab, ScreenWithSnackbar {
        override val title = Title.Plain(childName)
    }

    class ManageChildAppsScreen(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.child_apps_title)
    }

    class ManageChildAdvancedScreen(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.manage_child_tab_other)
    }

    class ManageChildCurrentDeviceScreen(
        state: State,
        val content: ManageChildCurrentDevice.Content,
        val intro: IntroHandling.Screen,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState
    ): Screen(
        state,
        toolbarIcons = IntroHandling.toolbarIcons(intro)
    ), ScreenWithBackStack, ScreenWithTitle, ScreenWithSnackbar, ScreenWithAuthenticationFab {
        override val title = Title.StringResource(R.string.current_device_title)
    }

    class ManageChildContactsScreen(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.contacts_title_long)
    }

    class ChildUsageHistory(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        val content: ManageChildUsageHistory.Screen,
        override val backStack: List<BackStackItem>
    ): Screen(state, toolbarIcons, toolbarOptions), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.usage_history_title)
    }

    // @tag:url-filter
    class ManageChildUrlFilter(
        state: State,
        val content: ManageChildUrlFilter.Screen,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state), ScreenWithBackStack, ScreenWithTitle, ScreenWithSnackbar, ScreenWithAuthenticationFab {
        override val title = Title.StringResource(R.string.url_filter_title)
    }

    // @tag:category-limits
    class ManageChildAppUsage(
        state: State,
        val content: ManageChildAppUsage.Screen,
        override val backStack: List<BackStackItem>
    ): Screen(state), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.app_usage_title)
    }

    class ManageChildUsageTasks(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.manage_child_tasks)
    }
    class ManageCategory(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        val categoryName: String,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.Plain(categoryName)
    }
    class ManageCategoryAdvanced(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.category_settings)
    }

    class ManageBlockedTimes(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        val content: ManageCategoryBlockedTimes.Screen,
        val intro: IntroHandling.Screen,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state, toolbarIcons, toolbarOptions), ScreenWithBackStack, ScreenWithTitle, ScreenWithSnackbar, ScreenWithAuthenticationFab {
        override val title = Title.StringResource(R.string.blocked_time_areas)
    }

    class ManageDevice(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        deviceName: String,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.Plain(deviceName)
    }

    class ManageDeviceUserScreen(
        state: State,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState,
        val items: List<ManageDeviceUser.UserItem>,
        val actions: ManageDeviceUser.Actions,
        val overlay: ManageDeviceUser.Overlay?
    ): Screen(state), ScreenWithBackStack, ScreenWithTitle, ScreenWithAuthenticationFab, ScreenWithSnackbar {
        override val title = Title.StringResource(R.string.manage_device_card_user_title)
    }

    class ManageDevicePermissions(
        state: State,
        val content: PermissionScreenContent,
        override val backStack: List<BackStackItem>
    ): Screen(state), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.manage_device_card_permission_title)
    }

    class ManageDeviceFeatures(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.manage_device_card_feature_title)
    }

    class ManageDeviceAdvances(
        state: State,
        toolbarIcons: List<Menu.Icon>,
        toolbarOptions: List<Menu.Dropdown>,
        fragment: FragmentState,
        override val backStack: List<BackStackItem>
    ): FragmentScreen(state, toolbarIcons, toolbarOptions, fragment), ScreenWithBackStack, ScreenWithTitle {
        override val title = Title.StringResource(R.string.manage_device_card_manage_title)
    }

    class DeviceOwnerScreen(
        state: State,
        val content: DeviceOwnerHandling.OwnerScreen,
        override val backStack: List<BackStackItem>,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state), ScreenWithAuthenticationFab, ScreenWithSnackbar, ScreenWithTitle, ScreenWithBackStack {
        override val title = Title.StringResource(R.string.diagnose_dom_title)
    }

    class SetupSelectModeScreen(
        state: State.Setup.SelectMode,
        override val snackbarHostState: SnackbarHostState,
        val selectLocal: () -> Unit,
        val selectConnected: () -> Unit,
        val selectUninstall: () -> Unit
    ): Screen(state), ScreenWithSnackbar

    class SetupDevicePermissionsScreen(
        state: State.Setup.DevicePermissions,
        val content: PermissionScreenContent,
        requestKeyMode: () -> Unit,
        val next: () -> Unit,
        val keyDialog: KeyDialog?,
        override val snackbarHostState: SnackbarHostState
    ): Screen(
        state,
        toolbarIcons = listOf(
            Menu.Icon(Icons.Default.Key, R.string.setup_select_mode_parent_key_title, handler = requestKeyMode)
        )
    ), ScreenWithSnackbar {
        data class KeyDialog(val confirm: () -> Unit, val cancel: () -> Unit)
    }

    class SetupConnectModePrivacyScreen(
        state: State.Setup.ConnectedPrivacy,
        val customServerDomain: String?,
        val accept: () -> Unit
    ): Screen(state), ScreenWithTitle {
        override val title = Title.StringResource(R.string.setup_privacy_connected_title)
    }

    class SetupSelectConnectedModeScreen(
        state: State.Setup.SelectConnectedMode,
        val mailLogin: () -> Unit,
        val codeLogin: () -> Unit
    ): Screen(state)

    class DeleteRegistration(
        state: State.DeleteAccount,
        val content: AccountDeletion.MyScreen,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state), ScreenWithSnackbar, ScreenWithTitle {
        override val title: Title = Title.StringResource(R.string.account_deletion_title)
    }

    class SetupParentMailAuthentication(
        state: State.Setup.ParentMailAuthentication,
        val content: MailAuthentication.Screen,
        override val snackbarHostState: SnackbarHostState
    ): Screen(state), ScreenWithSnackbar

    class SignupBlocked(state: State.Setup.SignUpBlocked): Screen(state)

    class SignInWrongMailAddress(state: State.Setup.SignInWrongMailAddress): Screen(state)
    class ConfirmNewParentAccount(
        state: State.Setup.ConfirmNewParentAccount,
        val reject: () -> Unit,
        val confirm: () -> Unit
    ): Screen(state)

    class ParentBaseConfiguration(
        state: State.Setup.ParentBaseConfiguration,
        val content: SetupParentHandling.ParentBaseConfiguration
    ): Screen(state), ScreenWithTitle {
        override val title: Title get() =
            if (content.newUserDetails != null) Title.StringResource(R.string.setup_parent_mode_create_family)
            else Title.StringResource(R.string.setup_parent_mode_add_device)
    }

    class ParentSetupConsent(
        state: State.Setup.ParentConsent,
        val content: SetupParentHandling.ParentSetupConsent,
        override val snackbarHostState: SnackbarHostState,
        val errorDialog: Pair<String, () -> Unit>?
    ): Screen(state), ScreenWithSnackbar
}

interface ScreenWithAuthenticationFab
interface ScreenWithSnackbar {
    val snackbarHostState: SnackbarHostState
}

interface ScreenWithTitle {
    val title: Title
}

interface ScreenWithBackStack {
    val backStack: List<BackStackItem>
}

data class BackStackItem(
    val title: Title,
    val action: () -> Unit
)

sealed class Title {
    data class Plain(val text: String): Title()
    data class StringResource(val id: Int): Title()
}