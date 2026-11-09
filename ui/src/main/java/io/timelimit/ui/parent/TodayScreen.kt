package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.TextButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import io.timelimit.api.AppTime
import io.timelimit.api.AppUsage
import io.timelimit.api.ChildHome
import io.timelimit.api.NewAppLine
import io.timelimit.api.ParentApi
import io.timelimit.api.ParentCategory
import io.timelimit.ui.R
import io.timelimit.ui.child.AppIcon
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.WeekLine
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.child.formatDuration

internal const val MINUTE = 60_000L
private const val MAX_CATEGORY_TITLE = 50
private val GRANTS = listOf(15, 30, 60)
/** Less than this a day is noise: such rows wait behind «Ещё N — меньше минуты». */
internal const val NOTICEABLE_MS = MINUTE
private const val PREFS = "parent_ui"
private const val PREF_CATEGORIES_TAB = "home_categories_tab"

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalChildColors.current.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/**
 * A one-off action, not a choice: a plain bordered label, so no button state (focus, hover, the first
 * of a group on a keyboard tablet) can make one look selected.
 */
@Composable
fun Chip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalChildColors.current
    val shape = RoundedCornerShape(8.dp)

    Box(
        Modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, colors.secondary.copy(alpha = 0.4f), shape)
            .background(colors.surface).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.action, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
fun usageFailure(usage: AppUsage.Failed): String = when (usage.httpCode) {
    401, 403 -> stringResource(R.string.parent_usage_failed_auth, usage.httpCode!!)
    404 -> stringResource(R.string.parent_usage_failed_old_server)
    null -> stringResource(R.string.parent_usage_failed_network, usage.detail)
    else -> stringResource(R.string.parent_usage_failed_http, usage.httpCode!!, usage.detail)
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = LocalChildColors.current.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

/** P1–P4, P6 of docs/specification/parent-ui.md. */
// @tag:new-ui
@Composable
fun TodayScreen(child: ChildHome, api: ParentApi, actions: ParentActions, onApp: (String) -> Unit, onTablets: () -> Unit) {
    val colors = LocalChildColors.current
    val usage = child.usage
    // one tablet chosen covers both tabs and the time above them, as in the web console
    var tablet by rememberSaveable { mutableStateOf<String?>(null) }
    var tabletsShown by rememberSaveable { mutableStateOf(false) }
    // a tablet taken out of the family leaves no filter behind it: the chosen one is read through the list
    val chosen = child.tablets.find { it.id == tablet }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (chosen == null) stringResource(R.string.parent_today) else stringResource(R.string.parent_today_on_tablet, chosen.name),
                    color = colors.secondary
                )
                if (usage is AppUsage.Known) {
                    Text(formatDuration(usage.today.sumOf { timeOn(it, chosen?.id) }), color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    if (chosen == null) Text(stringResource(R.string.parent_on_tablets, child.tablets.size), color = colors.secondary, fontSize = 14.sp)
                }
            }
            if (child.tablets.isNotEmpty()) TextButton(onClick = { tabletsShown = !tabletsShown }) {
                Text(stringResource(R.string.parent_tablets_button, child.tablets.size) + if (tabletsShown) " ▴" else " ▾")
            }
        }
    }
    if (child.tablets.isEmpty()) NoTabletCard()
    if (tabletsShown) TabletList(child, chosen?.id, { tablet = it }, onTablets)
    chosen?.let { Chip(stringResource(R.string.parent_only_tablet, it.name) + " ✕") { tablet = null } }

    ModeCard(child, api, actions)
    if (child.allowances.isNotEmpty()) AllowanceCard(child, api, actions)
    child.newApps.forEach { NewAppCard(it, child, api, actions) }

    // the chosen tab outlives the app, as homeTab in the web console's localStorage
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var categoriesTab by remember { mutableStateOf(prefs.getBoolean(PREF_CATEGORIES_TAB, false)) }
    TabRow(selectedTabIndex = if (categoriesTab) 1 else 0, backgroundColor = colors.surface, contentColor = colors.action) {
        listOf(R.string.parent_tab_apps, R.string.parent_categories).forEachIndexed { index, title ->
            Tab(selected = categoriesTab == (index == 1), onClick = {
                categoriesTab = index == 1
                prefs.edit().putBoolean(PREF_CATEGORIES_TAB, categoriesTab).apply()
            }, text = { Text(stringResource(title)) })
        }
    }
    if (categoriesTab) CategoryTree(child, api, actions, onApp, chosen?.id) else AppList(child, api, actions, onApp, chosen?.id)
}

