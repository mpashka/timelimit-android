package io.timelimit.android.child

import java.util.BitSet

/** Walks the blocked minutes of a week (Monday 00:00 is minute 0), as a category stores its modes. */
// @tag:new-ui
object ModeClock {
    const val DAY = 24 * 60
    const val WEEK = 7 * DAY

    /** Minutes from [fromMinute] to the first minute that is not blocked; null if the whole week is blocked. */
    fun minutesUntilOpen(blocked: BitSet, fromMinute: Int): Int? =
        (0 until WEEK).firstOrNull { !blocked[(fromMinute + it) % WEEK] }

    /** Minutes from [nowMinute] to the start of the next blocked window and its length, looking [horizon] minutes ahead. */
    fun nextWindow(blocked: BitSet, nowMinute: Int, horizon: Int): Pair<Int, Int>? {
        if (blocked[nowMinute % WEEK]) return null

        val start = (1..horizon).firstOrNull { blocked[(nowMinute + it) % WEEK] } ?: return null
        val length = minutesUntilOpen(blocked, (nowMinute + start) % WEEK) ?: return null

        return start to length
    }

    fun nextDayStart(nowMinute: Int): Int = (nowMinute / DAY + 1) * DAY
}
