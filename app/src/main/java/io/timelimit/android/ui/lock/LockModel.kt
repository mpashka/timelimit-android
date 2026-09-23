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

package io.timelimit.android.ui.lock

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.material.SnackbarHostState
import androidx.lifecycle.*
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.data.model.TemporarilyAllowedApp
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.DeviceAndUserRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.integration.platform.NetworkId
import io.timelimit.android.livedata.*
import io.timelimit.android.logic.*
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import io.timelimit.android.ui.model.ApiModel
import io.timelimit.android.ui.model.managechild.ManageChildCurrentDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

class LockModel(application: Application): AndroidViewModel(application) {
    val api = ApiModel(application)
    private val packageAndActivityNameLiveInternal = MutableLiveData<Pair<String, String?>>()
    private var didInit = false

    val logic = DefaultAppLogic.with(application)
    private val deviceAndUserRelatedData: LiveData<DeviceAndUserRelatedData?> = logic.database.derivedDataDao().getUserAndDeviceRelatedDataLive()
    private val batteryStatus: LiveData<BatteryStatus> = logic.platformIntegration.getBatteryStatusLive()
    private val realNetworkIdLive: LiveData<NetworkId> = liveDataFromFunction { logic.platformIntegration.getCurrentNetworkId() }
    private val needsNetworkIdLive = MutableLiveData<Boolean>().apply { value = false }
    private val networkIdLive: LiveData<NetworkId?> by lazy { needsNetworkIdLive.switchMap { needsNetworkId ->
        if (needsNetworkId) realNetworkIdLive as LiveData<NetworkId?> else liveDataFromNullableValue(null as NetworkId?)
    }.ignoreUnchanged() }
    private val borrowedCurrentDevice = logic.currentDeviceLogic.borrowedCurrentDevice.asLiveData()
    private val handlingCache = CategoryHandlingCache()

    val title: String? get() = logic.platformIntegration.getLocalAppTitle(packageAndActivityNameLiveInternal.value!!.first)
    val icon: Drawable? get() = logic.platformIntegration.getAppIcon(packageAndActivityNameLiveInternal.value!!.first)
    val packageAndActivityNameLive: LiveData<Pair<String, String?>> = packageAndActivityNameLiveInternal

    fun init(packageName: String, activityName: String?) {
        if (didInit) return

        packageAndActivityNameLiveInternal.value = packageName to activityName
    }

    val enableAlternativeDurationSelection = logic.database.config().getEnableAlternativeDurationSelectionAsync()

    val snackbarHostState = SnackbarHostState()
    val manageCurrentDeviceLaunchFunction = ManageChildCurrentDevice.buildLaunchFunction(viewModelScope, application, snackbarHostState)
    val manageCurrentDeviceTransitionLive = MutableStateFlow(null as ManageChildCurrentDevice.Content.ActiveUser.Transition?)
    val manageCurrentDeviceTransitionMutex = Mutex()
    val manageCurrentDeviceState = MutableStateFlow(ManageChildCurrentDevice.State())
    val currentDeviceLive = logic.deviceUserEntry.asFlow().filterNotNull().combine(
        logic.database.device().getAllDevicesFlow()
    ) { user, devices -> devices.firstOrNull { it.id == user.currentDevice } }
    val manageCurrentDeviceContent = combine(
        deviceAndUserRelatedData.asFlow(),
        manageCurrentDeviceTransitionLive,
        combine(
            logic.currentDeviceLogic.borrowedCurrentDevice,
            manageCurrentDeviceState,
            logic.database.config().getRememberedCurrentDeviceChoiceFlow()
        ) { a, b, c -> Triple(a, b, c) }
    ) { a, b, c  -> Triple(a, b, c) }.transform { params ->
        val (userAndDeviceRelatedData, transition, params2) = params
        val (borrowedCurrentDevice, state, rememberedChoice) = params2

        val userRelatedData = userAndDeviceRelatedData?.userRelatedData ?: return@transform

        val status = CurrentDeviceLogic.handleDeviceAsCurrentDevice(
            userAndDeviceRelatedData, borrowedCurrentDevice
        )

        emit(
            ManageChildCurrentDevice.buildActiveUserContent(
                userAndDeviceRelatedData.deviceRelatedData,
                userRelatedData,
                transition,
                manageCurrentDeviceTransitionLive,
                manageCurrentDeviceTransitionMutex,
                logic,
                api.activityCommand,
                api.authentication,
                currentDeviceLive,
                manageCurrentDeviceLaunchFunction,
                status,
                state,
                manageCurrentDeviceState::update,
                rememberedChoice
            )
        )
    }.shareIn(viewModelScope, SharingStarted.Lazily, 1)

