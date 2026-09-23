package io.timelimit.android.child

import android.content.Context
import androidx.lifecycle.asFlow
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.extensions.sortedCategories
import io.timelimit.android.data.model.AppAllowance
import io.timelimit.android.data.model.ChildRequest
import io.timelimit.android.data.model.ChildRequestAnswer
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.DeviceAndUserRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.BlockingReason
import io.timelimit.android.logic.CurrentDeviceLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import io.timelimit.android.sync.actions.CreateChildRequestAction
import io.timelimit.android.sync.actions.GrantByParentCodeAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.api.App
import io.timelimit.api.AppAccess
import io.timelimit.api.CategoryToday
import io.timelimit.api.ChildApi
import io.timelimit.api.CloseReason
import io.timelimit.api.GrantChoice
import io.timelimit.api.GrantScope
import io.timelimit.api.ModeWindow
import io.timelimit.api.ParentCode
import io.timelimit.api.Request
import io.timelimit.api.Today
import io.timelimit.api.WaitingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.TimeZone

// @tag:new-ui
class ChildApiOverLogic(private val logic: AppLogic) : ChildApi {
    companion object {
        private const val MINUTE = 60_000L

        private var instance: ChildApiOverLogic? = null

        fun with(context: Context): ChildApiOverLogic = instance
            ?: ChildApiOverLogic(DefaultAppLogic.with(context)).also { instance = it }
    }

    // ponytail: recomputes on a 15 s tick instead of scheduling by CategoryItselfHandling.dependsOnMaxTime
    private val tick = flow { while (true) { emit(Unit); delay(15_000) } }

    private val userAndDevice = logic.database.derivedDataDao().getUserAndDeviceRelatedDataLive().asFlow()
    private val users = logic.database.user().getAllUsersFlow()

