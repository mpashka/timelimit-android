package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.AppUsage
import io.timelimit.api.ChildHome
import io.timelimit.api.ParentApi
import io.timelimit.ui.R
import io.timelimit.ui.child.AppIcon
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.formatDuration
import java.time.Instant
import java.time.ZoneId

private const val MINUTE = 60_000L

/** P6, P8: all apps of the week by category, new ones first; a row opens the app card. */
// @tag:new-ui
@Composable
fun AppsScreen(child: ChildHome, api: ParentApi, actions: ParentActions, onApp: (String) -> Unit) {
    val colors = LocalChildColors.current

    SectionTitle(stringResource(R.string.parent_apps_of, child.name))

    when (val usage = child.usage) {
        is AppUsage.Known -> usage.week.groupBy { it.categoryTitle }.forEach { (category, lines) ->
            Text(category ?: stringResource(R.string.parent_without_category), color = colors.secondary, fontSize = 14.sp)
            lines.forEach { line ->
                AppRow(line.copy(categoryTitle = line.rule?.let { ruleText(it) })) { onApp(line.app.packageName) }
            }
        }
        is AppUsage.Unknown -> Text(usage.why, color = colors.secondary)
    }
}

/** P5, P8: the week of one app, its category, its own limit and «Закрыть всегда». */
// @tag:new-ui @tag:app-rule
@Composable
fun AppCard(child: ChildHome, packageName: String, api: ParentApi, actions: ParentActions, onBack: () -> Unit) {
    val colors = LocalChildColors.current
    val usage = child.usage as? AppUsage.Known
    val line = usage?.week?.find { it.app.packageName == packageName }
    val title = line?.app?.title ?: packageName
    val today = usage?.today?.find { it.app.packageName == packageName }?.ms ?: 0
    val days = usage?.weekByDay?.get(packageName) ?: List(7) { 0L }
    val rule = line?.rule
    val category = child.categories.find { it.ref.title == line?.categoryTitle }
    val set = stringResource(R.string.parent_rule_set)
    val movedTo = stringResource(R.string.parent_moved_to)

    TextButton(onClick = onBack) { Text(stringResource(R.string.parent_back)) }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName, title, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.parent_app_today_average, formatDuration(today), formatDuration(days.sum() / 7)), color = colors.secondary, fontSize = 14.sp)
        WeekBars(days, child.now)
    }

    Card {
        Text(stringResource(R.string.parent_category_label, category?.ref?.title ?: stringResource(R.string.parent_without_category)), color = colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            child.categories.filter { it.depth == 0 && it.ref.id != category?.ref?.id }.forEach { target ->
                Chip(target.ref.title) { actions(movedTo.format(title, target.ref.title)) { api.moveApp(packageName, target.ref.id) } }
            }
        }
    }

    Card {
        Text(stringResource(R.string.parent_own_limit), color = colors.text)
        Text(stringResource(R.string.parent_own_limit_inside, category?.ref?.title ?: ""), color = colors.secondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val keepDays = rule?.days?.takeIf { it and 127 != 0 } ?: 127
            listOf(-1, 30, 45, 60).forEach { minutes ->
                val label = if (minutes < 0) stringResource(R.string.parent_no) else formatDuration(minutes * MINUTE)
                Chip(if ((rule?.limitMinutes ?: -1) == minutes) "✓ $label" else label) {
                    actions(set.format(title, label)) { api.setAppRule(packageName, keepDays, minutes) }
                }
            }
        }
        val closedAlways = rule != null && rule.days and 127 == 0
        Chip(stringResource(if (closedAlways) R.string.parent_open_again else R.string.parent_close_always)) {
            actions(set.format(title, if (closedAlways) "—" else "×")) { api.setAppRule(packageName, if (closedAlways) 127 else 0, rule?.limitMinutes ?: -1) }
        }
    }

    category?.let { CategoryCard(it, child, api, actions) }
}

@Composable
private fun WeekBars(days: List<Long>, now: Long) {
    val colors = LocalChildColors.current
    val names = stringArrayResource(R.array.child_days_short)
    val max = (days.maxOrNull() ?: 0).coerceAtLeast(MINUTE)
    val todayIndex = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).dayOfWeek.value - 1

    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { index, ms ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((ms / MINUTE).toString(), color = colors.secondary, fontSize = 11.sp)
                Box(Modifier.width(20.dp).height((80f * ms / max).dp.coerceAtLeast(2.dp)).background(if (index == 6) colors.action else colors.secondary))
                Text(names[(todayIndex - 6 + index + 14) % 7], color = colors.secondary, fontSize = 11.sp)
            }
        }
    }
}
