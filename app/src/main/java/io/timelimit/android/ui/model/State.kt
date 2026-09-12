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

import android.os.Bundle
import androidx.fragment.app.Fragment
import io.timelimit.android.R
import io.timelimit.android.integration.platform.SystemPermission
import io.timelimit.android.sync.network.StatusOfMailAddress
import io.timelimit.android.sync.network.StatusOfMailAddressResponse
import io.timelimit.android.ui.contacts.ContactsFragment
import io.timelimit.android.ui.diagnose.*
import io.timelimit.android.ui.diagnose.exitreason.DiagnoseExitReasonFragment
import io.timelimit.android.ui.fragment.*
import io.timelimit.android.ui.manage.category.ManageCategoryFragment
import io.timelimit.android.ui.manage.category.ManageCategoryFragmentArgs
import io.timelimit.android.ui.manage.device.manage.ManageDeviceFragment
import io.timelimit.android.ui.manage.device.manage.ManageDeviceFragmentArgs
import io.timelimit.android.ui.manage.device.manage.advanced.ManageDeviceAdvancedFragment
import io.timelimit.android.ui.manage.device.manage.advanced.ManageDeviceAdvancedFragmentArgs
import io.timelimit.android.ui.manage.device.manage.feature.ManageDeviceFeaturesFragment
import io.timelimit.android.ui.manage.device.manage.feature.ManageDeviceFeaturesFragmentArgs
import io.timelimit.android.ui.manage.parent.ManageParentFragment
import io.timelimit.android.ui.manage.parent.ManageParentFragmentArgs
import io.timelimit.android.ui.manage.parent.link.LinkParentMailFragment
import io.timelimit.android.ui.manage.parent.link.LinkParentMailFragmentArgs
import io.timelimit.android.ui.manage.parent.password.change.ChangeParentPasswordFragment
import io.timelimit.android.ui.manage.parent.password.change.ChangeParentPasswordFragmentArgs
import io.timelimit.android.ui.manage.parent.password.restore.RestoreParentPasswordFragment
import io.timelimit.android.ui.manage.parent.password.restore.RestoreParentPasswordFragmentArgs
import io.timelimit.android.ui.manage.parent.u2fkey.ManageParentU2FKeyFragment
import io.timelimit.android.ui.manage.parent.u2fkey.ManageParentU2FKeyFragmentArgs
import io.timelimit.android.ui.model.account.AccountDeletion
import io.timelimit.android.ui.model.managedevice.DeviceOwnerHandling
import io.timelimit.android.ui.model.mailauthentication.MailAuthentication
import io.timelimit.android.ui.model.main.OverviewHandling
import io.timelimit.android.ui.model.managechild.ManageCategoryBlockedTimes
import io.timelimit.android.ui.model.managechild.ManageChildCategoryList
import io.timelimit.android.ui.model.managechild.ManageChildCurrentDevice
import io.timelimit.android.ui.model.managechild.ManageChildUsageHistory
import io.timelimit.android.ui.model.setup.SetupParentHandling
import io.timelimit.android.ui.overview.uninstall.UninstallFragment
import io.timelimit.android.ui.parentmode.ParentModeFragment
import io.timelimit.android.ui.payment.PurchaseFragment
import io.timelimit.android.ui.payment.StayAwesomeFragment
import io.timelimit.android.ui.setup.*
import io.timelimit.android.ui.setup.child.SetupRemoteChildFragment
import io.timelimit.android.ui.setup.device.SetupDeviceFragment
import io.timelimit.android.ui.user.create.AddUserFragment
import io.timelimit.android.ui.view.NotifyPermissionCard
import java.io.Serializable

sealed class State (val previous: State?): Serializable {
    fun hasPrevious(other: State): Boolean = this.previous != null && (this.previous.matches(other) || this.previous.hasPrevious(other))
    fun find(predicate: (State) -> Boolean): State? =
        if (predicate(this)) this
        else previous?.find(predicate)
    fun first(): State = previous?.first() ?: this
    open fun matches(other: State) = this == other
    object LaunchState: State(previous = null) {
        @Suppress("UNUSED_VARIABLE")
        private fun readResolve(): Any = LaunchState
    }

