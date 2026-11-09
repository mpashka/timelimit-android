package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.ChildHome
import io.timelimit.api.TabletLine
import io.timelimit.ui.R
import io.timelimit.ui.child.LocalChildColors
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.child.formatDuration

/** In a few words: in touch or last seen when, and what is open now. */
@Composable
fun tabletStatus(tablet: TabletLine): String = when {
    !tablet.online && tablet.seen == 0L -> stringResource(R.string.parent_tablet_unknown)
    !tablet.online -> stringResource(R.string.parent_tablet_offline, formatClock(tablet.seen))
    tablet.appNow != null -> stringResource(R.string.parent_tablet_now, tablet.appNow!!.title)
    else -> stringResource(R.string.parent_tablet_online)
}

/** The tablets of «Сегодня», behind the «Планшеты · N ▾» button: a row each, the chosen one lit. */
// @tag:tablet-filter
@Composable
fun TabletList(child: ChildHome, chosen: String?, choose: (String?) -> Unit, onSettings: () -> Unit) {
    val colors = LocalChildColors.current

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(vertical = 4.dp)) {
        child.tablets.forEach { tablet ->
            val itsTurn = tablet.id == chosen
            val toggle = { choose(if (itsTurn) null else tablet.id) }

            Row(
                Modifier.fillMaxWidth().background(if (itsTurn) colors.action.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { toggle() }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tablet.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(tabletStatus(tablet), color = if (tablet.online) colors.allowed else colors.secondary, fontSize = 13.sp)
                }
                tablet.todayMs?.let { Text(formatDuration(it), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                TabletMenu(tablet, itsTurn, toggle, onSettings)
            }
        }
    }
}

/**
 * One menu for a tablet: its own time first, then the settings. Neither «на сегодня» nor «закрыть» —
 * the limits belong to the child, not to a tablet, as in the web console.
 */
@Composable
private fun TabletMenu(tablet: TabletLine, chosen: Boolean, toggle: () -> Unit, onSettings: () -> Unit) {
    RowMenu(tablet.name, tabletStatus(tablet)) {
        MenuItem(stringResource(if (chosen) R.string.parent_all_tablets else R.string.parent_tablet_usage), onClick = toggle)
        MenuItem(stringResource(R.string.parent_tablet_settings), onClick = onSettings)
    }
}

/** Nothing to limit yet: the new interface cannot connect a tablet, that is the old one and the family server. */
@Composable
fun NoTabletCard() {
    Card { Text(stringResource(R.string.parent_no_tablet_yet), color = LocalChildColors.current.text, fontSize = 14.sp) }
}

@Composable
fun TabletsScreen(child: ChildHome) {
    val colors = LocalChildColors.current

    if (child.tablets.isEmpty()) NoTabletCard()
    child.tablets.forEach { tablet ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tablet.name, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                tablet.todayMs?.let { Text(formatDuration(it), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
            Text(tabletStatus(tablet), color = if (tablet.online) colors.allowed else colors.secondary, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
}
