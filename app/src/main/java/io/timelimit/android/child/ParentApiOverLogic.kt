package io.timelimit.android.child

import android.content.Context
import androidx.lifecycle.asFlow
import io.timelimit.android.data.extensions.sortedCategories
import io.timelimit.android.data.model.ChildRequestAnswer
import io.timelimit.android.data.model.Device
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.sync.actions.AddCategoryAppsAction
import io.timelimit.android.sync.actions.AnswerChildRequestAction
import io.timelimit.android.sync.actions.IncrementCategoryExtraTimeAction
import io.timelimit.android.sync.actions.ParentAction
import io.timelimit.android.sync.actions.SetAppRuleAction
import io.timelimit.android.sync.actions.UpdateCategoryDisableLimitsAction
import io.timelimit.android.sync.actions.UpdateCategoryTemporarilyBlockedAction
import io.timelimit.android.sync.actions.apply.ApplyActionParentDeviceAuthentication
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.android.sync.network.api.AppUsageRow
import io.timelimit.api.AnsweredLine
import io.timelimit.api.App
import io.timelimit.api.AppRuleLine
import io.timelimit.api.AppTime
import io.timelimit.api.AppUsage
import io.timelimit.api.CategoryRef
import io.timelimit.api.ChildHome
import io.timelimit.api.GrantScope
import io.timelimit.api.ModeWindow
import io.timelimit.api.NewAppLine
import io.timelimit.api.ParentApi
import io.timelimit.api.ParentCategory
import io.timelimit.api.ParentCodeNow
import io.timelimit.api.ParentHome
import io.timelimit.api.ParentRequest
import io.timelimit.api.TabletLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

// @tag:new-ui
class ParentApiOverLogic(private val logic: AppLogic) : ParentApi {
    companion object {
        private const val MINUTE = 60_000L
        private const val ONLINE = 2 * MINUTE
        private const val USAGE_REFRESH = MINUTE

        private var instance: ParentApiOverLogic? = null

        fun with(context: Context): ParentApiOverLogic = instance
            ?: ParentApiOverLogic(DefaultAppLogic.with(context)).also { instance = it }

        fun guessCategory(section: String, categories: List<CategoryRef>): CategoryRef? {
            val words = when (section) {
                "game" -> listOf("игр")
                "video" -> listOf("видео")
                "productivity", "news" -> listOf("учёб", "учеб")
                else -> return null
            }

            return categories.firstOrNull { category -> words.any { category.title.lowercase().contains(it) } }
        }
    }

    private val tick = flow { while (true) { emit(Unit); delay(15_000) } }
    private val refresh = MutableStateFlow(0)
    private val usage = MutableStateFlow<Pair<Long, Result<List<AppUsageRow>>>?>(null)

