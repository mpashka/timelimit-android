package io.timelimit.android.logic

import io.timelimit.android.data.model.AppRule
import java.time.Instant
import java.util.TimeZone

/** The own rule of one app, docs/specification/protocol-new-ui.md, section 3: it closes the app on top of its category. */
// @tag:app-rule
object AppRuleCheck {
    sealed interface Verdict {
        data object Open : Verdict
        data object ClosedAlways : Verdict
        data class NotToday(val days: Int, val daysUntilAllowed: Int) : Verdict
        data object LimitOver : Verdict
    }

    /** [dayOfWeek] 0 is Monday; [ownUnsent] is this tablet's time of the app today that the server does not know yet. */
    fun check(rule: AppRule?, dayOfWeek: Int, epochDay: Int, ownUnsent: Long): Verdict {
        if (rule == null) return Verdict.Open
        if (rule.days and 127 == 0) return Verdict.ClosedAlways
        if (rule.days and (1 shl dayOfWeek) == 0) {
            return Verdict.NotToday(rule.days, (1..7).first { rule.days and (1 shl ((dayOfWeek + it) % 7)) != 0 })
        }
        if (rule.limitMinutes >= 0) {
            val used = (if (rule.usedDay == epochDay) rule.usedMs else 0) + ownUnsent
            if (used >= rule.limitMinutes * 60_000L) return Verdict.LimitOver
        }

        return Verdict.Open
    }

    fun check(rules: List<AppRule>, packageName: String, now: Long, timeZone: TimeZone, ownUnsent: Long): Verdict {
        val rule = rules.find { it.packageName == packageName } ?: return Verdict.Open
        val day = Instant.ofEpochMilli(now).atZone(timeZone.toZoneId()).toLocalDate()

        return check(rule, day.dayOfWeek.value - 1, day.toEpochDay().toInt(), ownUnsent)
    }
}