    private val applyRememberedCurrentDeviceSelectionLock = AtomicBoolean(false)

    fun applyRememberedCurrentDeviceSelection() {
        val updated = applyRememberedCurrentDeviceSelectionLock.compareAndSet(false, true)

        if (!updated) return

        viewModelScope.launch {
            manageCurrentDeviceContent.first().actions.recallRememberedChoice()
        }
    }

    val content: LiveData<LockscreenContent> = object: MediatorLiveData<LockscreenContent>() {
        private val updateRunnable = Runnable { update() }
        private val timeModificationListener: () -> Unit = { update() }
        private val realTime = RealTime.newInstance()

        init {
            addSource(deviceAndUserRelatedData) { update() }
            addSource(batteryStatus) { update() }
            addSource(networkIdLive) { update() }
            addSource(packageAndActivityNameLiveInternal) { update() }
            addSource(borrowedCurrentDevice) { update() }
        }

        private fun update() {
            val deviceAndUserRelatedData = deviceAndUserRelatedData.value ?: return
            val batteryStatus = batteryStatus.value ?: return
            val networkId = networkIdLive.value
            val hasPremiumOrLocalMode = deviceAndUserRelatedData.deviceRelatedData.let { it.isLocalMode || it.isConnectedAndHasPremium }
            val (packageName, activityName) = packageAndActivityNameLiveInternal.value ?: return

            logic.realTimeLogic.getRealTime(realTime)

            if (deviceAndUserRelatedData.userRelatedData?.user?.type != UserType.Child) {
                value = LockscreenContent.Close; return
            }

            val appBaseHandling = AppBaseHandling.calculate(
                    foregroundAppPackageName = packageName,
                    foregroundAppActivityName = activityName,
                    deviceRelatedData = deviceAndUserRelatedData.deviceRelatedData,
                    userRelatedData = deviceAndUserRelatedData.userRelatedData,
                    pauseForegroundAppBackgroundLoop = false,
                    pauseCounting = false,
                    isSystemImageApp = logic.platformIntegration.isSystemImageApp(packageName)
            )

            val needsNetworkId = appBaseHandling.needsNetworkId

            if (needsNetworkId != needsNetworkIdLive.value) {
                needsNetworkIdLive.value = needsNetworkId
            }

            if (needsNetworkId && networkId == null) return

            handlingCache.reportStatus(
                    user = deviceAndUserRelatedData.userRelatedData,
                    assumeCurrentDevice = CurrentDeviceLogic.handleDeviceAsCurrentDevice(
                        deviceAndUserRelatedData,
                        borrowedCurrentDevice.value
                    ) is CurrentDeviceLogic.HandleAsCurrentDevice.Yes,
                    batteryStatus = batteryStatus,
                    timeInMillis = realTime.timeInMillis,
                    shouldTrustTimeTemporarily = realTime.shouldTrustTimeTemporarily,
                    currentNetworkId = networkId,
                    hasPremiumOrLocalMode = hasPremiumOrLocalMode
            )

            // @tag:app-allowance
            val appAllowed = io.timelimit.android.data.model.AppAllowance.activeUntil(
                deviceAndUserRelatedData.userRelatedData.user.appAllowances, packageName,
                realTime.timeInMillis, realTime.shouldTrustTimeTemporarily
            ) != null
            val blockingCategories = appBaseHandling.getCategories(AppBaseHandling.GetCategoriesPurpose.Blocking)

            if (blockingCategories.iterator().hasNext()) {
                val categoryHandlings = blockingCategories.map { handlingCache.get(it) }
                val blockingHandling = categoryHandlings.find { it.shouldBlockActivities(appAllowed) }

                value = if (blockingHandling == null) LockscreenContent.Close else LockscreenContent.Blocked.BlockedCategory(
                        deviceAndUserRelatedData = deviceAndUserRelatedData,
                        blockingHandling = blockingHandling,
                        level = appBaseHandling.level,
                        userRelatedData = deviceAndUserRelatedData.userRelatedData,
                        appPackageName = packageName,
                        appActivityName = activityName
                ).also { scheduleUpdate((blockingHandling.dependsOnMaxTime - realTime.timeInMillis)) }
            } else if (appBaseHandling is AppBaseHandling.BlockDueToNoCategory && !appAllowed) {
                value = LockscreenContent.Blocked.BlockDueToNoCategory(
                        userRelatedData = deviceAndUserRelatedData.userRelatedData,
                        deviceId = deviceAndUserRelatedData.deviceRelatedData.deviceEntry.id,
                        enableActivityLevelBlocking = deviceAndUserRelatedData.deviceRelatedData.deviceEntry.enableActivityLevelBlocking,
                        appPackageName = packageName,
                        appActivityName = activityName
                )
            } else {
                value = LockscreenContent.Close; return
            }
        }

        private fun scheduleUpdate(delay: Long) {
            logic.timeApi.cancelScheduledAction(updateRunnable)
            logic.timeApi.runDelayedByUptime(updateRunnable, delay)
        }

        private fun unscheduleUpdate() {
            logic.timeApi.cancelScheduledAction(updateRunnable)
        }

        override fun onActive() {
            super.onActive()

            logic.realTimeLogic.registerTimeModificationListener(timeModificationListener)

            update()
        }

        override fun onInactive() {
            super.onInactive()

            unscheduleUpdate()
            logic.realTimeLogic.unregisterTimeModificationListener(timeModificationListener)
        }
    }