    override val home: Flow<ParentHome> = combine(
        logic.database.user().getAllUsersFlow(),
        logic.database.device().getAllDevicesFlow(),
        logic.database.app().getAllApps().asFlow(),
        logic.deviceStates,
        combine(tick, refresh, usage) { _, _, usage -> usage }
    ) { users, devices, apps, states, usage ->
        computeHome(users, devices, apps.associate { it.packageName to it.title }, states, usage)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override val parentCode: Flow<ParentCodeNow?> = flow {
        while (true) {
            val secret = withContext(Dispatchers.IO) { logic.database.config().getParentCodeSecretSync() }
            val now = now()
            val step = now / ParentCodeTotp.STEP_MILLIS

            emit(secret?.let { ParentCodeNow(ParentCodeTotp.codeAt(ParentCodeTotp.decodeBase32(it), step), (step + 1) * ParentCodeTotp.STEP_MILLIS) })
            delay(1_000)
        }
    }.distinctUntilChanged()

    private fun now(): Long = RealTime.newInstance().also { logic.realTimeLogic.getRealTime(it) }.timeInMillis

    private fun ownDevice(devices: List<Device>): Device? =
        logic.database.config().getOwnDeviceIdSync()?.let { id -> devices.find { it.id == id } }

    private fun cannotAct(users: List<User>, device: Device?): String? = when {
        device == null -> "Телефон не подключён к семье"
        users.find { it.id == device.currentUserId }?.type != UserType.Parent -> "На этом телефоне сейчас не родитель"
        !device.isUserKeptSignedIn -> "Телефон не хранит вход родителя: войдите в старом интерфейсе с галкой «Don't ask again at this device»"
        else -> null
    }

    private suspend fun loadUsage(childId: String, parentId: String, today: Int) {
        val result = runCatching {
            val server = logic.serverLogic.getServerConfigCoroutine()
            server.api.getAppUsage(server.deviceAuthToken, parentId, "device", childId, today - 6, today)
        }

        usage.value = now() to result
    }

    private suspend fun computeHome(
        users: List<User>,
        devices: List<Device>,
        titles: Map<String, String>,
        states: List<io.timelimit.android.data.model.DeviceState>,
        usage: Pair<Long, Result<List<AppUsageRow>>>?,
    ): ParentHome {
        val device = ownDevice(devices)
        val cannotAct = cannotAct(users, device)
        // ponytail: the first child only; a switcher in the header when a family has more
        val child = users.firstOrNull { it.type == UserType.Child } ?: return ParentHome(cannotAct, null)
        val data = logic.database.derivedDataDao().getUserRelatedDataSync(child.id) ?: return ParentHome(cannotAct, null)
        val now = now()
        val zone = data.timeZone.toZoneId()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayEpoch = today.toEpochDay().toInt()

        if (device != null && (usage == null || now - usage.first > USAGE_REFRESH)) {
            loadUsage(child.id, device.currentUserId, todayEpoch)
        }

        fun app(packageName: String) = App(packageName, titles[packageName] ?: child.newApps.find { it.packageName == packageName }?.title ?: packageName)
        fun tabletName(deviceId: String) = devices.find { it.id == deviceId }?.name

        val cache = CategoryHandlingCache().apply {
            reportStatus(data, BatteryStatus.assumeFull, true, now, true, null, true)
        }
        val nowMinute = getMinuteOfWeek(now, data.timeZone)
        val minuteStart = now - now % MINUTE
        val sorted = data.sortedCategories()
        val categories = sorted.map { (depth, category) -> parentCategory(category, depth, cache, todayEpoch, today, nowMinute, minuteStart, now) }
        val refs = categories.map { it.ref }

        val windows = data.categories.filter { it.category.parentCategoryId.isEmpty() }.map { it.category.blockedMinutesInWeek.dataNotToModify }
        val modeNow = windows.filter { it[nowMinute] }
            .mapNotNull { ModeClock.minutesUntilOpen(it, nowMinute) }.minOrNull()
            ?.let { ModeWindow(null, minuteStart, minuteStart + it * MINUTE) }
        val nextMode = windows.mapNotNull { ModeClock.nextWindow(it, nowMinute, ModeClock.DAY) }.minByOrNull { it.first }
            ?.let { (start, length) -> ModeWindow(null, minuteStart + start * MINUTE, minuteStart + (start + length) * MINUTE) }
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val morning = modeNow?.until ?: nextMode?.until ?: (midnight + 7 * 60 * MINUTE)
        val dayEnd = nextMode?.from?.takeIf { it < midnight } ?: midnight

        val rows = usage?.second?.getOrNull()
        val appUsage = when {
            rows != null -> {
                val categoryOf = { packageName: String ->
                    data.categoryApps.find { it.appSpecifier.packageName == packageName }?.categoryId
                        ?.let { id -> refs.find { it.id == id }?.title }
                }
                fun lines(filter: (AppUsageRow) -> Boolean) = rows.filter(filter).groupBy { it.packageName }
                    .map { (packageName, items) ->
                        AppTime(app(packageName), items.sumOf { it.ms }, categoryOf(packageName),
                            child.appRules.find { it.packageName == packageName }?.let { AppRuleLine(it.days, it.limitMinutes) })
                    }
                    .sortedByDescending { it.ms }

                AppUsage.Known(
                    today = lines { it.day == todayEpoch },
                    week = lines { true },
                    weekByDay = rows.groupBy { it.packageName }.mapValues { (_, items) ->
                        (todayEpoch - 6..todayEpoch).map { day -> items.filter { it.day == day }.sumOf { it.ms } }
                    }
                )
            }
            usage == null -> AppUsage.Unknown("загружаем…")
            else -> AppUsage.Unknown("сервер не отдал время по приложениям: ${usage.second.exceptionOrNull()?.message ?: "нет связи"}")
        }
        val usageToday = (appUsage as? AppUsage.Known)?.today.orEmpty()

        val requests = child.childRequests
            .filter { it.answer == null && now < it.expiresAt }
            .sortedByDescending { it.createdAt }
            .map { request ->
                val categoryId = request.categoryId.ifEmpty { child.categoryForNotAssignedApps }

                ParentRequest(
                    id = request.id,
                    app = app(request.packageName),
                    at = request.createdAt,
                    tabletName = tabletName(request.deviceId),
                    word = request.word,
                    appToday = usageToday.find { it.app.packageName == request.packageName }?.ms,
                    category = categories.find { it.ref.id == categoryId },
                    rule = child.appRules.find { it.packageName == request.packageName }?.let { AppRuleLine(it.days, it.limitMinutes) },
                )
            }
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val answered = child.childRequests.mapNotNull { request ->
            request.answer?.takeIf { it.at >= todayStart }?.let {
                AnsweredLine(app(request.packageName), it.at, it.kind != ChildRequestAnswer.KIND_DENY, it.until)
            }
        }.sortedByDescending { it.at }

        val tablets = devices.filter { it.currentUserId == child.id }.map { tablet ->
            val state = states.find { it.deviceId == tablet.id }

            TabletLine(tablet.name, state != null && now - state.seen < ONLINE, state?.seen ?: 0, state?.app?.takeIf { it.isNotEmpty() }?.let { app(it) })
        }

        return ParentHome(cannotAct, ChildHome(
            id = child.id,
            name = child.name,
            now = now,
            modeNow = modeNow,
            nextMode = nextMode,
            morning = morning,
            dayEnd = dayEnd,
            usage = appUsage,
            newApps = child.newApps.map { NewAppLine(app(it.packageName), it.installedAt, tabletName(it.deviceId), guessCategory(it.section, refs)) },
            categories = categories,
            requests = requests,
            answeredToday = answered,
            tablets = tablets,
        ))
    }

    private fun parentCategory(
        category: CategoryRelatedData, depth: Int, cache: CategoryHandlingCache,
        todayEpoch: Int, today: LocalDate, nowMinute: Int, minuteStart: Long, now: Long,
    ): ParentCategory {
        val handling = cache.get(category.category.id)
        val blocked = category.category.blockedMinutesInWeek.dataNotToModify
        val dayBit = 1 shl (today.dayOfWeek.value - 1)
        val limit = category.rules.filter { it.appliesToWholeDay && it.dayMask.toInt() and dayBit != 0 && it.maximumTimeInMillis > 0 }
            .minOfOrNull { it.maximumTimeInMillis.toLong() }
        val tempBlocked = category.category.temporarilyBlocked &&
                (category.category.temporarilyBlockedEndTime == 0L || category.category.temporarilyBlockedEndTime > now)

        return ParentCategory(
            ref = CategoryRef(category.category.id, category.category.title),
            depth = depth,
            remaining = handling.remainingTime?.includingExtraTime,
            usedToday = category.usedTimes.filter { it.dayOfEpoch == todayEpoch && it.startTimeOfDay == 0 && it.endTimeOfDay == 24 * 60 - 1 }
                .sumOf { it.usedMillis },
            limit = limit,
            closedByModeUntil = if (blocked[nowMinute]) ModeClock.minutesUntilOpen(blocked, nowMinute)?.let { minuteStart + it * MINUTE } else null,
            closedByParentUntil = category.category.temporarilyBlockedEndTime.takeIf { tempBlocked && it != 0L },
            closedByParent = tempBlocked,
            allowedUntil = category.category.disableLimitsUntil.takeIf { it > now },
        )
    }

    private suspend fun dispatch(actions: List<ParentAction>) {
        actions.forEach { action ->
            ApplyActionUtil.applyParentAction(
                action = action,
                database = logic.database,
                authentication = ApplyActionParentDeviceAuthentication,
                syncUtil = logic.syncUtil,
                platformIntegration = logic.platformIntegration
            )
        }
        refresh.value++
    }

    private suspend fun childData(): UserRelatedData? = withContext(Dispatchers.IO) {
        logic.database.user().getAllUsersSync().firstOrNull { it.type == UserType.Child }
            ?.let { logic.database.derivedDataDao().getUserRelatedDataSync(it.id) }
    }

    override suspend fun addTime(categoryId: String, minutes: Int) {
        val data = childData() ?: return
        val category = data.categoryById[categoryId]?.category ?: return
        val now = now()
        val duringMode = category.blockedMinutesInWeek.dataNotToModify[getMinuteOfWeek(now, data.timeZone)] || category.temporarilyBlocked

        dispatch(listOf(
            if (duringMode) UpdateCategoryDisableLimitsAction(categoryId, category.disableLimitsUntil.coerceAtLeast(now) + minutes * MINUTE)
            else IncrementCategoryExtraTimeAction(
                categoryId, minutes * MINUTE,
                Instant.ofEpochMilli(now).atZone(data.timeZone.toZoneId()).toLocalDate().toEpochDay().toInt()
            )
        ))
    }

    override suspend fun closeCategory(categoryId: String, until: Long) {
        dispatch(listOf(UpdateCategoryTemporarilyBlockedAction(categoryId, true, until)))
    }

    // the same as the web console: every top-level category of the child
    override suspend fun closeAll(until: Long) {
        val data = childData() ?: return

        dispatch(data.categories.filter { it.category.parentCategoryId.isEmpty() }
            .map { UpdateCategoryTemporarilyBlockedAction(it.category.id, true, until) })
    }

    override suspend fun answer(requestId: String, scope: GrantScope, until: Long) {
        dispatch(listOf(AnswerChildRequestAction(
            requestId, if (scope == GrantScope.App) ChildRequestAnswer.KIND_APP else ChildRequestAnswer.KIND_CATEGORY, until, ""
        )))
    }

    override suspend fun deny(requestId: String) {
        dispatch(listOf(AnswerChildRequestAction(requestId, ChildRequestAnswer.KIND_DENY, 0, "")))
    }

    override suspend fun moveApp(packageName: String, categoryId: String) {
        dispatch(listOf(AddCategoryAppsAction(categoryId, listOf(packageName))))
    }

    override suspend fun setAppRule(packageName: String, days: Int, limitMinutes: Int) {
        val data = childData() ?: return

        dispatch(listOf(SetAppRuleAction(data.user.id, packageName, days, limitMinutes)))
    }
}
