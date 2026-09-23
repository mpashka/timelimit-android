package io.timelimit.android.child

import android.content.Context
import androidx.lifecycle.asFlow
import io.timelimit.android.data.extensions.sortedCategories
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.DeviceAndUserRelatedData
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.BlockingReason
import io.timelimit.android.logic.CurrentDeviceLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import io.timelimit.api.App
import io.timelimit.api.AppAccess
import io.timelimit.api.CategoryToday
import io.timelimit.api.ChildApi
import io.timelimit.api.CloseReason
import io.timelimit.api.ModeWindow
import io.timelimit.api.Request
import io.timelimit.api.Today
import io.timelimit.api.WaitingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update

// @tag:new-ui
class ChildApiOverLogic(private val logic: AppLogic) : ChildApi {
    companion object {
        private const val MINUTE = 60_000L
        private const val REQUEST_LIFETIME = 30 * MINUTE

        private var instance: ChildApiOverLogic? = null

        fun with(context: Context): ChildApiOverLogic = instance
            ?: ChildApiOverLogic(DefaultAppLogic.with(context)).also { instance = it }
    }

    // ponytail: requests live only in this process and never reach a parent;
    // replace with the request of docs/specification/protocol-new-ui.md once the server has it
    // @tag:child-request
    private val requests = MutableStateFlow<Map<String, Request>>(emptyMap())

    // ponytail: recomputes on a 15 s tick instead of scheduling by CategoryItselfHandling.dependsOnMaxTime
    private val tick = flow { while (true) { emit(Unit); delay(15_000) } }

    private val userAndDevice = logic.database.derivedDataDao().getUserAndDeviceRelatedDataLive().asFlow()

