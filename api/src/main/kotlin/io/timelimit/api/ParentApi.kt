package io.timelimit.api

import kotlinx.coroutines.flow.Flow

/**
 * What the parent's screens need (docs/specification/parent-ui.md): state as flows, actions as commands.
 * Times are epoch milliseconds, durations are milliseconds.
 */
// @tag:new-ui
interface ParentApi {
    val home: Flow<ParentHome>

    fun selectChild(childId: String)

    /** The code of docs/specification/protocol-new-ui.md, section 7; null — the server gave no secret yet. */
    // @tag:parent-code
    val parentCode: Flow<ParentCodeNow?>

    /** "+N": adds to the limit, or during a mode opens the category over it. */
    suspend fun addTime(categoryId: String, minutes: Int): Undo?

    suspend fun closeCategory(categoryId: String, until: Long): Undo?

    suspend fun closeAll(until: Long): Undo?

    // @tag:child-request
    suspend fun answer(requestId: String, scope: GrantScope, until: Long): Undo?

    // @tag:child-request
    suspend fun deny(requestId: String): Undo?

    /** The name is the parent's own, stored as typed (any Unicode). */
    suspend fun renameCategory(categoryId: String, title: String): Undo?

    // @tag:new-app
    suspend fun moveApp(packageName: String, categoryId: String): Undo?

    // @tag:app-rule
    suspend fun setAppRule(packageName: String, days: Int, limitMinutes: Int): Undo?

    /** null — the mode is off. */
    // @tag:ban-schedule
    suspend fun setSchedule(kind: ModeKind, schedule: ScheduleSpec?): Undo?

    // @tag:url-filter
    suspend fun setSites(sites: Sites): Undo?
}

/** Puts back what a command changed; null from a command — it can not be undone (a denial is final). */
fun interface Undo {
    suspend fun undo()
}

enum class CannotAct { NotConnected, NotParent, NotKeptSignedIn }

data class ChildRef(val id: String, val name: String)

/** [end] is inclusive, `start > end` runs past midnight, days bit 0 is Monday. */
data class ScheduleSpec(val start: Int, val end: Int, val days: Int, val categoryIds: List<String>)

data class ScheduleLine(val kind: ModeKind, val on: Boolean, val spec: ScheduleSpec, val window: ModeWindow?, val exceptions: Int)

/** Chrome's filter of the child; "*" in [block] means only [allow] opens. */
data class Sites(val enabled: Boolean, val allow: List<String>, val block: List<String>)

data class ParentCodeNow(val code: String, val changesAt: Long)

data class ParentHome(
    /** null — actions work */
    val cannotAct: CannotAct?,
    val children: List<ChildRef>,
    val child: ChildHome?,
)

data class ChildHome(
    val id: String,
    val name: String,
    val now: Long,
    val modeNow: ModeWindow?,
    val nextMode: ModeWindow?,
    /** "до утра": the end of the current or next mode, else tomorrow 07:00 */
    val morning: Long,
    /** "до конца дня": the start of the next mode today, else midnight */
    val dayEnd: Long,
    val usage: AppUsage,
    val newApps: List<NewAppLine>,
    val categories: List<ParentCategory>,
    val requests: List<ParentRequest>,
    val answeredToday: List<AnsweredLine>,
    val tablets: List<TabletLine>,
    val schedules: List<ScheduleLine>,
    val otherBans: Int,
    /** null — the server does not keep a site filter */
    val sites: Sites?,
)

sealed interface AppUsage {
    data class Known(val today: List<AppTime>, val week: List<AppTime>, val weekByDay: Map<String, List<Long>>) : AppUsage
    data object Loading : AppUsage
    /** The phone may not ask: no kept parent sign-in. */
    data object NeedsSignIn : AppUsage
    /** [httpCode] null — no answer at all; [detail] — the error as it came, never empty. */
    data class Failed(val httpCode: Int?, val detail: String) : AppUsage
}

data class AppTime(val app: App, val ms: Long, val categoryTitle: String?, val rule: AppRuleLine?)

data class AppRuleLine(val days: Int, val limitMinutes: Int)

data class NewAppLine(val app: App, val installedAt: Long, val tabletName: String?, val guessedCategory: CategoryRef?)

data class CategoryRef(val id: String, val title: String)

data class ParentCategory(
    val ref: CategoryRef,
    val depth: Int,
    /** null — no limit */
    val remaining: Long?,
    val usedToday: Long,
    val limit: Long?,
    val closedByModeUntil: Long?,
    val closedByParentUntil: Long?,
    val closedByParent: Boolean,
    val allowedUntil: Long?,
)

data class ParentRequest(
    val id: String,
    val app: App,
    val at: Long,
    val tabletName: String?,
    val word: String,
    val appToday: Long?,
    /** the category "вся категория" would open; null — the app has none and there is no category for such apps */
    val category: ParentCategory?,
    val rule: AppRuleLine?,
)

data class AnsweredLine(val app: App, val at: Long, val allowed: Boolean, val until: Long)

data class TabletLine(val name: String, val online: Boolean, val seen: Long, val appNow: App?)