/** The app's time on the chosen tablet, or on all of them when none is chosen. */
// @tag:tablet-filter
fun timeOn(line: AppTime, tablet: String?): Long = if (tablet == null) line.ms else line.byTablet[tablet] ?: 0

@Composable
private fun ModeCard(child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    val closedAll = stringResource(R.string.parent_closed_all_until)

    Card {
        val mode = child.modeNow
        if (mode != null) {
            Text(stringResource(R.string.parent_mode_now, formatClock(mode.until)), color = colors.closed, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(
                stringResource(R.string.parent_open_now) + (child.nextMode?.let { " · " + stringResource(R.string.parent_mode_from, formatClock(it.from)) } ?: ""),
                color = colors.allowed, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.parent_close_all), color = colors.secondary, fontSize = 14.sp)
            listOf(
                formatDuration(30 * MINUTE) to child.now + 30 * MINUTE,
                formatDuration(60 * MINUTE) to child.now + 60 * MINUTE,
                stringResource(R.string.parent_until_morning) to child.morning,
            ).forEach { (label, until) ->
                Chip(label) { actions(closedAll.format(formatClock(until))) { api.closeAll(until) } }
            }
        }
    }
}

/** What is open over its limits right now, and the way to take it back before the time is out. */
// @tag:app-allowance
@Composable
private fun AllowanceCard(child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    val removed = stringResource(R.string.parent_allowance_removed)

    Card {
        Text(stringResource(R.string.parent_allowances), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        child.allowances.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(line.app.packageName, line.app.title, 24.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.app.title, color = colors.text, fontSize = 15.sp)
                    Text(stringResource(R.string.parent_allowed_until, formatClock(line.until)), color = colors.secondary, fontSize = 13.sp)
                }
                Chip(stringResource(R.string.parent_allowance_remove)) {
                    actions(removed.format(line.app.title)) { api.removeAppAllowance(line.app.packageName) }
                }
            }
        }
    }
}

/** Today's apps by time, the noticeable ones; the rest behind one line that says how many and why hidden. */
@Composable
private fun AppList(child: ChildHome, api: ParentApi, actions: ParentActions, onApp: (String) -> Unit, tablet: String?) {
    val colors = LocalChildColors.current
    var all by rememberSaveable { mutableStateOf(false) }

    when (val usage = child.usage) {
        is AppUsage.Known -> {
            val apps = usage.today.map { it.copy(ms = timeOn(it, tablet)) }.filter { it.ms > 0 }.sortedByDescending { it.ms }
            val shown = if (all) apps else apps.filter { it.ms >= NOTICEABLE_MS }
            val max = apps.firstOrNull()?.ms ?: 0

            if (apps.isEmpty()) Text(stringResource(R.string.parent_no_apps_today), color = colors.secondary, fontSize = 14.sp)
            shown.forEach { line ->
                val note = listOfNotNull(line.category?.title, line.rule?.let { ruleText(it) }).joinToString(" · ")
                AppRow(line, note, api, actions, max) { onApp(line.app.packageName) }
            }
            if (shown.size < apps.size) Row(Modifier.fillMaxWidth().clickable { all = true }.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.parent_more_under_minute, apps.size - shown.size), color = colors.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.parent_show), color = colors.action, fontSize = 14.sp)
            }
        }
        AppUsage.Loading -> Text(stringResource(R.string.parent_usage_loading), color = colors.secondary, fontSize = 14.sp)
        AppUsage.NeedsSignIn -> Text(stringResource(R.string.parent_usage_needs_sign_in), color = colors.secondary, fontSize = 14.sp)
        is AppUsage.Failed -> Text(usageFailure(usage), color = colors.secondary, fontSize = 14.sp)
    }
}

