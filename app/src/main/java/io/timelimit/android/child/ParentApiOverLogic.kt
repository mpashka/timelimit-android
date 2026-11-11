package io.timelimit.android.child

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.asFlow
import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.extensions.sortedCategories
import io.timelimit.android.data.model.AppAllowance
import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.ChildRequestAnswer
import io.timelimit.android.data.model.Device
import io.timelimit.android.data.model.User
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.integration.platform.BatteryStatus
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache
import io.timelimit.android.sync.actions.AddCategoryAppsAction
import io.timelimit.android.sync.actions.AnswerChildRequestAction
import io.timelimit.android.sync.actions.CreateTimeLimitRuleAction
import io.timelimit.android.sync.actions.DeleteTimeLimitRuleAction
import io.timelimit.android.sync.actions.IncrementCategoryExtraTimeAction
import io.timelimit.android.sync.actions.ParentAction
import io.timelimit.android.sync.actions.SetAppRuleAction
import io.timelimit.android.sync.actions.UpdateCategoryDisableLimitsAction
import io.timelimit.android.sync.actions.UpdateCategoryTitleAction
import io.timelimit.android.sync.actions.UpdateCategoryTemporarilyBlockedAction
import io.timelimit.android.sync.actions.apply.ApplyActionParentDeviceAuthentication
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.android.sync.network.api.AppIconRow
import io.timelimit.android.sync.network.api.AppUsageRow
import io.timelimit.android.sync.network.api.BadRequestHttpError
import io.timelimit.android.sync.network.api.ConflictHttpError
import io.timelimit.android.sync.network.api.ForbiddenHttpError
import io.timelimit.android.sync.network.api.GoneHttpError
import io.timelimit.android.sync.network.api.NotFoundHttpError
import io.timelimit.android.sync.network.api.TooManyRequestsHttpError
import io.timelimit.android.sync.network.api.UnauthorizedHttpError
import io.timelimit.api.AllowanceLine
import io.timelimit.api.AnsweredLine
import io.timelimit.android.sync.actions.RemoveCategoryAppsAction
import io.timelimit.android.sync.actions.SetCategoryExtraTimeAction
import io.timelimit.android.sync.actions.SetAppAllowanceAction
import io.timelimit.android.sync.actions.UpdateUserUrlFilterAction
import io.timelimit.android.data.model.UserUrlFilter
import io.timelimit.api.Undo
import io.timelimit.api.Sites
import io.timelimit.api.ScheduleSpec
import io.timelimit.api.ScheduleLine
import io.timelimit.api.ChildRef
import io.timelimit.api.CannotAct
import io.timelimit.api.App
import io.timelimit.api.AppRuleLine
import io.timelimit.api.AppTime
import io.timelimit.api.AppUsage
import io.timelimit.api.appsTimeOfCategory
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

