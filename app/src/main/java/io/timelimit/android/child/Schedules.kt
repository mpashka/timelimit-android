package io.timelimit.android.child

import io.timelimit.android.data.IdGenerator
import io.timelimit.android.data.model.TimeLimitRule
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.sync.actions.CreateTimeLimitRuleAction
import io.timelimit.android.sync.actions.DeleteTimeLimitRuleAction
import io.timelimit.android.sync.actions.ParentAction
import java.util.BitSet

/**
 * Modes («Сон», «Учёба») the way the web console keeps them (timelimit-parent/src/core/bans.ts,
 * src/shared/schedules.ts): not stored by name, but rules with no time — «bans» — recognised by shape.
 * [start] and [end] are minutes of day, [end] inclusive; `start > end` runs past midnight; days bit 0 is Monday.
 */
// @tag:ban-schedule
object Schedules {
    const val DAY_END = 24 * 60 - 1
    const val ALL_DAYS = 127
    const val WEEKDAYS = 31

    enum class Kind { Sleep, Study }

    data class Ban(val days: Int, val start: Int, val end: Int, val hard: Boolean, val categoryIds: List<String>, val rules: List<TimeLimitRule>)

    val defaults = mapOf(
        Kind.Sleep to Ban(ALL_DAYS, 21 * 60, 7 * 60 - 1, true, emptyList(), emptyList()),
        Kind.Study to Ban(WEEKDAYS, 8 * 60, 14 * 60 - 1, true, emptyList(), emptyList()),
    )

    private val allowedTitle = Regex("^(allowed( apps)?|erlaubte apps|разрешено|разрешённые( приложения)?|разрешенные( приложения)?|всегда можно)$", RegexOption.IGNORE_CASE)
    private val educationTitle = Regex("^(education|учёба|учеба|обучение|школа)$", RegexOption.IGNORE_CASE)

    fun kindOf(start: Int, end: Int): Kind? = when (io.timelimit.api.ModeKind.of(start, end)) {
        io.timelimit.api.ModeKind.Sleep -> Kind.Sleep
        io.timelimit.api.ModeKind.Study -> Kind.Study
        null -> null
    }

    fun rotateDays(days: Int): Int = ((days shl 1) or (days shr 6)) and ALL_DAYS

    private fun isBan(rule: TimeLimitRule) = rule.maximumTimeInMillis == 0 && rule.expiresAt == null

    private fun categoryBans(category: CategoryRelatedData): List<Ban> {
        val segments = category.rules.filter(::isBan)
        val used = mutableSetOf<String>()
        val result = mutableListOf<Ban>()
        val id = category.category.id

        for (evening in segments) {
            if (evening.endMinuteOfDay != DAY_END || evening.startMinuteOfDay == 0 || evening.id in used) continue
            val morning = segments.find {
                it.id !in used && it.startMinuteOfDay == 0 && it.endMinuteOfDay != DAY_END &&
                        it.dayMask.toInt() == rotateDays(evening.dayMask.toInt()) && it.applyToExtraTimeUsage == evening.applyToExtraTimeUsage
            } ?: continue

            used += evening.id; used += morning.id
            result += Ban(evening.dayMask.toInt(), evening.startMinuteOfDay, morning.endMinuteOfDay, evening.applyToExtraTimeUsage, listOf(id), listOf(evening, morning))
        }
        segments.filter { it.id !in used }.forEach {
            result += Ban(it.dayMask.toInt(), it.startMinuteOfDay, it.endMinuteOfDay, it.applyToExtraTimeUsage, listOf(id), listOf(it))
        }

        return result
    }

    fun readBans(categories: List<CategoryRelatedData>): List<Ban> =
        categories.flatMap(::categoryBans)
            .groupBy { listOf(it.days, it.start, it.end, it.hard) }
            .map { (_, bans) -> bans.first().copy(categoryIds = bans.flatMap { it.categoryIds }.distinct(), rules = bans.flatMap { it.rules }) }
            .sortedWith(compareBy({ it.start }, { it.days }, { it.end }))

    /** Roots but the launcher's category — a ban on it blanks the home screen — and, for study, but the school one. */
    fun defaultCategories(kind: Kind, categories: List<CategoryRelatedData>): List<String> = categories
        .filter { it.category.parentCategoryId.isEmpty() }
        .filterNot { allowedTitle.matches(it.category.title.trim()) }
        .filterNot { kind == Kind.Study && educationTitle.matches(it.category.title.trim()) }
        .map { it.category.id }

    /** Replaces every ban of [kind] with [wanted] (empty — the mode is off). */
    fun setActions(kind: Kind, categories: List<CategoryRelatedData>, wanted: List<Ban>): List<ParentAction> {
        val removed = readBans(categories).filter { kindOf(it.start, it.end) == kind }.flatMap { it.rules }

        return removed.map { DeleteTimeLimitRuleAction(it.id) } + wanted.flatMap { ban ->
            ban.categoryIds.flatMap { categoryId ->
                segments(ban).map { (days, start, end) ->
                    CreateTimeLimitRuleAction(TimeLimitRule(
                        id = IdGenerator.generateId(), categoryId = categoryId, applyToExtraTimeUsage = ban.hard,
                        dayMask = days.toByte(), maximumTimeInMillis = 0, startMinuteOfDay = start, endMinuteOfDay = end,
                        sessionDurationMilliseconds = 0, sessionPauseMilliseconds = 0, perDay = false, expiresAt = null
                    ))
                }
            }
        }
    }

    private fun segments(ban: Ban): List<Triple<Int, Int, Int>> =
        if (ban.start <= ban.end) listOf(Triple(ban.days, ban.start, ban.end))
        else listOf(Triple(ban.days, ban.start, DAY_END), Triple(rotateDays(ban.days), 0, ban.end))

    /** The blocked minutes of the week of a category: its blocked time areas and its bans (Monday 00:00 is minute 0). */
    fun blockedMinutes(category: CategoryRelatedData): BitSet {
        val result = category.category.blockedMinutesInWeek.dataNotToModify.clone() as BitSet

        category.rules.filter(::isBan).forEach { rule ->
            for (day in 0 until 7) {
                if (rule.dayMask.toInt() and (1 shl day) == 0) continue
                result.set(day * ModeClock.DAY + rule.startMinuteOfDay, day * ModeClock.DAY + rule.endMinuteOfDay + 1)
            }
        }

        return result
    }

    /**
     * The name of the mode that closes the category at [minuteOfWeek], from the ban covering that minute.
     * Not from the blocked window: two bans that touch at midnight (21:30–24:00 and 00:00–16:01) make one
     * window that looks like sleep, while the one closing at 15:29 is a daytime ban.
     */
    fun kindAt(category: CategoryRelatedData, minuteOfWeek: Int): Kind? {
        val day = (minuteOfWeek % ModeClock.WEEK) / ModeClock.DAY
        val minute = minuteOfWeek % ModeClock.DAY
        val covering = readBans(listOf(category)).filter { ban ->
            segments(ban).any { (days, start, end) -> days and (1 shl day) != 0 && minute in start..end }
        }

        return covering.firstNotNullOfOrNull { kindOf(it.start, it.end) }
    }
}
