/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.logic

// @tag:category-limits
object ForegroundTimeAggregation {
    sealed class Event {
        abstract val timestamp: Long

        data class Resumed(override val timestamp: Long, val packageName: String, val className: String): Event()
        data class Paused(override val timestamp: Long, val packageName: String, val className: String): Event()
        // device shutdown or startup: nothing can stay in the foreground across it
        data class EndAll(override val timestamp: Long): Event()
    }

    /**
     * Sums the foreground time per package within [start, end).
     * A package counts as foreground while at least one of its activities is resumed, so parallel
     * activities (multi window) are not counted twice. A pause without a preceding resume means the
     * activity was resumed before [start]; a resume without a pause is counted until [end].
     */
    fun aggregate(events: List<Event>, start: Long, end: Long): Map<String, Long> {
        val sorted = events.sortedBy { it.timestamp }
        val result = mutableMapOf<String, Long>()
        val resumedClasses = mutableMapOf<String, MutableSet<String>>()
        val foregroundSince = mutableMapOf<String, Long>()

        fun clip(time: Long) = time.coerceIn(start, end)

        fun finish(packageName: String, time: Long) {
            val since = foregroundSince.remove(packageName) ?: return
            val duration = clip(time) - clip(since)

            if (duration > 0) result[packageName] = (result[packageName] ?: 0) + duration
        }

        fun resume(packageName: String, className: String, time: Long) {
            val classes = resumedClasses.getOrPut(packageName) { mutableSetOf() }

            if (classes.isEmpty()) foregroundSince[packageName] = time

            classes.add(className)
        }

        run {
            val seen = mutableSetOf<Pair<String, String>>()

            for (event in sorted) {
                when (event) {
                    is Event.EndAll -> break
                    is Event.Resumed -> seen.add(event.packageName to event.className)
                    is Event.Paused -> if (seen.add(event.packageName to event.className)) resume(event.packageName, event.className, start)
                }
            }
        }

        for (event in sorted) {
            when (event) {
                is Event.Resumed -> resume(event.packageName, event.className, event.timestamp)
                is Event.Paused -> {
                    val classes = resumedClasses[event.packageName]

                    if (classes != null && classes.remove(event.className) && classes.isEmpty()) finish(event.packageName, event.timestamp)
                }
                is Event.EndAll -> {
                    foregroundSince.keys.toList().forEach { finish(it, event.timestamp) }
                    resumedClasses.clear()
                }
            }
        }

        foregroundSince.keys.toList().forEach { finish(it, end) }

        return result
    }
}