    data class Overview(
        val state: OverviewHandling.OverviewState = OverviewHandling.OverviewState.empty
    ): State(previous = null)
    class About(previous: Overview): FragmentStateLegacy(
        previous = previous,
        fragmentClass = AboutFragmentWrapped::class.java,
        containerId = R.id.fragment_about
    )
    class AddUser(previous: Overview): FragmentStateLegacy(
        previous = previous,
        fragmentClass = AddUserFragment::class.java,
        containerId = R.id.fragment_add_user
    )
    sealed class ManageChild(
        previous: State,
        fragmentClass: Class<out Fragment>,
        val childId: String,
        val previousOverview: Overview,
        containerId: Int
    ): FragmentStateLegacy(previous, fragmentClass, containerId) {
        class Main(
            previousOverview: Overview,
            childId: String,
            val lastSpecialMode: ManageChildCategoryList.CategorySpecialMode.NotNone? = null
        ): ManageChild(
            previous = previousOverview,
            fragmentClass = Fragment::class.java,
            childId = childId,
            previousOverview = previousOverview,
            containerId = R.id.fragment_manage_child
        ) {
            fun copy(lastSpecialMode: ManageChildCategoryList.CategorySpecialMode.NotNone? = this.lastSpecialMode) = Main(previousOverview, childId, lastSpecialMode)
        }

        sealed class Sub(
            previous: State,
            val previousMain: Main,
            fragmentClass: Class<out Fragment>,
            containerId: Int
        ): ManageChild(previous, fragmentClass, previousMain.childId, previousMain.previousOverview, containerId)

        class Apps(val previousChild: Main): Sub(previousChild, previousChild, ChildAppsFragmentWrapper::class.java, R.id.fragment_manage_child_apps) {
            override val arguments: Bundle get() = ChildAppsFragmentWrapperArgs(previousChild.childId).toBundle()
        }
        class Advanced(val previousChild: Main): Sub(previousChild, previousChild, ChildAdvancedFragmentWrapper::class.java, R.id.fragment_manage_child_advanced) {
            override val arguments: Bundle get() = ChildAdvancedFragmentWrapperArgs(previousChild.childId).toBundle()
        }
        data class AdvancedCurrentDevice(
            val previousAdvanced: Advanced,
            val nested: ManageChildCurrentDevice.State = ManageChildCurrentDevice.State()
        ): Sub(previousAdvanced, previousAdvanced.previousMain, Fragment::class.java, R.id.fragment_manage_child_advanced_current_device)
        class Contacts(val previousChild: Main): Sub(previousChild, previousChild, ContactsFragment::class.java, R.id.fragment_manage_child_contacts)
        data class UsageHistory(
            val previousChild: Main,
            val state: ManageChildUsageHistory.State = ManageChildUsageHistory.State()
        ): Sub(previousChild, previousChild, Fragment::class.java, R.id.fragment_manage_child_usage_history)
        // @tag:url-filter
        class UrlFilter(val previousAdvanced: Advanced): Sub(previousAdvanced, previousAdvanced.previousMain, Fragment::class.java, R.id.fragment_manage_child_url_filter)
        // @tag:category-limits
        data class AppUsage(
            val previousChild: Main,
            val daysAgo: Int = 0
        ): Sub(previousChild, previousChild, Fragment::class.java, R.id.fragment_manage_child_app_usage)
        class Tasks(val previousChild: Main): Sub(previousChild, previousChild, ChildTasksFragmentWrapper::class.java, R.id.fragment_manage_child_tasks) {
            override val arguments: Bundle get() = ChildTasksFragmentWrapperArgs(previousChild.childId).toBundle()
        }

        sealed class ManageCategory(
            previous: State,
            val previousChild: ManageChild.Main,
            val categoryId: String,
            fragmentClass: Class<out Fragment>,
            containerId: Int
        ): Sub(previous, previousChild, fragmentClass, containerId) {
            class Main(
                previousChild: ManageChild.Main,
                categoryId: String
            ): ManageCategory(previousChild, previousChild, categoryId, ManageCategoryFragment::class.java, R.id.fragment_manage_category) {
                override val arguments: Bundle get() = ManageCategoryFragmentArgs(
                    childId = previousChild.childId,
                    categoryId = categoryId
                ).toBundle()

                override val toolbarOptions: List<Menu.Dropdown> get() = listOf(
                    Menu.Dropdown(R.string.blocked_time_areas, UpdateStateCommand.ManageChild.BlockedTimes),
                    Menu.Dropdown(R.string.category_settings, UpdateStateCommand.ManageChild.CategoryAdvanced)
                )
            }

            sealed class Sub(
                previous: State,
                val previousCategory: Main,
                fragmentClass: Class<out Fragment>,
                containerId: Int
            ): ManageCategory(previous, previousCategory.previousChild, previousCategory.categoryId, fragmentClass, containerId)

            data class BlockedTimes(
                val previousMain2: Main,
                val details: ManageCategoryBlockedTimes.State = ManageCategoryBlockedTimes.State.initial
            ): Sub(previousMain2, previousMain2, Fragment::class.java, R.id.fragment_manage_category_blocked_times)

            class Advanced(
                previousCategory: Main
            ): Sub(previousCategory, previousCategory, CategoryAdvancedFragmentWrapper::class.java, R.id.fragment_manage_category_advanced) {
                override val arguments: Bundle get() = CategoryAdvancedFragmentWrapperArgs(
                    childId = previousCategory.previousChild.childId,
                    categoryId = previousCategory.categoryId
                ).toBundle()
            }
        }
    }
    sealed class ManageParent(previous: State, fragmentClass: Class<out Fragment>, containerId: Int): FragmentStateLegacy(previous = previous, fragmentClass = fragmentClass, containerId = containerId) {
        class Main(
            previous: Overview,
            val parentId: String
        ): ManageParent(previous = previous, fragmentClass = ManageParentFragment::class.java, containerId = R.id.fragment_manage_parent) {
            override val arguments get() = ManageParentFragmentArgs(parentId).toBundle()
        }

