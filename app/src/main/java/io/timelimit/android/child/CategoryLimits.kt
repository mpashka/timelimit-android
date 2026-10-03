package io.timelimit.android.child

import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.UsedTimeItem
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.logic.RemainingTime
import io.timelimit.api.WeekLimit

/** The day and week limits of a category as the screens show them, over its own rules active today. */
// @tag:category-limits
class CategoryLimits(rules: List<TimeLimitRule>, private val usedTimes: List<UsedTimeItem>, private val date: DateInTimezone, now: Long) {
    private val today = rules.filter {
        (it.expiresAt == null || it.expiresAt > now) && it.dayMask.toInt() and (1 shl date.dayOfWeek) != 0 &&
                it.appliesToWholeDay && it.maximumTimeInMillis > 0
    }
    private val weekly = today.filter { !it.perDay && it.appliesToMultipleDays && !it.sessionDurationLimitEnabled }

    val dayLimit: Long? = (today - weekly.toSet()).minOfOrNull { it.maximumTimeInMillis.toLong() }

    /** The weekly rule with the least time left; null — no weekly rule today. */
    val week: WeekLimit? = weekly
        .map { WeekLimit(RemainingTime.getUsedTime(usedTimes, it, date.firstDayOfWeekAsEpochDay, null), it.maximumTimeInMillis.toLong()) }
        .minByOrNull { it.limit - it.used }

    /** Days from today to the first day no exhausted weekly rule holds: a day outside its days or the next Monday. */
    val daysUntilWeekOpens: Int? = weekly
        .filter { RemainingTime.getUsedTime(usedTimes, it, date.firstDayOfWeekAsEpochDay, null) >= it.maximumTimeInMillis }
        .takeIf { it.isNotEmpty() }
        ?.let { over ->
            (1..7).first { day ->
                val dayOfWeek = date.dayOfWeek + day
                dayOfWeek >= 7 || over.none { it.dayMask.toInt() and (1 shl dayOfWeek) != 0 }
            }
        }
}