// @tag:new-ui
class ParentApiOverLogic(private val logic: AppLogic) : ParentApi {
    companion object {
        private const val MINUTE = 60_000L
        private const val ONLINE = 2 * MINUTE
        private const val USAGE_REFRESH = MINUTE
        private const val ICONS_AT_ONCE = 500

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
    private val usage = MutableStateFlow<Triple<String, Long, Result<List<AppUsageRow>>>?>(null)
    private val selectedChild = MutableStateFlow<String?>(null)
    // @tag:app-icon
    private val icons = MutableStateFlow<Map<String, AppIconRow>>(emptyMap())
    /** asked once with an answer: a package the server has no icon of is not asked again until the app restarts */
    private val iconsAsked = mutableSetOf<String>()
    private var iconsFailedAt = 0L

    override val appIcons: Flow<Map<String, ByteArray>> = icons.map { rows -> rows.mapValues { it.value.icon } }

    override fun selectChild(childId: String) {
        selectedChild.value = childId
        refresh.value++
    }

    override val home: Flow<ParentHome> = combine(
        logic.database.user().getAllUsersFlow(),
        logic.database.device().getAllDevicesFlow(),
        logic.database.app().getAllApps().asFlow(),
        logic.deviceStates,
        combine(tick, refresh, usage, selectedChild, icons) { _, _, usage, selected, icons -> Triple(usage, selected, icons) }
    ) { users, devices, apps, states, (usage, selected, icons) ->
        computeHome(users, devices, apps.associate { it.packageName to it.title }, states, usage, selected, icons)
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

    private fun cannotAct(users: List<User>, device: Device?): CannotAct? = when {
        device == null -> CannotAct.NotConnected
        users.find { it.id == device.currentUserId }?.type != UserType.Parent -> CannotAct.NotParent
        !device.isUserKeptSignedIn -> CannotAct.NotKeptSignedIn
        else -> null
    }

    private suspend fun loadUsage(childId: String, parentId: String, today: Int) {
        val result = runCatching {
            val server = logic.serverLogic.getServerConfigCoroutine()
            server.api.getAppUsage(server.deviceAuthToken, parentId, "device", childId, today - 6, today)
        }

        usage.value = Triple(childId, now(), result)
    }

    /** Icons of [home]'s apps that are not installed here; a failed request is tried again after a minute. */
    // @tag:app-icon
    private suspend fun loadIcons(home: ChildHome, parentId: String) {
        val missing = packagesOf(home).filterNot { it in iconsAsked || isInstalled(it) }
        if (missing.isEmpty() || now() - iconsFailedAt < USAGE_REFRESH) return

        val result = runCatching {
            val server = logic.serverLogic.getServerConfigCoroutine()
            missing.chunked(ICONS_AT_ONCE).flatMap { server.api.getAppIcons(server.deviceAuthToken, parentId, "device", it) }
        }

        result.onSuccess { rows ->
            iconsAsked += missing
            if (rows.isNotEmpty()) icons.value = icons.value + rows.associateBy { it.packageName }
        }.onFailure { iconsFailedAt = now() }
    }

    private fun packagesOf(home: ChildHome): Set<String> = buildSet {
        (home.usage as? AppUsage.Known)?.let { usage -> usage.week.forEach { add(it.app.packageName) } }
        home.categories.forEach { category -> category.apps.forEach { add(it.app.packageName) } }
        home.newApps.forEach { add(it.app.packageName) }
        home.requests.forEach { add(it.app.packageName) }
        home.answeredToday.forEach { add(it.app.packageName) }
        home.allowances.forEach { add(it.app.packageName) }
        home.tablets.forEach { tablet -> tablet.appNow?.let { add(it.packageName) } }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        logic.context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun computeHome(
        users: List<User>,
        devices: List<Device>,
        titles: Map<String, String>,
        states: List<io.timelimit.android.data.model.DeviceState>,
        loaded: Triple<String, Long, Result<List<AppUsageRow>>>?,
        selected: String?,
        remote: Map<String, AppIconRow>,
    ): ParentHome {
        val device = ownDevice(devices)
        val cannotAct = cannotAct(users, device)
        val children = users.filter { it.type == UserType.Child }
        val refs = children.map { ChildRef(it.id, it.name) }
        val child = children.find { it.id == selected } ?: children.firstOrNull() ?: return ParentHome(cannotAct, refs, null)
        val data = logic.database.derivedDataDao().getUserRelatedDataSync(child.id) ?: return ParentHome(cannotAct, refs, null)
        val usage = loaded?.takeIf { it.first == child.id }?.let { it.second to it.third }
        val now = now()
        val zone = data.timeZone.toZoneId()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayEpoch = today.toEpochDay().toInt()

        if (device != null && cannotAct == null && (usage == null || now - usage.first > USAGE_REFRESH)) {
            loadUsage(child.id, device.currentUserId, todayEpoch)
        }

        fun app(packageName: String) = App(packageName, titles[packageName] ?: child.newApps.find { it.packageName == packageName }?.title
            ?: remote[packageName]?.title?.takeIf { it.isNotEmpty() } ?: packageName)
        fun tabletName(deviceId: String) = devices.find { it.id == deviceId }?.name

        val cache = CategoryHandlingCache().apply {
            reportStatus(data, BatteryStatus.assumeFull, true, now, true, null, true)
        }
        val nowMinute = getMinuteOfWeek(now, data.timeZone)
        val minuteStart = now - now % MINUTE
        val sorted = data.sortedCategories()
        val ownTime = sorted.map { (depth, category) ->
            parentCategory(category, depth, category.category.parentCategoryId.takeIf { data.categoryById.containsKey(it) },
                CategoryChain(data, category.category.id, cache), todayEpoch, today, nowMinute, minuteStart, now)
        }
        val categoryRefs = ownTime.map { it.ref }

        fun appLine(packageName: String, ms: Long, byTablet: Map<String, Long>, category: CategoryRef?) = AppTime(
            app = app(packageName),
            ms = ms,
            category = category,
            byTablet = byTablet,
            rule = child.appRules.find { it.packageName == packageName }?.let { AppRuleLine(it.days, it.limitMinutes) },
            allowedUntil = AppAllowance.activeUntil(child.appAllowances, packageName, now, trustTime = true),
        )

        val windows = data.categories.filter { it.category.parentCategoryId.isEmpty() }.map { it to Schedules.blockedMinutes(it) }
        val modeNow = windows.filter { it.second[nowMinute] }
            .mapNotNull { (category, bits) -> ModeClock.minutesUntilOpen(bits, nowMinute)?.let { it to category } }.minByOrNull { it.first }
            ?.let { (length, category) -> ModeWindow(Schedules.kindAt(category, nowMinute)?.toApi(), minuteStart, minuteStart + length * MINUTE) }
        val nextMode = windows.mapNotNull { (category, bits) -> ModeClock.nextWindow(bits, nowMinute, ModeClock.DAY)?.let { it to category } }.minByOrNull { it.first.first }
            ?.let { (window, category) ->
                ModeWindow(Schedules.kindAt(category, nowMinute + window.first)?.toApi(), minuteStart + window.first * MINUTE, minuteStart + (window.first + window.second) * MINUTE)
            }
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val morning = modeNow?.until ?: nextMode?.until ?: (midnight + 7 * 60 * MINUTE)
        val dayEnd = nextMode?.from?.takeIf { it < midnight } ?: midnight

        val rows = usage?.second?.getOrNull()
        val appUsage = when {
            rows != null -> {
                val categoryOf = { packageName: String ->
                    (data.categoryApps.find { it.appSpecifier.packageName == packageName }?.categoryId ?: child.categoryForNotAssignedApps)
                        .let { id -> categoryRefs.find { it.id == id } }
                }
                fun lines(filter: (AppUsageRow) -> Boolean) = rows.filter(filter).groupBy { it.packageName }
                    .map { (packageName, items) ->
                        appLine(packageName, items.sumOf { it.ms },
                            items.groupBy { it.deviceId }.mapValues { (_, ofTablet) -> ofTablet.sumOf { it.ms } },
                            categoryOf(packageName))
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
            cannotAct != null -> AppUsage.NeedsSignIn
            usage == null -> AppUsage.Loading
            else -> usage.second.exceptionOrNull().let { ex ->
                AppUsage.Failed(
                    httpCode = when (ex) {
                        is BadRequestHttpError -> 400
                        is UnauthorizedHttpError -> 401
                        is ForbiddenHttpError -> 403
                        is NotFoundHttpError -> 404
                        is ConflictHttpError -> 409
                        is GoneHttpError -> 410
                        is TooManyRequestsHttpError -> 429
                        else -> null
                    },
                    detail = listOfNotNull(ex?.javaClass?.simpleName, ex?.message?.takeIf { it.isNotBlank() }).joinToString(": ").ifEmpty { "?" }
                )
            }
        }
        val usageToday = (appUsage as? AppUsage.Known)?.today.orEmpty()
        val assignedPackages = data.categoryApps.groupBy({ it.categoryId }, { it.appSpecifier.packageName })

        // apps whose time went to the category join the assigned ones: the category for apps without
        // one has nothing assigned at all, and an app assigned on one tablet only has another specifier
        fun appsOfCategory(ref: CategoryRef): List<AppTime> {
            val used = usageToday.filter { it.category?.id == ref.id }
            val usedPackages = used.map { it.app.packageName }.toSet()

            return used + assignedPackages[ref.id].orEmpty().distinct().filterNot { it in usedPackages }
                .map { appLine(it, 0, emptyMap(), ref) }
        }
        // @tag:category-time
        val categories = ownTime.map { category ->
            val usedToday = if (category.limit != null) category.usedToday
            else maxOf(category.usedToday, appsTimeOfCategory(ownTime, category.ref.id, usageToday))

            category.copy(usedToday = usedToday, apps = appsOfCategory(category.ref))
        }

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

            TabletLine(
                id = tablet.id,
                name = tablet.name,
                online = state != null && now - state.seen < ONLINE,
                seen = state?.seen ?: 0,
                appNow = state?.app?.takeIf { it.isNotEmpty() }?.let { app(it) },
                todayMs = rows?.filter { it.deviceId == tablet.id && it.day == todayEpoch }?.sumOf { it.ms },
            )
        }

        val bans = Schedules.readBans(data.categories)
        val schedules = Schedules.Kind.entries.map { kind ->
            val ofKind = bans.filter { Schedules.kindOf(it.start, it.end) == kind }
            val main = ofKind.maxByOrNull { it.categoryIds.size }
            val spec = main ?: Schedules.defaults.getValue(kind).copy(categoryIds = Schedules.defaultCategories(kind, data.categories))

            ScheduleLine(
                kind = kind.toApi(),
                on = main != null,
                spec = ScheduleSpec(spec.start, spec.end, spec.days, ofKind.flatMap { it.categoryIds }.distinct().ifEmpty { spec.categoryIds }),
                window = listOfNotNull(modeNow, nextMode).firstOrNull { it.kind == kind.toApi() },
                exceptions = ofKind.size - 1,
            )
        }

        val home = ChildHome(
            id = child.id,
            name = child.name,
            now = now,
            modeNow = modeNow,
            nextMode = nextMode,
            morning = morning,
            dayEnd = dayEnd,
            usage = appUsage,
            newApps = child.newApps.map { NewAppLine(app(it.packageName), it.installedAt, tabletName(it.deviceId), guessCategory(it.section, categoryRefs)) },
            categories = categories,
            requests = requests,
            answeredToday = answered,
            tablets = tablets,
            allowances = child.appAllowances.filter { it.until > now }.sortedBy { it.until }
                .map { AllowanceLine(app(it.packageName), it.until) },
            schedules = schedules,
            otherBans = bans.count { Schedules.kindOf(it.start, it.end) == null },
            sites = if (logic.database.config().getServerApiLevelSync() >= UpdateUserUrlFilterAction.MIN_SERVER_API_LEVEL)
                (child.urlFilter ?: UserUrlFilter(false, emptyList(), emptyList())).let { Sites(it.enabled, it.allow, it.block) }
            else null,
        )

        if (device != null && cannotAct == null) loadIcons(home, device.currentUserId)

        return ParentHome(cannotAct, refs, home)
    }

    // @tag:category-limits
    private fun parentCategory(
        category: CategoryRelatedData, depth: Int, parentId: String?, chain: CategoryChain,
        todayEpoch: Int, today: LocalDate, nowMinute: Int, minuteStart: Long, now: Long,
    ): ParentCategory {
        val blocked = Schedules.blockedMinutes(category)
        val limits = CategoryLimits(category.rules, category.usedTimes, DateInTimezone.newInstance(today), now)
        val tempBlocked = category.category.temporarilyBlocked &&
                (category.category.temporarilyBlockedEndTime == 0L || category.category.temporarilyBlockedEndTime > now)

        return ParentCategory(
            ref = CategoryRef(category.category.id, category.category.title),
            depth = depth,
            parentId = parentId,
            remaining = chain.remaining,
            usedToday = category.usedTimes.filter { it.dayOfEpoch == todayEpoch && it.startTimeOfDay == 0 && it.endTimeOfDay == 24 * 60 - 1 }
                .sumOf { it.usedMillis },
            limit = limits.dayLimit,
            week = limits.week,
            dailyLimit = dailyLimitOf(category.rules),
            closedByModeUntil = if (blocked[nowMinute]) ModeClock.minutesUntilOpen(blocked, nowMinute)?.let { minuteStart + it * MINUTE } else null,
            closedByParentUntil = category.category.temporarilyBlockedEndTime.takeIf { tempBlocked && it != 0L },
            closedByParent = tempBlocked,
            allowedUntil = category.category.disableLimitsUntil.takeIf { it > now },
            limitedBy = chain.limitedBy,
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

    private fun undoWith(actions: List<ParentAction>): Undo? =
        if (actions.isEmpty()) null else Undo { dispatch(actions) }

    private suspend fun childData(): UserRelatedData? = withContext(Dispatchers.IO) {
        val children = logic.database.user().getAllUsersSync().filter { it.type == UserType.Child }

        (children.find { it.id == selectedChild.value } ?: children.firstOrNull())
            ?.let { logic.database.derivedDataDao().getUserRelatedDataSync(it.id) }
    }

    override suspend fun addTime(categoryId: String, minutes: Int): Undo? {
        val data = childData() ?: return null
        val category = data.categoryById[categoryId] ?: return null
        val now = now()
        val day = Instant.ofEpochMilli(now).atZone(data.timeZone.toZoneId()).toLocalDate().toEpochDay().toInt()
        val duringMode = Schedules.blockedMinutes(category)[getMinuteOfWeek(now, data.timeZone)] || category.category.temporarilyBlocked
        val base = category.category

        return if (duringMode) {
            dispatch(listOf(UpdateCategoryDisableLimitsAction(categoryId, base.disableLimitsUntil.coerceAtLeast(now) + minutes * MINUTE)))
            undoWith(listOf(UpdateCategoryDisableLimitsAction(categoryId, base.disableLimitsUntil)))
        } else {
            dispatch(listOf(IncrementCategoryExtraTimeAction(categoryId, minutes * MINUTE, day)))
            undoWith(listOf(SetCategoryExtraTimeAction(categoryId, base.getExtraTime(day), day)))
        }
    }

    private fun restoreBlocked(category: io.timelimit.android.data.model.Category) = UpdateCategoryTemporarilyBlockedAction(
        category.id, category.temporarilyBlocked, category.temporarilyBlockedEndTime.takeIf { category.temporarilyBlocked && it != 0L }
    )

    override suspend fun closeCategory(categoryId: String, until: Long): Undo? {
        val category = childData()?.categoryById?.get(categoryId)?.category ?: return null

        dispatch(listOf(UpdateCategoryTemporarilyBlockedAction(categoryId, true, until)))
        return undoWith(listOf(restoreBlocked(category)))
    }

    // the same as the web console: every top-level category of the child
    override suspend fun closeAll(until: Long): Undo? {
        val roots = childData()?.categories?.filter { it.category.parentCategoryId.isEmpty() }?.map { it.category } ?: return null

        dispatch(roots.map { UpdateCategoryTemporarilyBlockedAction(it.id, true, until) })
        return undoWith(roots.map { restoreBlocked(it) })
    }

    override suspend fun openCategory(categoryId: String): Undo? {
        val category = childData()?.categoryById?.get(categoryId)?.category ?: return null

        dispatch(listOf(UpdateCategoryTemporarilyBlockedAction(categoryId, false, null)))
        return undoWith(listOf(restoreBlocked(category)))
    }

    override suspend fun returnLimits(categoryId: String): Undo? {
        val category = childData()?.categoryById?.get(categoryId)?.category ?: return null

        dispatch(listOf(UpdateCategoryDisableLimitsAction(categoryId, 0)))
        return undoWith(listOf(UpdateCategoryDisableLimitsAction(categoryId, category.disableLimitsUntil)))
    }

    override suspend fun answer(requestId: String, scope: GrantScope, until: Long): Undo? {
        val data = childData() ?: return null
        val request = data.user.childRequests.find { it.id == requestId } ?: return null

        dispatch(listOf(AnswerChildRequestAction(
            requestId, if (scope == GrantScope.App) ChildRequestAnswer.KIND_APP else ChildRequestAnswer.KIND_CATEGORY, until, ""
        )))

        // the answer itself stays (the first answer is final), what it opened is closed again
        return when (scope) {
            GrantScope.App -> undoWith(listOf(SetAppAllowanceAction(
                data.user.id, request.packageName,
                data.user.appAllowances.filter { it.packageName == request.packageName }.maxOfOrNull { it.until } ?: 0
            )))
            GrantScope.Category -> data.categoryById[request.categoryId.ifEmpty { data.user.categoryForNotAssignedApps }]?.category
                ?.let { undoWith(listOf(UpdateCategoryDisableLimitsAction(it.id, it.disableLimitsUntil))) }
        }
    }

    override suspend fun deny(requestId: String): Undo? {
        dispatch(listOf(AnswerChildRequestAction(requestId, ChildRequestAnswer.KIND_DENY, 0, "")))
        return null
    }

    override suspend fun renameCategory(categoryId: String, title: String): Undo? {
        val old = childData()?.categoryById?.get(categoryId)?.category?.title ?: return null
        val wanted = title.trim().takeIf { it.isNotEmpty() && it != old } ?: return null

        dispatch(listOf(UpdateCategoryTitleAction(categoryId, wanted)))
        return undoWith(listOf(UpdateCategoryTitleAction(categoryId, old)))
    }

    /** One rule for all days takes the place of the day limits there were; «Отменить» puts those back as they were. */
    // @tag:category-limits
    override suspend fun setCategoryLimit(categoryId: String, minutes: Int?): Undo? {
        require(minutes == null || minutes > 0) { "the day limit is positive minutes or none, got $minutes" }
        val previous = dailyLimitRules(childData()?.categoryById?.get(categoryId)?.rules ?: return null)
        val wanted = minutes?.let {
            TimeLimitRule(
                id = IdGenerator.generateId(), categoryId = categoryId, applyToExtraTimeUsage = false,
                dayMask = Schedules.ALL_DAYS.toByte(), maximumTimeInMillis = it * MINUTE.toInt(),
                startMinuteOfDay = TimeLimitRule.MIN_START_MINUTE, endMinuteOfDay = TimeLimitRule.MAX_END_MINUTE,
                sessionDurationMilliseconds = 0, sessionPauseMilliseconds = 0, perDay = true, expiresAt = null,
            )
        }

        dispatch(previous.map { DeleteTimeLimitRuleAction(it.id) } + listOfNotNull(wanted?.let { CreateTimeLimitRuleAction(it) }))
        return undoWith(listOfNotNull(wanted?.let { DeleteTimeLimitRuleAction(it.id) }) + previous.map { CreateTimeLimitRuleAction(it) })
    }

    override suspend fun moveApp(packageName: String, categoryId: String): Undo? {
        val data = childData() ?: return null
        val previous = data.categoryApps.filter { it.appSpecifier.packageName == packageName }.map { it.categoryId }.distinct()

        dispatch(listOf(AddCategoryAppsAction(categoryId, listOf(packageName))))
        return undoWith(
            if (previous.isEmpty()) listOf(RemoveCategoryAppsAction(categoryId, listOf(packageName)))
            else previous.map { AddCategoryAppsAction(it, listOf(packageName)) }
        )
    }

    // the action sets the whole rule, so the hidden mark from the server is passed on
    // @tag:app-hidden
    override suspend fun setAppRule(packageName: String, days: Int, limitMinutes: Int): Undo? {
        val data = childData() ?: return null
        val old = data.user.appRules.find { it.packageName == packageName }
        val hidden = old?.hidden ?: false

        dispatch(listOf(SetAppRuleAction(data.user.id, packageName, days, limitMinutes, hidden)))
        return undoWith(listOf(SetAppRuleAction(data.user.id, packageName, old?.days ?: 127, old?.limitMinutes ?: -1, hidden)))
    }

    // the allowance stacks on the one in force, as in the web console
    // @tag:app-allowance
    override suspend fun allowApp(packageName: String, minutes: Int): Undo? {
        val data = childData() ?: return null
        val previous = data.user.appAllowances.filter { it.packageName == packageName }.maxOfOrNull { it.until } ?: 0

        dispatch(listOf(SetAppAllowanceAction(data.user.id, packageName, previous.coerceAtLeast(now()) + minutes * MINUTE)))
        return undoWith(listOf(SetAppAllowanceAction(data.user.id, packageName, previous)))
    }

    // @tag:app-allowance
    override suspend fun removeAppAllowance(packageName: String): Undo? {
        val data = childData() ?: return null
        val previous = data.user.appAllowances.filter { it.packageName == packageName }.maxOfOrNull { it.until } ?: 0

        dispatch(listOf(SetAppAllowanceAction(data.user.id, packageName, 0)))
        return undoWith(listOf(SetAppAllowanceAction(data.user.id, packageName, previous)))
    }

    // @tag:ban-schedule
    override suspend fun setSchedule(kind: io.timelimit.api.ModeKind, schedule: ScheduleSpec?): Undo? {
        val data = childData() ?: return null
        val own = if (kind == io.timelimit.api.ModeKind.Sleep) Schedules.Kind.Sleep else Schedules.Kind.Study
        val previous = Schedules.readBans(data.categories).filter { Schedules.kindOf(it.start, it.end) == own }
        val wanted = listOfNotNull(schedule?.let {
            val hard = previous.firstOrNull()?.hard ?: true
            Schedules.Ban(it.days, it.start, it.end, hard, it.categoryIds, emptyList())
        })

        if (wanted.any { Schedules.kindOf(it.start, it.end) != own || it.categoryIds.isEmpty() }) throw IllegalArgumentException("not a ${own.name} time")

        dispatch(Schedules.setActions(own, data.categories, wanted))

        return Undo {
            childData()?.let { now -> dispatch(Schedules.setActions(own, now.categories, previous)) }
        }
    }

    // @tag:url-filter
    override suspend fun setSites(sites: Sites): Undo? {
        val data = childData() ?: return null
        val old = data.user.urlFilter ?: UserUrlFilter(false, emptyList(), emptyList())

        dispatch(listOf(UpdateUserUrlFilterAction(data.user.id, UserUrlFilter(sites.enabled, sites.allow, sites.block))))
        return undoWith(listOf(UpdateUserUrlFilterAction(data.user.id, old)))
    }
}
