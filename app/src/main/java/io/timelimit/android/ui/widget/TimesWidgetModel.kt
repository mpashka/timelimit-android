package io.timelimit.android.ui.widget

import io.timelimit.android.child.ModeClock
import io.timelimit.android.child.Schedules
import io.timelimit.android.child.toApi
import io.timelimit.android.data.model.derived.CategoryRelatedData
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Bar
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Item
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Mode
import io.timelimit.api.WeekLimit

/** What the widget draws (variant G of the owner's choice): limit bars of each limited category and the mode ring. */
// @tag:new-ui
object TimesWidgetModel {
    private const val MINUTE = 60_000L
    const val SOON_MINUTES = 60

    private fun bar(used: Long, limit: Long, over: Boolean) =
        Bar(if (over) 100 else (used * 100 / limit).coerceIn(0, 100).toInt(), over)

    /**
     * [remaining] — time left today as the child's «Что можно» shows it (parent chain and extra time included),
     * [dayLimit] — the category's own day limit; null result — no limit, no row.
     */
    fun item(categoryId: String, title: String, remaining: Long?, dayLimit: Long?, week: WeekLimit?): Item? {
        val day = dayLimit?.let { bar(it - (remaining ?: it), it, remaining != null && remaining <= 0) }
        val weekBar = week?.let { bar(it.used, it.limit, it.used >= it.limit) }

        return if (day == null && weekBar == null) null else Item(categoryId, title, day, weekBar)
    }

    // ponytail: only named modes (sleep, study); a closed time of another shape is not shown at all
    fun mode(categories: List<CategoryRelatedData>, nowMinute: Int, minuteStart: Long): Mode? {
        val blocked = categories.map { it to Schedules.blockedMinutes(it) }

        val current = blocked.filter { (_, minutes) -> minutes[nowMinute] }.mapNotNull { (category, minutes) ->
            val kind = Schedules.kindAt(category, nowMinute) ?: return@mapNotNull null
            val left = ModeClock.minutesUntilOpen(minutes, nowMinute) ?: return@mapNotNull null
            val passed = ModeClock.runBefore(minutes, nowMinute, true)

            Mode(kind.toApi(), minuteStart - passed * MINUTE, minuteStart + left * MINUTE, null, left * 100 / (passed + left))
        }.maxByOrNull { it.until }

        return current ?: blocked.mapNotNull { (category, minutes) ->
            val (start, length) = ModeClock.nextWindow(minutes, nowMinute, ModeClock.DAY) ?: return@mapNotNull null
            val kind = Schedules.kindAt(category, (nowMinute + start) % ModeClock.WEEK) ?: return@mapNotNull null
            val open = minOf(ModeClock.runBefore(minutes, nowMinute, false), nowMinute % ModeClock.DAY)

            Mode(kind.toApi(), minuteStart + start * MINUTE, minuteStart + (start + length) * MINUTE, start, start * 100 / (open + start))
        }.minByOrNull { it.from }
    }
}