    override fun appAccess(packageName: String): Flow<AppAccess> = combine(
        userAndDevice, users, logic.currentDeviceLogic.borrowedCurrentDevice, tick
    ) { data, users, _, _ ->
        computeAccess(data, users, packageName)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override val today: Flow<Today> = combine(userAndDevice, tick) { data, _ ->
        computeToday(data)
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // @tag:child-request
    override suspend fun ask(packageName: String, word: String) {
        val user = withContext(Dispatchers.IO) { logic.database.derivedDataDao().getUserAndDeviceRelatedDataSync() }
            ?.userRelatedData ?: return
        val categoryId = user.categoryApps.firstOrNull { it.appSpecifier.packageName == packageName }?.categoryId ?: ""

        ApplyActionUtil.applyAppLogicAction(
            action = CreateChildRequestAction(IdGenerator.generateId(), packageName, categoryId, word.take(CreateChildRequestAction.MAX_WORD_LENGTH)),
            appLogic = logic,
            ignoreIfDeviceIsNotConfigured = true
        )
    }

    // @tag:parent-code
    override suspend fun checkParentCode(code: String): ParentCode? {
        val secret = withContext(Dispatchers.IO) { logic.database.config().getParentCodeSecretSync() } ?: return null

        return ParentCodeTotp.matchingStep(secret, code, now().timeInMillis)?.let { ParentCode(code, it) }
    }

    // @tag:parent-code @tag:app-allowance
    override suspend fun grant(packageName: String, code: ParentCode, scope: GrantScope, until: Long) {
        val categoryId = when (scope) {
            GrantScope.App -> ""
            GrantScope.Category -> withContext(Dispatchers.IO) {
                logic.database.derivedDataDao().getUserAndDeviceRelatedDataSync()?.let { blockingCategory(it, packageName) }
            }?.createdWithCategoryRelatedData?.category?.id ?: return
        }

        ApplyActionUtil.applyAppLogicAction(
            action = GrantByParentCodeAction(
                code = code.code, step = code.step,
                grant = if (scope == GrantScope.App) ChildRequestAnswer.KIND_APP else ChildRequestAnswer.KIND_CATEGORY,
                packageName = if (scope == GrantScope.App) packageName else "", categoryId = categoryId, until = until
            ),
            appLogic = logic,
            ignoreIfDeviceIsNotConfigured = true
        )
    }

    private fun app(packageName: String) =
        App(packageName, logic.platformIntegration.getLocalAppTitle(packageName) ?: packageName)

    private fun now(): RealTime = RealTime.newInstance().also { logic.realTimeLogic.getRealTime(it) }

    private fun handlingCache(data: DeviceAndUserRelatedData, user: UserRelatedData, time: RealTime, base: AppBaseHandling?) =
        CategoryHandlingCache().apply {
            reportStatus(
                user = user,
                batteryStatus = logic.platformIntegration.getBatteryStatus(),
                shouldTrustTimeTemporarily = time.shouldTrustTimeTemporarily,
                timeInMillis = time.timeInMillis,
                assumeCurrentDevice = CurrentDeviceLogic.handleDeviceAsCurrentDevice(data, logic.currentDeviceLogic.borrowedCurrentDevice.value) is CurrentDeviceLogic.HandleAsCurrentDevice.Yes,
                currentNetworkId = if (base?.needsNetworkId == true) logic.platformIntegration.getCurrentNetworkId() else null,
                hasPremiumOrLocalMode = data.deviceRelatedData.let { it.isLocalMode || it.isConnectedAndHasPremium }
            )
        }

    private fun baseHandling(data: DeviceAndUserRelatedData, user: UserRelatedData, packageName: String) = AppBaseHandling.calculate(
        foregroundAppPackageName = packageName,
        foregroundAppActivityName = null,
        pauseForegroundAppBackgroundLoop = false,
        pauseCounting = false,
        userRelatedData = user,
        deviceRelatedData = data.deviceRelatedData,
        isSystemImageApp = logic.platformIntegration.isSystemImageApp(packageName)
    )

    private fun blockingCategory(data: DeviceAndUserRelatedData, packageName: String): CategoryItselfHandling? {
        val user = data.userRelatedData ?: return null
        val base = baseHandling(data, user, packageName)
        val cache = handlingCache(data, user, now(), base)

        return base.getCategories(AppBaseHandling.GetCategoriesPurpose.Blocking)
            .map { cache.get(it) }
            .find { it.shouldBlockActivities }
    }

    private fun computeAccess(
        data: DeviceAndUserRelatedData?,
        users: List<User>,
        packageName: String,
    ): AppAccess {
        val app = app(packageName)
        val user = data?.userRelatedData
        if (user == null || user.user.type != UserType.Child) return AppAccess.Open(app, null, null)

        val time = now()
        val base = baseHandling(data, user, packageName)
        val cache = handlingCache(data, user, time, base)
        val allowedUntil = AppAllowance.activeUntil(user.user.appAllowances, packageName, time.timeInMillis, time.shouldTrustTimeTemporarily)
        val categories = base.getCategories(AppBaseHandling.GetCategoriesPurpose.Blocking).map { cache.get(it) }
        val blocking = categories.find { it.shouldBlockActivities(allowedUntil != null) }
        val parentName = { id: String -> users.find { it.id == id }?.name ?: "" }
        val request = ChildRequestStates.forApp(user.user.childRequests, packageName, time.timeInMillis, parentName)
        val grant = if (logic.database.config().getParentCodeSecretSync() == null) null
        else GrantChoice(blocking?.createdWithCategoryRelatedData?.category?.title, dayEnd(user, time.timeInMillis))

        return when {
            blocking != null -> AppAccess.Closed(
                app = app,
                categoryTitle = blocking.createdWithCategoryRelatedData.category.title,
                reason = closeReason(blocking, time.timeInMillis),
                opensAt = opensAt(blocking, time.timeInMillis, user.timeZone),
                remainingToday = blocking.remainingTime?.includingExtraTime?.takeIf { it > 0 },
                request = request,
                grant = grant,
            )
            base is AppBaseHandling.BlockDueToNoCategory && allowedUntil == null -> AppAccess.Closed(
                app, null, CloseReason.NewApp, null, null, request, grant
            )
            else -> {
                val categoryUntil = categories.filter { it.areLimitsTemporarilyDisabled }
                    .maxOfOrNull { it.createdWithCategoryRelatedData.category.disableLimitsUntil.coerceAtLeast(user.user.disableLimitsUntil) }
                val until = listOfNotNull(allowedUntil, categoryUntil).maxOrNull()

                AppAccess.Open(app, until, until?.let { ChildRequestStates.answeredBy(user.user.childRequests, packageName, parentName) })
            }
        }
    }

    private fun dayEnd(user: UserRelatedData, now: Long): Long {
        val nowMinute = getMinuteOfWeek(now, user.timeZone)
        val minuteStart = now - now % MINUTE
        val untilMidnight = ModeClock.nextDayStart(nowMinute) - nowMinute

        return user.categories
            .mapNotNull { ModeClock.nextWindow(it.category.blockedMinutesInWeek.dataNotToModify, nowMinute, untilMidnight)?.first }
            .minOrNull()
            .let { minuteStart + (it ?: untilMidnight) * MINUTE }
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
    private fun opensAt(handling: CategoryItselfHandling, now: Long, timeZone: TimeZone): Long? {
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

    private fun computeToday(data: DeviceAndUserRelatedData?): Today {
        val time = now()
        val user = data?.userRelatedData

        if (user == null || user.user.type != UserType.Child) return Today(time.timeInMillis, emptyList(), emptyList(), null)

        val waiting = ChildRequestStates.waiting(user.user.childRequests, time.timeInMillis)
            .map { WaitingRequest(app(it.packageName), it.createdAt) }
        val cache = handlingCache(data, user, time, null)
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

/** How the child sees the requests of `users.data[].requests` (docs/specification/protocol-new-ui.md, section 1). */
// @tag:child-request
object ChildRequestStates {
    private const val SHOW_OUTCOME = 60 * 60 * 1000L

    fun forApp(requests: List<ChildRequest>, packageName: String, now: Long, parentName: (String) -> String): Request {
        val latest = requests.filter { it.packageName == packageName }.maxByOrNull { it.createdAt } ?: return Request.None
        val answer = latest.answer

        return when {
            latest.pending -> Request.Sending(latest.word)
            answer == null && now < latest.expiresAt -> Request.Sent(latest.createdAt, latest.word)
            answer == null -> if (now - latest.expiresAt < SHOW_OUTCOME) Request.Expired(latest.word) else Request.None
            answer.kind == ChildRequestAnswer.KIND_DENY && now - answer.at < SHOW_OUTCOME ->
                Request.Refused(answer.at, parentName(answer.parentUserId), answer.word.ifEmpty { null }, answer.repeatAfter)
            else -> Request.None
        }
    }

    fun waiting(requests: List<ChildRequest>, now: Long): List<ChildRequest> =
        requests.filter { it.pending || (it.answer == null && now < it.expiresAt) }

    fun answeredBy(requests: List<ChildRequest>, packageName: String, parentName: (String) -> String): String? =
        requests.filter { it.packageName == packageName && it.answer != null && it.answer.kind != ChildRequestAnswer.KIND_DENY }
            .maxByOrNull { it.answer!!.at }
            ?.let { parentName(it.answer!!.parentUserId) }
            ?.ifEmpty { null }
}
