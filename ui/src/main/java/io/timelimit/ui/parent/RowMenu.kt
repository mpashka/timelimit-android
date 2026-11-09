package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.timelimit.ui.R
import io.timelimit.ui.child.LocalChildColors

/** Closes the open sheet: every piece of a menu does it before its own action. */
internal val LocalMenuClose = staticCompositionLocalOf<() -> Unit> { {} }

/** A tap without the ripple: the sheet and the space around it react, but do not look pressed. */
@Composable
private fun Modifier.tap(onTap: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }

    return clickable(interactionSource = interaction, indication = null, onClick = onTap)
}

/**
 * The one menu of actions on a row — app, category or tablet alike (`parent-ui.md`, «Главный
 * экран»): a ⋮ on the row, a sheet from the bottom. Any piece of the menu closes the sheet, so the
 * outcome is told by the snackbar over the screen, not inside a menu that is already gone.
 */
// @tag:new-ui
@Composable
fun RowMenu(title: String, subtitle: String? = null, items: @Composable ColumnScope.() -> Unit) {
    val colors = LocalChildColors.current
    var open by rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.parent_actions, title), tint = colors.secondary)
    }

    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // the whole screen is the dialog, so the sheet sits at the bottom and a tap beside it closes
        Box(Modifier.fillMaxSize().tap { open = false }, contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.surface).tap { }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.secondary, fontSize = 13.sp) }
                CompositionLocalProvider(LocalMenuClose provides { open = false }) { items() }
            }
        }
    }
}

/** A line of the menu; [hint] is the quiet part on the right, «до ЧЧ:ММ» of «Закрыть до утра». */
@Composable
fun MenuItem(text: String, hint: String? = null, closer: Boolean = false, onClick: () -> Unit) {
    val colors = LocalChildColors.current
    val close = LocalMenuClose.current

    Row(
        Modifier.fillMaxWidth().clickable { close(); onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = if (closer) colors.closed else colors.action, fontSize = 16.sp, modifier = Modifier.weight(1f))
        hint?.let { Text(it, color = colors.secondary, fontSize = 13.sp) }
    }
}

@Composable
fun MenuLabel(text: String) = Text(text, color = LocalChildColors.current.secondary, fontSize = 13.sp)

@Composable
fun MenuChip(text: String, onClick: () -> Unit) {
    val close = LocalMenuClose.current

    Chip(text) { close(); onClick() }
}