        class ChangePassword(val previousParent: Main): ManageParent(previousParent, ChangeParentPasswordFragment::class.java, R.id.fragment_manage_parent_change_password) {
            override val arguments: Bundle get() = ChangeParentPasswordFragmentArgs(previousParent.parentId).toBundle()
        }
        class RestorePassword(val previousParent: Main): ManageParent(previousParent, RestoreParentPasswordFragment::class.java, R.id.fragment_manage_parent_password) {
            override val arguments: Bundle get() = RestoreParentPasswordFragmentArgs(previousParent.parentId).toBundle()
        }
        class LinkMail(val previousParent: Main): ManageParent(previousParent, LinkParentMailFragment::class.java, R.id.fragment_manage_parent_link_mail) {
            override val arguments: Bundle get() = LinkParentMailFragmentArgs(previousParent.parentId).toBundle()
        }
        class U2F(val previousParent: Main): ManageParent(previousParent, ManageParentU2FKeyFragment::class.java, R.id.fragment_manage_parent_u2f) {
            override val arguments: Bundle get() = ManageParentU2FKeyFragmentArgs(previousParent.parentId).toBundle()
        }
    }
    sealed class ManageDevice(
        previous: State,
        val previousOverview: Overview,
        val deviceId: String,
        fragmentClass: Class<out Fragment>,
        containerId: Int
    ): FragmentStateLegacy(previous, fragmentClass, containerId) {
        class Main(
            previousOverview: Overview,
            deviceId: String
        ): ManageDevice(previousOverview, previousOverview, deviceId, ManageDeviceFragment::class.java, R.id.fragment_manage_device_main) {
            override val arguments: Bundle get() = ManageDeviceFragmentArgs(deviceId).toBundle()
        }

        sealed class Sub(
            val previousManageDeviceMain: Main,
            fragmentClass: Class<out Fragment>,
            previous: State = previousManageDeviceMain,
            containerId: Int
        ): ManageDevice(
            previous,
            previousManageDeviceMain.previousOverview,
            previousManageDeviceMain.deviceId,
            fragmentClass,
            containerId
        )

        data class User(
            val previousMain: Main,
            val overlay: Overlay? = null
        ): Sub(previousMain, Fragment::class.java, containerId = R.id.fragment_manage_device_user) {
            sealed class Overlay: Serializable {
                data class EnableDefaultUserDialog(val userId: String): Overlay()
                object AdjustDefaultUserTimeout: Overlay() {
                    @Suppress("UNUSED_VARIABLE")
                    private fun readResolve(): Any = LaunchState
                }
            }
        }
        data class Permissions(val previousMain: Main, val currentDialog: SystemPermission? = null): Sub(previousMain, Fragment::class.java, containerId = R.id.fragment_manage_device_permissions) {
            override fun matches(other: State): Boolean =
                if (other is Permissions) this.previousMain.matches(other.previousMain)
                else false
        }
        data class DeviceOwner(val previousPermissions: Permissions, val details: DeviceOwnerHandling.OwnerState = DeviceOwnerHandling.OwnerState()): Sub(previousPermissions.previousMain, Fragment::class.java, previousPermissions, R.id.fragment_manage_device_owner)
        class Features(previousMain: Main): Sub(previousMain, ManageDeviceFeaturesFragment::class.java, containerId = R.id.fragment_manage_device_features) {
            override val arguments: Bundle get() = ManageDeviceFeaturesFragmentArgs(deviceId).toBundle()
        }
        class Advanced(previousMain: Main): Sub(previousMain, ManageDeviceAdvancedFragment::class.java, containerId = R.id.fragment_manage_device_advanced) {
            override val arguments: Bundle get() = ManageDeviceAdvancedFragmentArgs(deviceId).toBundle()
        }
    }
    class SetupDevice(val previousOverview: Overview): FragmentStateLegacy(previous = previousOverview, fragmentClass = SetupDeviceFragment::class.java, R.id.fragment_setup_device)
    data class DeleteAccount(
        val previousOverview: Overview,
        val content: AccountDeletion.MyState = AccountDeletion.MyState.Preparing()
    ): State(previousOverview)
    class Uninstall(previous: Overview): FragmentStateLegacy(previous = previous, fragmentClass = UninstallFragment::class.java, R.id.fragment_uninstall)
    object DiagnoseScreen {
        class Main(previous: About): FragmentStateLegacy(previous, DiagnoseMainFragment::class.java, R.id.fragment_diagnose_main)
        class Battery(previous: Main): FragmentStateLegacy(previous, DiagnoseBatteryFragment::class.java, R.id.fragment_diagnose_battery)
        class Clock(previous: Main): FragmentStateLegacy(previous, DiagnoseClockFragment::class.java, R.id.fragment_diagnose_clock)
        class Connection(previous: Main): FragmentStateLegacy(previous, DiagnoseConnectionFragment::class.java, R.id.fragment_diagnose_connection)
        class ExperimentalFlags(previous: Main): FragmentStateLegacy(previous, DiagnoseExperimentalFlagFragment::class.java, R.id.fragment_diagnose_experimental_flag)
        class ExitReasons(previous: Main): FragmentStateLegacy(previous, DiagnoseExitReasonFragment::class.java, R.id.fragment_diagnose_exit_reason)
        class Crypto(previous: Main): FragmentStateLegacy(previous, DiagnoseCryptoFragment::class.java, R.id.fragment_diagnose_crypto)
        class ForegroundApp(previous: Main): FragmentStateLegacy(previous, DiagnoseForegroundAppFragment::class.java, R.id.fragment_diagnose_foreground_app)
        class Sync(previous: Main): FragmentStateLegacy(previous, DiagnoseSyncFragment::class.java, R.id.fragment_diagnose_sync)
    }
    sealed class Setup(previous: State): State(previous) {
        class SetupTerms: FragmentStateLegacy(previous = null, fragmentClass = SetupTermsFragment::class.java, R.id.fragment_setup_terms)
        class SetupHelpInfo(previous: SetupTerms): FragmentStateLegacy(previous = previous, fragmentClass = SetupHelpInfoFragment::class.java, R.id.fragment_setup_help_info)
        class SelectMode(previous: SetupHelpInfo): Setup(previous)
        data class DevicePermissions(
            val previousSelectMode: SelectMode,
            val currentDialog: Dialog? = null
        ): Setup(previous = previousSelectMode) {
            sealed class Dialog: Serializable

            data class SystemPermissionDialog(val permission: SystemPermission): Dialog()
            object ParentKeyDialog: Dialog() {
                @Suppress("UNUSED_VARIABLE")
                private fun readResolve(): Any = LaunchState
            }
        }
        class LocalMode(previous: DevicePermissions): FragmentStateLegacy(previous = previous, fragmentClass = SetupLocalModeFragment::class.java, R.id.fragment_setup_local_mode)
        class ConnectedPrivacy(previousSelectMode: SelectMode): Setup(previousSelectMode)
        class SelectConnectedMode(previousConnectedPrivacy: ConnectedPrivacy): Setup(previousConnectedPrivacy)
        class RemoteChild(previous: SelectConnectedMode): FragmentStateLegacy(previous = previous, fragmentClass = SetupRemoteChildFragment::class.java, R.id.fragment_setup_remote_child)
        sealed class ParentModeSetup(previous: State): Setup(previous)
        data class ParentMailAuthentication(
            val previousSelectConnectedMode: SelectConnectedMode,
            val content: MailAuthentication.State = MailAuthentication.State.initial
        ): ParentModeSetup(previous = previousSelectConnectedMode)
        class SignUpBlocked(previous: ParentMailAuthentication): ParentModeSetup(previous)
        class SignInWrongMailAddress(previous: ConfirmNewParentAccount): ParentModeSetup(previous)
        data class ConfirmNewParentAccount(
            val previousParentMailAuthentication: ParentMailAuthentication,
            val mailAuthToken: String,
            val mailStatus: StatusOfMailAddressResponse
        ): ParentModeSetup(previous = previousParentMailAuthentication)
        data class ParentBaseConfiguration(
            val previousState: State,
            val previousParentMailAuthentication: ParentMailAuthentication,
            val mailAuthToken: String,
            val mailStatus: StatusOfMailAddressResponse,
            val deviceName: String,
            val newUser: SetupParentHandling.NewUserDetails?
        ): ParentModeSetup(previousState) {
            init {
                if ((newUser != null) != (mailStatus.status == StatusOfMailAddress.MailAddressWithoutFamily))
                    throw IllegalStateException()
            }
        }
        data class ParentConsent(
            val baseConfig: ParentBaseConfiguration,
            val backgroundSync: Boolean,
            val notificationAccess: NotifyPermissionCard.Status,
            val enableUpdates: Boolean,
            val error: String?
        ): ParentModeSetup(baseConfig) {
            init {
                if (baseConfig.newUser != null && !baseConfig.newUser.ready) throw IllegalStateException()
            }
        }
    }
    class ParentMode: FragmentStateLegacy(previous = null, fragmentClass = ParentModeFragment::class.java, R.id.fragment_setup_parent_mode)
    object Purchase {
        class Purchase(previous: About): FragmentStateLegacy(previous, PurchaseFragment::class.java, R.id.fragment_purchase)
        class StayAwesome(previous: About): FragmentStateLegacy(previous, StayAwesomeFragment::class.java, R.id.fragment_stay_awesome)
    }
}