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

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.*
import io.timelimit.android.Application
import io.timelimit.android.BuildConfig
import io.timelimit.android.R
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.model.UserType
import io.timelimit.android.extensions.showSafe
import io.timelimit.android.integration.platform.SystemPermissionConfirmationLevel
import io.timelimit.android.integration.platform.android.NotificationChannels
import io.timelimit.android.livedata.ignoreUnchanged
import io.timelimit.android.livedata.liveDataFromNullableValue
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.u2f.U2fManager
import io.timelimit.android.u2f.protocol.U2FDevice
import io.timelimit.android.ui.animation.Transition
import io.timelimit.android.ui.consent.SyncAppListConsentDialogFragment
import io.timelimit.android.ui.login.AuthTokenLoginProcessor
import io.timelimit.android.ui.login.NewLoginFragment
import io.timelimit.android.ui.main.ActivityViewModel
import io.timelimit.android.ui.main.ActivityViewModelHolder
import io.timelimit.android.ui.main.AuthenticatedUser
import io.timelimit.android.ui.main.FragmentWithCustomTitle
import io.timelimit.android.ui.manage.child.category.create.CreateCategoryDialogFragment
import io.timelimit.android.ui.manage.child.category.specialmode.SetCategorySpecialModeFragment
import io.timelimit.android.ui.manage.device.add.AddDeviceFragment
import io.timelimit.android.ui.model.*
import io.timelimit.android.ui.overview.overview.CanNotAddDevicesInLocalModeDialogFragment
import io.timelimit.android.ui.payment.ActivityPurchaseModel
import io.timelimit.android.ui.payment.RequiresPurchaseDialogFragment
import io.timelimit.android.ui.util.SyncStatusModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom

class MainActivity : AppCompatActivity(), ActivityViewModelHolder, U2fManager.DeviceFoundListener, MainModelActivity {
    companion object {
        private const val LOG_TAG = "MainActivity"
        private const val AUTH_DIALOG_TAG = "adt"
        const val ACTION_USER_OPTIONS = "OPEN_USER_OPTIONS"
        const val EXTRA_USER_ID = "userId"
        private const val EXTRA_AUTH_HANDOVER = "authHandover"
        private const val MAIN_MODEL_STATE = "mainModelState"
        private const val FRAGMENT_IDS_STATE = "fragmentIds"

        private var authHandover: Triple<Long, Long, AuthenticatedUser>? = null

        fun getAuthHandoverIntent(context: Context, user: AuthenticatedUser): Intent {
            val time = SystemClock.uptimeMillis()
            val key = SecureRandom().nextLong()

            authHandover = Triple(time, key, user)

            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_AUTH_HANDOVER, key)
        }

