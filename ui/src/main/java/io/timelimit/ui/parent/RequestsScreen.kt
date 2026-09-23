package io.timelimit.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.AppRuleLine
import io.timelimit.api.ChildHome
import io.timelimit.api.GrantScope
import io.timelimit.api.ParentApi
import io.timelimit.api.ParentRequest
import io.timelimit.ui.R
import io.timelimit.ui.child.AppIcon
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.child.formatDuration

private const val MINUTE = 60_000L

@Composable
fun ruleText(rule: AppRuleLine): String? {
    val names = stringArrayResource(R.array.child_days_short)
    val chosen = (0 until 7).filter { rule.days and (1 shl it) != 0 }.map { names[it] }

    return when {
        rule.days and 127 == 0 -> stringResource(R.string.parent_rule_closed)
        rule.days and 127 != 127 -> stringResource(R.string.parent_rule_days,
            if (chosen.size <= 1) chosen.joinToString() else chosen.dropLast(1).joinToString(", ") + " и " + chosen.last())
        rule.limitMinutes >= 0 -> stringResource(R.string.parent_rule_limit, formatDuration(rule.limitMinutes * MINUTE))
        else -> null
    }
}

/** The bell: what the child asks for, answered in one tap (docs/specification/parent-ui.md, «Просьбы»). */
// @tag:new-ui @tag:child-request
@Composable
fun RequestsScreen(child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current

    SectionTitle(stringResource(R.string.parent_requests))
    if (child.requests.isEmpty()) Text(stringResource(R.string.parent_no_requests), color = colors.secondary)
    child.requests.forEach { RequestCard(it, child, api, actions) }

    if (child.answeredToday.isNotEmpty()) {
        SectionTitle(stringResource(R.string.parent_earlier_today))
        child.answeredToday.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(line.app.packageName, line.app.title, 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    line.app.title + " — " + if (line.allowed) stringResource(R.string.parent_allowed_until, formatClock(line.until))
                    else stringResource(R.string.parent_denied),
                    color = colors.text, modifier = Modifier.weight(1f)
                )
                Text(formatClock(line.at), color = colors.secondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RequestCard(request: ParentRequest, child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    var scope by rememberSaveable(request.id) { mutableStateOf(GrantScope.App) }
    val allowed = stringResource(R.string.parent_allowed_done)
    val denied = stringResource(R.string.parent_denied_done)
    val category = request.category

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(request.app.packageName, request.app.title, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.parent_request_title, child.name, request.app.title), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(listOfNotNull(formatClock(request.at), request.tabletName, category?.ref?.title).joinToString(" · "), color = colors.secondary, fontSize = 13.sp)
        if (request.word.isNotEmpty()) Text("«${request.word}»", color = colors.text, fontSize = 16.sp)
        Text(stringResource(R.string.parent_app_today, request.app.title, formatDuration(request.appToday ?: 0)), color = colors.secondary, fontSize = 14.sp)
        category?.let {
            Text(
                it.limit?.let { limit -> stringResource(R.string.parent_category_today_of, it.ref.title, formatDuration(it.usedToday), formatDuration(limit)) }
                    ?: stringResource(R.string.parent_category_today, it.ref.title, formatDuration(it.usedToday)),
                color = colors.secondary, fontSize = 14.sp
            )
        }
        request.rule?.let { ruleText(it) }?.let { Text(stringResource(R.string.parent_why_closed, request.app.title + " — " + it), color = colors.closed, fontSize = 14.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScopeButton(request.app.title, scope == GrantScope.App, true) { scope = GrantScope.App }
            ScopeButton(
                stringResource(R.string.parent_all_category, category?.ref?.title ?: stringResource(R.string.child_no_category)),
                scope == GrantScope.Category, category != null
            ) { scope = GrantScope.Category }
        }
        if (category == null) Text(stringResource(R.string.parent_no_category_why), color = colors.secondary, fontSize = 13.sp)

        listOf(
            formatDuration(15 * MINUTE) to child.now + 15 * MINUTE,
            formatDuration(30 * MINUTE) to child.now + 30 * MINUTE,
            formatDuration(60 * MINUTE) to child.now + 60 * MINUTE,
            stringResource(R.string.child_grant_day_end) to child.dayEnd,
        ).filter { it.second > child.now }.forEach { (label, until) ->
            Button(onClick = {
                val what = if (scope == GrantScope.App) request.app.title else category?.ref?.title ?: ""
                actions(allowed.format(what, formatClock(until))) { api.answer(request.id, scope, until) }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("$label — ${stringResource(R.string.child_until, formatClock(until))}")
            }
        }
        OutlinedButton(onClick = { actions(denied.format(request.app.title)) { api.deny(request.id) } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.parent_deny), color = colors.refused)
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ScopeButton(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChildColors.current

    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) colors.action else colors.ground,
            contentColor = if (selected) Color.White else colors.text
        )
    ) { Text(text) }
}
