package io.timelimit.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import io.timelimit.api.ChildHome
import io.timelimit.api.ModeKind
import io.timelimit.api.ParentApi
import io.timelimit.api.ScheduleLine
import io.timelimit.api.ScheduleSpec
import io.timelimit.ui.R
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.child.formatDay

private const val DAY = 24 * 60

private fun clock(minute: Int) = "%02d:%02d".format((minute % DAY) / 60, minute % 60)

private fun parseClock(text: String): Int? =
    Regex("^(\\d{1,2})[:.](\\d{2})$").find(text.trim())?.destructured?.let { (h, m) ->
        (h.toInt() * 60 + m.toInt()).takeIf { h.toInt() in 0..24 && m.toInt() in 0..59 && it <= DAY }
    }

/** P9: «Сон» and «Учёба» — a switch, from–to, days, what stays open (docs/specification/parent-ui.md, «Режимы»). */
// @tag:new-ui @tag:ban-schedule
@Composable
fun ModesScreen(child: ChildHome, api: ParentApi, actions: ParentActions) {
    child.schedules.forEach { ScheduleCard(it, child, api, actions) }

    if (child.otherBans > 0) Text(
        stringResource(R.string.parent_other_bans, child.otherBans),
        color = LocalChildColors.current.secondary, fontSize = 14.sp
    )
}

@Composable
private fun ScheduleCard(line: ScheduleLine, child: ChildHome, api: ParentApi, actions: ParentActions) {
    val colors = LocalChildColors.current
    val title = stringResource(if (line.kind == ModeKind.Sleep) R.string.parent_sleep else R.string.parent_study)
    val spec = line.spec
    val names = stringArrayResource(R.array.child_days_short)
    val roots = child.categories.filter { it.depth == 0 }
    var editing by rememberSaveable(line.kind) { mutableStateOf(false) }
    val set = { wanted: ScheduleSpec?, done: String -> actions(done) { api.setSchedule(line.kind, wanted) } }
    val label = { s: ScheduleSpec -> "$title: ${clock(s.start)}–${clock(s.end + 1)}" }
    val turnedOff = stringResource(R.string.parent_mode_turned_off, title)

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !line.on -> stringResource(R.string.parent_mode_off)
                    line.window == null -> ""
                    line.window!!.from <= child.now -> stringResource(R.string.parent_mode_until, formatClock(line.window!!.until))
                    else -> stringResource(R.string.parent_mode_starts, formatDay(line.window!!.from, child.now), formatClock(line.window!!.from))
                },
                color = colors.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            Switch(
                checked = line.on,
                onCheckedChange = { on -> set(if (on) spec else null, if (on) label(spec) else turnedOff) }
            )
        }

        Text(stringResource(R.string.parent_mode_from_to, clock(spec.start), clock(spec.end + 1)), color = if (line.on) colors.closed else colors.secondary, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        if (line.on) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                names.forEachIndexed { day, name ->
                    val selected = spec.days and (1 shl day) != 0
                    Button(
                        onClick = { spec.copy(days = spec.days xor (1 shl day)).let { set(it, label(it)) } },
                        enabled = spec.days != (1 shl day),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (selected) colors.action else colors.ground,
                            contentColor = if (selected) Color.White else colors.text
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.width(44.dp)
                    ) { Text(name, fontSize = 13.sp) }
                }
            }
            val open = roots.filterNot { it.ref.id in spec.categoryIds }.map { it.ref.title }
            Text(stringResource(R.string.parent_mode_open, open.joinToString().ifEmpty { stringResource(R.string.parent_nothing) }), color = colors.text, fontSize = 14.sp)
            if (line.exceptions > 0) Text(stringResource(R.string.parent_mode_exceptions), color = colors.secondary, fontSize = 13.sp)
        }

        if (!editing) TextButton(onClick = { editing = true }) { Text(stringResource(R.string.parent_change)) }
        else ScheduleForm(line.kind, spec, roots.map { it.ref.id to it.ref.title }, onCancel = { editing = false }) { wanted ->
            editing = false
            set(wanted, label(wanted))
        }
    }
}

@Composable
private fun ScheduleForm(kind: ModeKind, spec: ScheduleSpec, categories: List<Pair<String, String>>, onCancel: () -> Unit, onSave: (ScheduleSpec) -> Unit) {
    val colors = LocalChildColors.current
    var from by rememberSaveable { mutableStateOf(clock(spec.start)) }
    var to by rememberSaveable { mutableStateOf(clock(spec.end + 1)) }
    var closed by rememberSaveable { mutableStateOf(spec.categoryIds) }
    val start = parseClock(from)?.rem(DAY)
    val end = parseClock(to)?.let { (it + DAY - 1) % DAY }
    val fits = start != null && end != null && ModeKind.of(start, end) == kind

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.parent_from)) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.parent_to)) }, modifier = Modifier.weight(1f), singleLine = true)
    }
    if (!fits) Text(
        stringResource(if (kind == ModeKind.Sleep) R.string.parent_sleep_shape else R.string.parent_study_shape),
        color = colors.refused, fontSize = 13.sp
    )
    Text(stringResource(R.string.parent_mode_closes), color = colors.secondary, fontSize = 13.sp)
    categories.forEach { (id, title) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = id in closed, onCheckedChange = { closed = if (it) closed + id else closed - id })
            Text(title, color = colors.text)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(spec.copy(start = start!!, end = end!!, categoryIds = closed)) }, enabled = fits && closed.isNotEmpty()) {
            Text(stringResource(R.string.parent_save))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.parent_cancel)) }
    }
}