    val missingNetworkIdPermission = networkIdLive.map { it is NetworkId.MissingPermission }

    val osClockInMillis = liveDataFromFunction { logic.timeApi.getCurrentTimeInMillis() }

    private val categoryIdForTasks = content.map {
        if (it is LockscreenContent.Blocked.BlockedCategory && it.blockingHandling.activityBlockingReason == BlockingReason.TimeOver)
            it.blockedCategoryId
        else null
    }.ignoreUnchanged()

    val blockedCategoryTasks = categoryIdForTasks.switchMap { categoryId ->
        if (categoryId != null)
            logic.database.childTasks().getTasksByCategoryId(categoryId)
        else liveDataFromNonNullValue(emptyList())
    }

    fun confirmLocalTime() {
        logic.realTimeLogic.confirmLocalTime()
    }

    fun allowAppTemporarily() {
        // this accesses the database directly because it is not synced
        Threads.database.submit {
            try {
                logic.database.runInTransaction {
                    logic.database.config().getOwnDeviceIdSync()?.let { deviceId ->
                        logic.database.temporarilyAllowedApp().addTemporarilyAllowedAppSync(TemporarilyAllowedApp(
                                deviceId = deviceId,
                                packageName = packageAndActivityNameLiveInternal.value!!.first
                        ))
                    }
                }
            } catch (ex: SQLiteConstraintException) {
                // ignore this
                //
                // this happens when touching that option more than once very fast
                // or if the device is under load
            }
        }
    }

    fun setEnablePickerMode(enable: Boolean) {
        Threads.database.execute {
            logic.database.config().setEnableAlternativeDurationSelectionSync(enable)
        }
    }
}

sealed class LockscreenContent {
    object Close: LockscreenContent()

    sealed class Blocked: LockscreenContent() {
        abstract val userRelatedData: UserRelatedData
        abstract val appPackageName: String
        abstract val appActivityName: String?
        abstract val enableActivityLevelBlocking: Boolean
        abstract val reason: BlockingReason
        abstract val level: BlockingLevel

        class BlockedCategory(
                val deviceAndUserRelatedData: DeviceAndUserRelatedData,
                val blockingHandling: CategoryItselfHandling,
                override val level: BlockingLevel,
                override val userRelatedData: UserRelatedData,
                override val appPackageName: String,
                override val appActivityName: String?
        ): Blocked() {
            val appCategoryTitle = blockingHandling.createdWithCategoryRelatedData.category.title
            override val reason = blockingHandling.activityBlockingReason
            val deviceId = deviceAndUserRelatedData.deviceRelatedData.deviceEntry.id
            val userId = userRelatedData.user.id
            val timeZone = userRelatedData.user.timeZone
            val blockedCategoryId = blockingHandling.createdWithCategoryRelatedData.category.id
            val deviceRelatedData = deviceAndUserRelatedData.deviceRelatedData
            val hasFullVersion = deviceRelatedData.isConnectedAndHasPremium || deviceRelatedData.isLocalMode
            override val enableActivityLevelBlocking = deviceAndUserRelatedData.deviceRelatedData.deviceEntry.enableActivityLevelBlocking
        }

        class BlockDueToNoCategory(
                override val userRelatedData: UserRelatedData,
                val deviceId: String,
                override val enableActivityLevelBlocking: Boolean,
                override val appPackageName: String,
                override val appActivityName: String?
        ): Blocked() {
            override val level: BlockingLevel = BlockingLevel.App
            override val reason: BlockingReason = BlockingReason.NotPartOfAnCategory
        }
    }
}