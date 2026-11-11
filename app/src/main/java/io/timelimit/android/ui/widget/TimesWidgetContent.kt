/*
 * TimeLimit Copyright <C> 2019 - 2022 Jonas Lochmann
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
package io.timelimit.android.ui.widget

import io.timelimit.api.ModeKind

sealed class TimesWidgetContent {
        object UnconfiguredDevice: TimesWidgetContent()
        data class NoChildUser(val canSwitchToDefaultUser: Boolean): TimesWidgetContent()
        // @tag:new-ui
        data class Categories(
                /** only categories with a day or a week limit */
                val categories: List<Item>,
                val mode: Mode?,
                val canSwitchToDefaultUser: Boolean
        ): TimesWidgetContent() {
                /** [day], [week] — null when the category has no such limit */
                data class Item(
                        val categoryId: String,
                        val categoryName: String,
                        val day: Bar?,
                        val week: Bar?
                )

                /** [usedPercent] grows as time is spent; [over] — nothing left, drawn red and full */
                data class Bar(val usedPercent: Int, val over: Boolean)

                /**
                 * The named mode going on now ([startsIn] null) or the next one within a day.
                 * [leftPercent] — of the mode itself while it goes on, of the open gap before it otherwise.
                 */
                data class Mode(
                        val kind: ModeKind,
                        val from: Long,
                        val until: Long,
                        val startsIn: Int?,
                        val leftPercent: Int
                )
        }
}