    override fun appAccess(packageName: String): Flow<AppAccess> = combine(
        userAndDevice, logic.currentDeviceLogic.borrowedCurrentDevice, requests, tick
    ) { data, borrowed, requests, _ ->
        computeAccess(data, borrowed, packageName, requests[packageName])
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val today: Flow<Today> = combine(userAndDevice, requests, tick) { data, requests, _ ->
        computeToday(data, requests)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override suspend fun ask(packageName: String, word: String) {
        requests.update { it + (packageName to Request.Sent(logic.timeApi.getCurrentTimeInMillis(), word)) }
    }

    private fun app(packageName: String) =
        App(packageName, logic.platformIntegration.getLocalAppTitle(packageName) ?: packageName)

    private fun now(): RealTime = RealTime.newInstance().also { logic.realTimeLogic.getRealTime(it) }

    private fun liveRequest(request: Request?, now: Long): Request = when {
        request == null -> Request.None
        request is Request.Sent && now - request.at > REQUEST_LIFETIME -> Request.Expired(request.word)
        else -> request
    }

    private fun computeAccess(
        data: DeviceAndUserRelatedData?,
        borrowed: CurrentDeviceLogic.BorrowedCurrentDevice?,
        packageName: String,
        request: Request?,
    ): AppAccess {
        val app = app(packageName)
        val user = data?.userRelatedData
        if (user == null || user.user.type != UserType.Child) return AppAccess.Open(app, null, null)

        val time = now()
        val base = AppBaseHandling.calculate(
            foregroundAppPackageName = packageName,
            foregroundAppActivityName = null,
            pauseForegroundAppBackgroundLoop = false,
            pauseCounting = false,
            userRelatedData = user,
            deviceRelatedData = data.deviceRelatedData,
            isSystemImageApp = logic.platformIntegration.isSystemImageApp(packageName)
        )
        val cache = CategoryHandlingCache().apply {
            reportStatus(
                user = user,
                batteryStatus = logic.platformIntegration.getBatteryStatus(),
                shouldTrustTimeTemporarily = time.shouldTrustTimeTemporarily,
                timeInMillis = time.timeInMillis,
                assumeCurrentDevice = CurrentDeviceLogic.handleDeviceAsCurrentDevice(data, borrowed) is CurrentDeviceLogic.HandleAsCurrentDevice.Yes,
                currentNetworkId = if (base.needsNetworkId) logic.platformIntegration.getCurrentNetworkId() else null,
                hasPremiumOrLocalMode = data.deviceRelatedData.let { it.isLocalMode || it.isConnectedAndHasPremium }
            )
        }
        val blocking = base.getCategories(AppBaseHandling.GetCategoriesPurpose.Blocking)
            .map { cache.get(it) }
            .find { it.shouldBlockActivities }
        val liveRequest = liveRequest(request, time.timeInMillis)

        return when {
            blocking != null -> AppAccess.Closed(
                app = app,
                categoryTitle = blocking.createdWithCategoryRelatedData.category.title,
                reason = closeReason(blocking, time.timeInMillis),
                opensAt = opensAt(blocking, time.timeInMillis, user.timeZone),
                remainingToday = blocking.remainingTime?.includingExtraTime?.takeIf { it > 0 },
                request = liveRequest,
            )
            base is AppBaseHandling.BlockDueToNoCategory -> AppAccess.Closed(
                app, null, CloseReason.NewApp, null, null, liveRequest
            )
            else -> AppAccess.Open(app, null, null)
        }
    }

    private fun closeReason(handling: CategoryItselfHandling, now: Long): CloseReason = when (handling.activityBlockingReason) {
        BlockingReason.TimeOver -> CloseReason.LimitOver
        BlockingReason.TimeOverExtraTimeCanBeUsedLater -> CloseReason.ExtraTimeLater(handling.createdWithExtraTime)
        BlockingReason.BlockedAtThisTime -> CloseReason.Mode(null)
        BlockingReason.SessionDurationLimit -> handling.createdWithCategoryRelatedData.durations
            .filter { it.lastUsage + it.sessionPauseDuration > now }
            .maxByOrNull { it.lastSessionDuration }
            ?.let { CloseReason.Break(it.maxSessionDuration.toLong(), it.sessionPauseDuration.toLong()) }
            ?: CloseReason.Break(0, 0)
        BlockingReason.BatteryLimit -> CloseReason.LowBattery
        BlockingReason.MissingRequiredNetwork -> CloseReason.WifiRequired
        BlockingReason.ForbiddenNetwork -> CloseReason.ForbiddenNetwork
        BlockingReason.MissingNetworkCheckPermission -> CloseReason.NoNetworkPermission
        BlockingReason.MissingNetworkTime -> CloseReason.NoExactTime
        BlockingReason.RequiresCurrentDevice -> CloseReason.OtherDevice
        BlockingReason.NotPartOfAnCategory -> CloseReason.NewApp
        BlockingReason.TemporarilyBlocked, BlockingReason.NotificationsAreBlocked, BlockingReason.None -> CloseReason.ClosedByParent
    }

    // ponytail: ignores rules limited to part of a day and a zero limit on the next day;
    // the full "when does it open" function is in docs/specification/child-ui.md, "До скольки"
    private fun opensAt(handling: CategoryItselfHandling, now: Long, timeZone: java.util.TimeZone): Long? {
        val category = handling.createdWithCategoryRelatedData.category
        val blocked = category.blockedMinutesInWeek.dataNotToModify
        val nowMinute = getMinuteOfWeek(now, timeZone)
        val minuteStart = now - now % MINUTE

        return when (handling.activityBlockingReason) {
            BlockingReason.TimeOver -> {
                val nextDay = ModeClock.nextDayStart(nowMinute)
                ModeClock.minutesUntilOpen(blocked, nextDay % ModeClock.WEEK)
                    ?.let { minuteStart + (nextDay - nowMinute + it) * MINUTE }
            }
            BlockingReason.BlockedAtThisTime -> ModeClock.minutesUntilOpen(blocked, nowMinute)
                ?.takeIf { it > 0 }
                ?.let { minuteStart + it * MINUTE }
            BlockingReason.TemporarilyBlocked -> category.temporarilyBlockedEndTime.takeIf { it != 0L }
            BlockingReason.SessionDurationLimit -> handling.createdWithCategoryRelatedData.durations
                .map { it.lastUsage + it.sessionPauseDuration }
                .filter { it > now }
                .minOrNull()
            else -> null
        }
    }

    private fun computeToday(data: DeviceAndUserRelatedData?, requests: Map<String, Request>): Today {
        val time = now()
        val user = data?.userRelatedData
        val waiting = requests.mapNotNull { (packageName, request) ->
            (liveRequest(request, time.timeInMillis) as? Request.Sent)?.let { WaitingRequest(app(packageName), it.at) }
        }

        if (user == null || user.user.type != UserType.Child) return Today(time.timeInMillis, emptyList(), waiting, null)

        val cache = CategoryHandlingCache().apply {
            reportStatus(
                user = user,
                batteryStatus = logic.platformIntegration.getBatteryStatus(),
                shouldTrustTimeTemporarily = time.shouldTrustTimeTemporarily,
                timeInMillis = time.timeInMillis,
                assumeCurrentDevice = true,
                currentNetworkId = null,
                hasPremiumOrLocalMode = false
            )
        }
        val nowMinute = getMinuteOfWeek(time.timeInMillis, user.timeZone)
        val minuteStart = time.timeInMillis - time.timeInMillis % MINUTE
        val appsByCategory = user.categoryApps.groupBy({ it.categoryId }, { it.appSpecifier.packageName })

        val categories = user.sortedCategories().map { (_, category) ->
            val handling = cache.get(category.category.id)

            CategoryToday(
                title = category.category.title,
                remaining = handling.remainingTime?.includingExtraTime,
                closedNow = handling.shouldBlockActivities,
                apps = appsByCategory[category.category.id].orEmpty().distinct()
                    .mapNotNull { packageName -> logic.platformIntegration.getLocalAppTitle(packageName)?.let { App(packageName, it) } }
            )
        }
        val nextMode = user.categories
            .mapNotNull { ModeClock.nextWindow(it.category.blockedMinutesInWeek.dataNotToModify, nowMinute, ModeClock.DAY) }
            .minByOrNull { it.first }
            ?.let { (start, length) -> ModeWindow(null, minuteStart + start * MINUTE, minuteStart + (start + length) * MINUTE) }

        return Today(time.timeInMillis, categories, waiting, nextMode)
    }
}
