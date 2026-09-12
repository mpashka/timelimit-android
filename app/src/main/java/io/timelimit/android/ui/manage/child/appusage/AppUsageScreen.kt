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
package io.timelimit.android.ui.manage.child.appusage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.timelimit.android.R
import io.timelimit.android.ui.model.managechild.ManageChildAppUsage
import io.timelimit.android.util.TimeTextUtil
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// @tag:category-limits
@Composable
fun AppUsageScreen(content: ManageChildAppUsage.Screen, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("day") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { content.selectDaysAgo(content.daysAgo + 1) },
                    enabled = content.daysAgo < ManageChildAppUsage.MAX_DAYS_AGO
                ) {
                    Icon(Icons.Default.ChevronLeft, stringResource(R.string.app_usage_previous_day))
                }

                Text(
                    content.day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.h6,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { content.selectDaysAgo(content.daysAgo - 1) },
                    enabled = content.daysAgo > 0
                ) {
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.app_usage_next_day))
                }
            }
        }

        item("hint") {
            Text(stringResource(if (content.isDeviceUser) R.string.app_usage_hint else R.string.app_usage_hint_other_user))
        }

        if (content.items.isEmpty()) item("empty") {
            Text(stringResource(R.string.app_usage_empty), style = MaterialTheme.typography.h6)
        }

        items(content.items, key = { it.packageName }) { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row {
                    Text(item.title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
                    Text(TimeTextUtil.time(item.duration.toInt(), context))
                }

                Text(
                    listOfNotNull(
                        item.categoryTitle,
                        stringResource(when (item.counting) {
                            ManageChildAppUsage.Counting.Counted -> R.string.app_usage_counted
                            ManageChildAppUsage.Counting.NoActiveRules -> R.string.app_usage_not_counted_no_rules
                            ManageChildAppUsage.Counting.TemporarilyAllowed -> R.string.app_usage_not_counted_temporarily_allowed
                            ManageChildAppUsage.Counting.Whitelisted -> R.string.app_usage_not_counted_whitelisted
                            ManageChildAppUsage.Counting.NoCategory -> R.string.app_usage_no_category
                        })
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.body2,
                    color = if (item.counting == ManageChildAppUsage.Counting.Counted) MaterialTheme.colors.onSurface else MaterialTheme.colors.error
                )
            }
        }
    }
}
