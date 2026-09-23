package io.timelimit.api

import kotlinx.coroutines.flow.Flow

/**
 * What the child's screens need from the rest of the app: state as flows, actions as commands.
 * Times are epoch milliseconds, durations are milliseconds.
 */
// @tag:new-ui
interface ChildApi {
    fun appAccess(packageName: String): Flow<AppAccess>

    val today: Flow<Today>

    // @tag:child-request
    suspend fun ask(packageName: String, word: String)

    /** null — the code does not match (it changes every 30 s). */
    // @tag:parent-code
    suspend fun checkParentCode(code: String): ParentCode?

    // @tag:parent-code
    suspend fun grant(packageName: String, code: ParentCode, scope: GrantScope, until: Long)
}

data class ParentCode(val code: String, val step: Long)

enum class GrantScope { App, Category }

/**
 * What a parent next to the child can open with the parent code.
 * [categoryTitle] null — the app has no category, only the app itself can be opened;
 * [dayEnd] — "до конца дня": the start of the next mode today, else midnight.
 */
data class GrantChoice(val categoryTitle: String?, val dayEnd: Long)

data class App(val packageName: String, val title: String)

sealed interface AppAccess {
    val app: App

    /** [allowedUntil] and [allowedBy] (the parent's account name) are set when a parent opened it for a while. */
    data class Open(override val app: App, val allowedUntil: Long?, val allowedBy: String?) : AppAccess

    data class Closed(
        override val app: App,
        val categoryTitle: String?,
        val reason: CloseReason,
        /** null — not known when, or only a person can open it */
        val opensAt: Long?,
        /** what stays for today once it opens; null — no limit */
        val remainingToday: Long?,
        val request: Request,
        /** null — no parent code on this device (no server secret yet) */
        val grant: GrantChoice?,
    ) : AppAccess
}

sealed interface CloseReason {
    data object LimitOver : CloseReason
    data class ExtraTimeLater(val extraTime: Long) : CloseReason
    data class Mode(val name: String?) : CloseReason
    data object ClosedByParent : CloseReason
    data class Break(val playLength: Long, val breakLength: Long) : CloseReason
    data object NewApp : CloseReason
    data object LowBattery : CloseReason
    data object WifiRequired : CloseReason
    data object ForbiddenNetwork : CloseReason
    data object NoExactTime : CloseReason
    data object NoNetworkPermission : CloseReason
    data object OtherDevice : CloseReason

    /** The app's own rule: only on [days] (bit 0 — Monday). */
    // @tag:app-rule
    data class AppOnlyOnDays(val days: Int) : CloseReason
    data object AppClosedByParent : CloseReason
    data object AppLimitOver : CloseReason
}

/** The request states of docs/specification/protocol-new-ui.md, section 1, as the child sees them. */
// @tag:child-request
sealed interface Request {
    data object None : Request
    data class Sending(val word: String) : Request
    data class Sent(val at: Long, val word: String) : Request
    data class Refused(val at: Long, val parentName: String, val parentWord: String?, val askAgainAt: Long) : Request
    data class Expired(val word: String) : Request
}

data class Today(
    val now: Long,
    val categories: List<CategoryToday>,
    val waiting: List<WaitingRequest>,
    val nextMode: ModeWindow?,
)

data class CategoryToday(
    val title: String,
    /** null — no limit */
    val remaining: Long?,
    val closedNow: Boolean,
    val apps: List<App>,
)

data class WaitingRequest(val app: App, val sentAt: Long)

data class ModeWindow(val name: String?, val from: Long, val until: Long)