@Composable
fun AppRow(line: AppTime, note: String?, api: ParentApi, actions: ParentActions, max: Long = 0, onClick: () -> Unit) {
    val colors = LocalChildColors.current

    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(line.app.packageName, line.app.title, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(line.app.title, color = colors.text, fontSize = 16.sp)
            note?.takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.secondary, fontSize = 13.sp) }
            // the bar is relative to the longest app of the day, as in the web console
            if (max > 0) LinearProgressIndicator(
                progress = line.ms.toFloat() / max, color = colors.action, backgroundColor = colors.ground,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        Text(formatDuration(line.ms), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        AppMenu(line, api, actions, onMore = onClick)
    }
}

/** One menu for an app: today's allowance first, then closing for good, then the card with the rest. */
// @tag:app-allowance @tag:app-rule
@Composable
private fun AppMenu(line: AppTime, api: ParentApi, actions: ParentActions, onMore: () -> Unit) {
    val allowed = stringResource(R.string.parent_app_allowed)
    val set = stringResource(R.string.parent_rule_set)
    val title = line.app.title
    val closedAlways = line.rule != null && line.rule!!.days and 127 == 0

    RowMenu(title, line.allowedUntil?.let { stringResource(R.string.parent_allowed_until, formatClock(it)) }) {
        MenuLabel(stringResource(R.string.parent_allow_over_limit))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GRANTS.forEach { minutes ->
                val span = formatDuration(minutes * MINUTE)
                MenuChip("+$span") { actions(allowed.format(title, span)) { api.allowApp(line.app.packageName, minutes) } }
            }
        }
        MenuItem(stringResource(if (closedAlways) R.string.parent_open_again else R.string.parent_close_always), closer = !closedAlways) {
            actions(set.format(title, if (closedAlways) "—" else "×")) {
                api.setAppRule(line.app.packageName, if (closedAlways) 127 else 0, line.rule?.limitMinutes ?: -1)
            }
        }
        MenuItem(stringResource(R.string.parent_app_more), onClick = onMore)
    }
}

@Composable
private fun NewAppCard(line: NewAppLine, child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    var more by rememberSaveable(line.app.packageName) { mutableStateOf(false) }
    val movedTo = stringResource(R.string.parent_moved_to)

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(line.app.packageName, line.app.title, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(line.app.title + " · " + stringResource(R.string.parent_new), color = colors.text, fontSize = 16.sp)
                Text(
                    stringResource(R.string.parent_installed_at, formatClock(line.installedAt)) + (line.tabletName?.let { " · $it" } ?: "") +
                            " · " + stringResource(R.string.parent_closed_for_now),
                    color = colors.endingSoon, fontSize = 13.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val first = line.guessedCategory ?: child.categories.firstOrNull()?.ref
            first?.let { category ->
                Chip(stringResource(R.string.parent_to_category, category.title)) {
                    actions(movedTo.format(line.app.title, category.title)) { api.moveApp(line.app.packageName, category.id) }
                }
            }
            if (!more) Chip(stringResource(R.string.parent_other_category)) { more = true }
        }
        if (more) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            child.categories.filter { it.depth == 0 }.forEach { category ->
                Chip(category.ref.title) {
                    actions(movedTo.format(line.app.title, category.ref.title)) { api.moveApp(line.app.packageName, category.ref.id) }
                }
            }
        }
    }
}

