package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.AppTime
import io.timelimit.api.ChildHome
import io.timelimit.api.ParentApi
import io.timelimit.api.ParentCategory
import io.timelimit.ui.R
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.WeekLine
import io.timelimit.ui.child.formatDuration
import java.text.Collator
import java.util.Locale

private val INDENT = 20.dp
private val ROW_START = 8.dp
private val LIMIT_PRESETS = listOf(30, 60, 90, 120)

/**
 * The «Категории» tab: headers joined by tree lines, one category open at a time, its apps on their
 * own backing below the header. As CategoryTree of the web console (timelimit-parent src/web/home.tsx).
 */
// @tag:category-tree
@Composable
fun CategoryTree(child: ChildHome, api: ParentApi, actions: ParentActions, onApp: (String) -> Unit) {
    val colors = LocalChildColors.current
    val categories = child.categories
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // categories come parent first, children right after it: the last of its siblings ends its branch
    val lastIds = categories.groupBy { it.parentId }.values.map { it.last().ref.id }.toSet()
    val byId = categories.associateBy { it.ref.id }

    if (categories.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(vertical = 4.dp)) {
        categories.forEach { category ->
            val id = category.ref.id
            // an ancestor with siblings still to come keeps its vertical line through this row
            val rails = generateSequence(category.parentId?.let { byId[it] }) { it.parentId?.let(byId::get) }
                .filter { it.depth > 0 && it.ref.id !in lastIds }.map { it.depth }.toSet()

            CategoryNode(category, child, api, actions, last = id in lastIds, rails = rails, open = open == id) {
                open = if (open == id) null else id
            }
            if (open == id) CategoryApps(category, api, actions, onApp)
        }
    }
}

@Composable
private fun CategoryNode(
    category: ParentCategory, child: ChildHome, api: ParentApi, actions: ParentActions,
    last: Boolean, rails: Set<Int>, open: Boolean, toggle: () -> Unit,
) {
    val colors = LocalChildColors.current
    val line = colors.secondary.copy(alpha = 0.4f)
    var editing by rememberSaveable(category.ref.id) { mutableStateOf(false) }

    if (editing) Row(Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.weight(1f)) { RenameField(category, api, actions) { editing = false } }
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (open) colors.action.copy(alpha = 0.08f) else Color.Transparent)
            .drawBehind {
                val stroke = 1.dp.toPx()
                fun railX(level: Int) = (ROW_START + INDENT * (level - 1) + INDENT / 2).toPx()

                rails.forEach { level -> drawLine(line, Offset(railX(level), 0f), Offset(railX(level), size.height), stroke) }
                if (category.depth > 0) {
                    val x = railX(category.depth)
                    val middle = size.height / 2

                    drawLine(line, Offset(x, 0f), Offset(x, if (last) middle else size.height), stroke)
                    drawLine(line, Offset(x, middle), Offset((ROW_START + INDENT * category.depth).toPx(), middle), stroke)
                }
            }
            .clickable(onClick = toggle)
            .padding(start = ROW_START + INDENT * category.depth, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, contentDescription = null, tint = colors.secondary)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(category.ref.title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            category.limit?.let { limit ->
                Text(stringResource(R.string.parent_used_of, formatDuration(category.usedToday), formatDuration(limit)), color = colors.secondary, fontSize = 13.sp)
                LinearProgressIndicator(
                    progress = (category.usedToday.toFloat() / limit).coerceIn(0f, 1f),
                    color = colors.action, backgroundColor = colors.ground, modifier = Modifier.fillMaxWidth()
                )
            } ?: run {
                if (category.usedToday > 0) Text(stringResource(R.string.parent_used_today, formatDuration(category.usedToday)), color = colors.secondary, fontSize = 13.sp)
            }
            category.week?.let { WeekLine(it, 13.sp) }
            category.limitedBy?.let { Text(stringResource(R.string.limited_by_parent_category, it), color = colors.secondary, fontSize = 13.sp) }
        }
        Spacer(Modifier.width(8.dp))
        Text(categoryState(category), color = categoryStateColor(category), fontSize = 14.sp)
        CategoryMenu(category, child, api, actions, onRename = { editing = true })
    }
}

/** Inside an open category: its apps by time (the noticeable ones, «все» below) or all of them by name with the day limit. */
@Composable
private fun CategoryApps(category: ParentCategory, api: ParentApi, actions: ParentActions, onApp: (String) -> Unit) {
    val colors = LocalChildColors.current
    var manage by rememberSaveable(category.ref.id) { mutableStateOf(false) }
    var all by rememberSaveable(category.ref.id) { mutableStateOf(false) }
    val collator = Collator.getInstance(Locale("ru"))
    val apps = category.apps
    val used = apps.filter { it.ms >= NOTICEABLE_MS }.sortedByDescending { it.ms }
    val listed = when {
        manage -> apps.sortedWith(compareBy(collator) { it.app.title })
        all -> apps.sortedWith(compareByDescending<AppTime> { it.ms }.thenBy(collator) { it.app.title })
        else -> used
    }
    val max = if (manage) 0 else apps.maxOfOrNull { it.ms } ?: 0

    Column(Modifier.fillMaxWidth().background(colors.ground).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TabRow(selectedTabIndex = if (manage) 1 else 0, backgroundColor = colors.ground, contentColor = colors.action) {
            listOf(R.string.parent_mode_time, R.string.parent_mode_manage).forEachIndexed { index, title ->
                Tab(selected = manage == (index == 1), onClick = { manage = index == 1 }, text = { Text(stringResource(title)) })
            }
        }
        if (manage) DayLimit(category, api, actions)
        if (listed.isEmpty()) Text(
            stringResource(if (apps.isEmpty()) R.string.parent_no_apps_in_category else R.string.parent_no_apps_today_in_category),
            color = colors.secondary, fontSize = 14.sp
        )
        listed.forEach { line -> AppRow(line, line.rule?.let { ruleText(it) }, api, actions, max) { onApp(line.app.packageName) } }
        if (!manage && !all && apps.size > used.size) Row(Modifier.fillMaxWidth().clickable { all = true }.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.parent_all_apps_of_category, apps.size), color = colors.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.parent_show), color = colors.action, fontSize = 14.sp)
        }
    }
}

/** One limit for every day, as in the web console: «нет» or a preset, the current one marked and not pressable. */
// @tag:category-limits
@Composable
private fun DayLimit(category: ParentCategory, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    val limitSet = stringResource(R.string.parent_limit_set)
    val limitNone = stringResource(R.string.parent_limit_none)
    val title = category.ref.title
    val current = category.dailyLimit.minutes
    val presets = (LIMIT_PRESETS + listOfNotNull(current)).distinct().sorted()

    fun label(text: String, chosen: Boolean) = if (chosen) "✓ $text" else text

    Text(stringResource(R.string.parent_day_limit), color = colors.secondary, fontSize = 13.sp)
    // five chips do not fit a phone's row
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(label(stringResource(R.string.parent_no), current == null), enabled = current != null) {
            actions(limitNone.format(title)) { api.setCategoryLimit(category.ref.id, null) }
        }
        presets.forEach { minutes ->
            val span = formatDuration(minutes * MINUTE)
            Chip(label(span, current == minutes), enabled = current != minutes) {
                actions(limitSet.format(title, span)) { api.setCategoryLimit(category.ref.id, minutes) }
            }
        }
    }
    if (category.dailyLimit.mixed) Text(stringResource(R.string.parent_limit_mixed), color = colors.secondary, fontSize = 13.sp)
}