        fun getAuthHandoverFromIntent(intent: Intent): AuthenticatedUser? {
            val cachedHandover = authHandover
            val time = SystemClock.uptimeMillis()

            if (cachedHandover == null) return null

            if (cachedHandover.first < time - 2000 || cachedHandover.first - 1000 > time) {
                authHandover = null

                return null
            }

            if (intent.getLongExtra(EXTRA_AUTH_HANDOVER, 0) != cachedHandover.second) return null

            authHandover = null

            return cachedHandover.third
        }
    }

    private val mainModel by viewModels<MainModel>()
    private val syncModel: SyncStatusModel by lazy {
        ViewModelProviders.of(this).get(SyncStatusModel::class.java)
    }
    val purchaseModel: ActivityPurchaseModel by viewModels()
    override var ignoreStop: Boolean = false
    override val showPasswordRecovery: Boolean = true

    private val requestNotifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) mainModel.api.reportPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isNightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                if (isNightMode) android.graphics.Color.TRANSPARENT
                else resources.getColor(R.color.colorPrimaryDark)
            )
        )

        U2fManager.setupActivity(this)
        io.timelimit.android.child.UiChoice.redirectParent(this, savedInstanceState == null) { // @tag:new-ui
            io.timelimit.android.ui.login.NewLoginFragment.keepingSignedIn().showSafe(supportFragmentManager, AUTH_DIALOG_TAG)
        }

        NotificationChannels.createNotificationChannels(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager, this)

        if (savedInstanceState != null) {
            mainModel.state.value = savedInstanceState.getSerializable(MAIN_MODEL_STATE) as State
            mainModel.fragmentIds.addAll(savedInstanceState.getIntegerArrayList(FRAGMENT_IDS_STATE) ?: emptyList())
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (message in mainModel.api.activityCommand) when (message) {
                    ActivityCommand.ShowAddDeviceFragment -> AddDeviceFragment().show(supportFragmentManager)
                    ActivityCommand.ShowCanNotAddDevicesInLocalModeDialogFragment -> CanNotAddDevicesInLocalModeDialogFragment().show(supportFragmentManager)
                    ActivityCommand.ShowAuthenticationScreen -> showAuthenticationScreen()
                    ActivityCommand.ShowMissingPremiumDialog -> RequiresPurchaseDialogFragment().show(supportFragmentManager)
                    is ActivityCommand.LaunchSystemSettings -> mainModel.logic.platformIntegration.openSystemPermissionScren(
                        this@MainActivity, message.permission, SystemPermissionConfirmationLevel.Suggestion
                    )
                    is ActivityCommand.TriggerUninstall -> try {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${message.packageName}")
                        ).addCategory(Intent.CATEGORY_DEFAULT)
                        else Intent(
                            Intent.ACTION_UNINSTALL_PACKAGE,
                            Uri.parse("package:${message.packageName}")
                        )

                        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (ex: Exception) {
                        message.errorHandler()
                    }
                    ActivityCommand.RequestNotifyPermission -> requestNotifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    ActivityCommand.ShowSyncConsentDialog -> SyncAppListConsentDialogFragment.newInstance().show(supportFragmentManager)
                    is ActivityCommand.ShowCreateCategoryDialog -> CreateCategoryDialogFragment.newInstance(childId = message.childId)
                        .show(supportFragmentManager)
                    is ActivityCommand.ShowCategorySpecialModeDialog -> SetCategorySpecialModeFragment.newInstance(
                        childId = message.childId,
                        categoryId = message.categoryId,
                        mode = message.mode
                    ).show(supportFragmentManager)
                }
            }
        }

        // init the purchaseModel
        purchaseModel.getApplication<Application>()

        // init if not yet done
        DefaultAppLogic.with(this)

        val fragments = MutableStateFlow(emptyMap<Int, Fragment>())

        supportFragmentManager.registerFragmentLifecycleCallbacks(object: FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                super.onFragmentStarted(fm, f)

                fragments.update {
                    it + Pair(f.id, f)
                }
            }

            override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
                super.onFragmentStopped(fm, f)

                fragments.update {
                    it - f.id
                }

                if (f is NewLoginFragment) mainModel.api.reportAuthenticationScreenClosed()

                cleanupFragments()
            }
        }, false)

        handleParameters(intent)

        val hasDeviceId = getActivityViewModel().logic.deviceId.map { it != null }.ignoreUnchanged()
        val hasParentKey = getActivityViewModel().logic.database.config().getParentModeKeyLive().map { it != null }.ignoreUnchanged()

        hasDeviceId.observe(this) {
            val rootDestination = mainModel.state.value.first()

            if (!it) getActivityViewModel().logOut()

            if (
                it && rootDestination !is State.Overview ||
                !it && rootDestination is State.Overview
            ) {
                restartContent()
            }
        }

        hasParentKey.observe(this) {
            val rootDestination = mainModel.state.value.first()

            if (
                it && rootDestination !is State.ParentMode ||
                !it && rootDestination is State.ParentMode
            ) {
                restartContent()
            }
        }

        setContent {
            Theme {
                val screenLive by mainModel.screen.collectAsState(initial = null)
                val subtitleLive by syncModel.statusText.asFlow().collectAsState(initial = null)

                val isAuthenticated by getActivityViewModel().authenticatedUser
                    .map { it?.second?.type == UserType.Parent }
                    .asFlow().collectAsState(initial = false)

                BackHandler(enabled = screenLive?.state?.previous != null) {
                    execute(UpdateStateCommand.BackToPreviousScreen)
                }

                updateTransition(
                    targetState = screenLive,
                    label = "AnimatedContent"
                ).AnimatedContent(
                    modifier = Modifier.background(Color.Black),
                    contentKey = { screen ->
                        when (screen) {
                            is Screen.FragmentScreen -> screen.fragment.containerId
                            null -> null
                            else -> screen.javaClass
                        }
                    },
                    transitionSpec = {
                        val from = initialState
                        val to = targetState

                        if (from == null || to == null) Transition.none
                        else {
                            val isOpening = to.state.hasPrevious(from.state)
                            val isClosing = from.state.hasPrevious(to.state)

                            if (isOpening) Transition.openScreen
                            else if (isClosing)
                                if (from.state.previous == to.state) Transition.closeScreen
                                else Transition.bigCloseScreen
                            else Transition.swap
                        }
                    }
                ) { screen ->
                    val showAuthenticationDialog =
                        if (screen is ScreenWithAuthenticationFab && !isAuthenticated) ::showAuthenticationScreen
                        else null

                    val fragmentsLive by fragments.collectAsState()

                    val customTitle by when (screen) {
                        is Screen.FragmentScreen -> {
                            when (val fragment = fragmentsLive[screen.fragment.containerId]) {
                                is FragmentWithCustomTitle -> fragment.getCustomTitle()
                                else -> liveDataFromNullableValue(null)
                            }
                        }
                        else -> liveDataFromNullableValue(null)
                    }.asFlow().collectAsState(initial = null)

                    val screenTitle = when (screen) {
                        is ScreenWithTitle -> when (val title = screen.title) {
                            is Title.Plain -> title.text
                            is Title.StringResource -> stringResource(title.id)
                        }
                        else -> null
                    }

                    ScreenScaffold(
                        screen = screen,
                        title = screenTitle ?: customTitle ?: stringResource(R.string.app_name),
                        subtitle = subtitleLive,
                        backStack = when (screen) {
                            is ScreenWithBackStack -> screen.backStack
                            else -> emptyList()
                        },
                        executeCommand = ::execute,
                        content = { paddingValues ->
                            ScreenMultiplexer(
                                screen = screen,
                                fragmentManager = supportFragmentManager,
                                fragmentIds = mainModel.fragmentIds,
                                modifier = Modifier.fillMaxSize(),
                                paddingValues = paddingValues
                            )
                        },
                        showAuthenticationDialog = showAuthenticationDialog,
                        snackbarHostState = when (screen) {
                            is ScreenWithSnackbar -> screen.snackbarHostState
                            else -> null
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putSerializable(MAIN_MODEL_STATE, mainModel.state.value)
        outState.putIntegerArrayList(FRAGMENT_IDS_STATE, ArrayList(mainModel.fragmentIds))
    }

    override fun onStart() {
        super.onStart()

        purchaseModel.queryAndProcessPurchasesAsync()

        IsAppInForeground.reportStart()

        syncModel.handleStart()
    }

    override fun onStop() {
        super.onStop()

        if ((!isChangingConfigurations) && (!ignoreStop)) {
            getActivityViewModel().logOut()
        }

        IsAppInForeground.reportStop()
    }

    override fun onDestroy() {
        super.onDestroy()

        purchaseModel.forgetActivityCheckout()
    }

    private fun handleParameters(intent: Intent?): Boolean {
        // do not return true in this case because this should not affect other navigations
        intent?.also {
            getAuthHandoverFromIntent(intent)?.also { auth ->
                getActivityViewModel().setAuthenticatedUser(auth)
            }
        }

        if (intent?.action == ACTION_USER_OPTIONS) {
            val userId = intent.getStringExtra(EXTRA_USER_ID)
            val valid = userId != null && try { IdGenerator.assertIdValid(userId); true } catch (ex: IllegalArgumentException) {false}

            if (userId != null && valid) {
                execute(UpdateStateCommand.RecoverPassword(userId))
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT == Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) {
            return
        }

        if (handleParameters(intent)) return
    }

    private fun restartContent() {
        mainModel.execute(UpdateStateCommand.Reset)
    }

    override fun getActivityViewModel(): ActivityViewModel = mainModel.api.activityModel

    override fun showAuthenticationScreen() {
        if (supportFragmentManager.findFragmentByTag(AUTH_DIALOG_TAG) == null) {
            NewLoginFragment().showSafe(supportFragmentManager, AUTH_DIALOG_TAG)
        }
    }

    private fun cleanupFragments() {
        mainModel.fragmentIds
            .filter { fragmentId ->
                var v = mainModel.state.value as State?

                while (v != null) {
                    if (v is FragmentState && v.containerId == fragmentId) return@filter false

                    v = v.previous
                }

                true
            }
            .mapNotNull { supportFragmentManager.findFragmentById(it) }
            .filter { it.isDetached }
            .forEach {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "remove fragment $it")
                }

                val id = it.id

                supportFragmentManager.beginTransaction()
                    .remove(it)
                    .commitAllowingStateLoss()

                mainModel.fragmentIds.remove(id)
            }
    }

    override fun onResume() {
        super.onResume()

        U2fManager.with(this).registerListener(this)
    }

    override fun onPause() {
        super.onPause()

        U2fManager.with(this).unregisterListener(this)
    }

    override fun onDeviceFound(device: U2FDevice) = AuthTokenLoginProcessor.process(device, getActivityViewModel())

    override fun execute(command: UpdateStateCommand) = mainModel.execute(command)
}