@Composable
fun CategoryCard(category: ParentCategory, child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    var editing by rememberSaveable(category.ref.id) { mutableStateOf(false) }

    Card {
        if (editing) RenameField(category, api, actions) { editing = false }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(category.ref.title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(categoryState(category), color = categoryStateColor(category), fontSize = 15.sp)
            CategoryMenu(category, child, api, actions, onRename = { editing = true })
        }
        category.limit?.let { limit ->
            LinearProgressIndicator(
                progress = (category.usedToday.toFloat() / limit).coerceIn(0f, 1f),
                color = colors.action, backgroundColor = colors.ground, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.parent_used_of, formatDuration(category.usedToday), formatDuration(limit)), color = colors.secondary, fontSize = 13.sp)
        } ?: run {
            if (category.usedToday > 0) Text(stringResource(R.string.parent_used_today, formatDuration(category.usedToday)), color = colors.secondary, fontSize = 13.sp)
        }
        category.week?.let { WeekLine(it, 13.sp) }
        category.limitedBy?.let { Text(stringResource(R.string.limited_by_parent_category, it), color = colors.secondary, fontSize = 13.sp) }
    }
}

/** The new name in place of the old one; [onDone] hides the field, saved or not. */
@Composable
fun RenameField(category: ParentCategory, api: ParentApi, actions: ParentActions, onDone: () -> Unit) {
    val renamed = stringResource(R.string.parent_renamed)
    var name by rememberSaveable(category.ref.id) { mutableStateOf(category.ref.title) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it.take(MAX_CATEGORY_TITLE) }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            onDone()
            actions(renamed.format(name.trim())) { api.renameCategory(category.ref.id, name) }
        }, enabled = name.isNotBlank()) { Text(stringResource(R.string.parent_save)) }
        TextButton(onClick = onDone) { Text(stringResource(R.string.parent_cancel)) }
    }
}

@Composable
fun categoryStateColor(category: ParentCategory): Color {
    val colors = LocalChildColors.current

    return when {
        category.closedByParent || category.closedByModeUntil != null && category.allowedUntil == null -> colors.closed
        category.remaining != null && category.remaining!! < 10 * MINUTE -> colors.endingSoon
        else -> colors.allowed
    }
}

@Composable
fun categoryState(category: ParentCategory): String = categoryClosedState(category)
    ?: if (category.remaining == null) stringResource(R.string.parent_no_limit)
    else stringResource(R.string.parent_remaining, formatDuration(category.remaining!!))

/** What closed the category or opened it over the limits; null — nothing of the kind, the limits speak for themselves. */
@Composable
fun categoryClosedState(category: ParentCategory): String? = when {
    category.closedByParent -> stringResource(R.string.parent_closed_by_parent) +
            (category.closedByParentUntil?.let { " " + stringResource(R.string.parent_until, formatClock(it)) } ?: "")
    category.allowedUntil != null -> stringResource(R.string.parent_allowed_until, formatClock(category.allowedUntil!!))
    category.closedByModeUntil != null -> stringResource(R.string.parent_closed_mode_until, formatClock(category.closedByModeUntil!!))
    else -> null
}

/** One menu for a category: time for today first, then closing, then the name the parent gave it. */
@Composable
fun CategoryMenu(category: ParentCategory, child: ChildHome, api: ParentApi, actions: ParentActions, onRename: () -> Unit) {
    val added = stringResource(R.string.parent_added)
    val closed = stringResource(R.string.parent_closed_until)
    val opened = stringResource(R.string.parent_opened)
    val limited = stringResource(R.string.parent_limits_back)
    val title = category.ref.title

    RowMenu(title, categoryState(category)) {
        MenuLabel(stringResource(R.string.parent_add_today))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GRANTS.forEach { minutes ->
                MenuChip("+$minutes") { actions(added.format(title, minutes)) { api.addTime(category.ref.id, minutes) } }
            }
        }
        if (category.closedByParent) MenuItem(stringResource(R.string.parent_open)) {
            actions(opened.format(title)) { api.openCategory(category.ref.id) }
        } else MenuItem(stringResource(R.string.parent_close_until_morning), hint = formatClock(child.morning), closer = true) {
            actions(closed.format(title, formatClock(child.morning))) { api.closeCategory(category.ref.id, child.morning) }
        }
        if (category.allowedUntil != null) MenuItem(stringResource(R.string.parent_return_limits)) {
            actions(limited.format(title)) { api.returnLimits(category.ref.id) }
        }
        MenuItem(stringResource(R.string.parent_rename), onClick = onRename)
    }
}
