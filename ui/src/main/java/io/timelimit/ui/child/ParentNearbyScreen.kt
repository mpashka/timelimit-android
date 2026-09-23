package io.timelimit.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.App
import io.timelimit.api.GrantChoice
import io.timelimit.api.GrantScope
import io.timelimit.api.ParentCode
import io.timelimit.ui.R
import kotlinx.coroutines.launch

private const val CODE_LENGTH = 6
private const val MINUTE = 60_000L

/** C5 of docs/specification/mockups/child.html: the parent types the code, then opens the app or its category. */
// @tag:new-ui @tag:parent-code
@Composable
fun ParentNearby(
    app: App,
    choice: GrantChoice,
    checkCode: suspend (String) -> ParentCode?,
    grant: suspend (ParentCode, GrantScope, Long) -> Unit,
    onBack: () -> Unit,
    onUpstreamScreen: () -> Unit,
) {
    val colors = LocalChildColors.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<ParentCode?>(null) }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().background(colors.ground),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.child_parent_nearby), color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        val verified = code
        if (verified == null) {
            Text(stringResource(R.string.child_code_hint), color = colors.secondary, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(CODE_LENGTH) { index ->
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(colors.surface)
                            .border(1.dp, if (wrong) colors.refused else colors.secondary, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text(typed.getOrNull(index)?.toString() ?: "", color = colors.text, fontSize = 24.sp) }
                }
            }
            if (wrong) Text(stringResource(R.string.child_code_wrong), color = colors.refused, fontSize = 16.sp)

            Keypad(
                onDigit = { digit ->
                    if (typed.length < CODE_LENGTH) {
                        typed += digit
                        wrong = false
                        if (typed.length == CODE_LENGTH) scope.launch {
                            val result = checkCode(typed)
                            if (result == null) { wrong = true; typed = "" } else code = result
                        }
                    }
                },
                onErase = { typed = typed.dropLast(1) }
            )
        } else {
            GrantChoiceContent(app, choice) { grantScope, until ->
                scope.launch { grant(verified, grantScope, until); onBack() }
            }
        }

        TextButton(onClick = onBack) { Text(stringResource(R.string.child_back_to, app.title)) }
        TextButton(onClick = onUpstreamScreen) { Text(stringResource(R.string.child_other_parent_actions), color = colors.secondary) }
    }
}

@Composable
private fun Keypad(onDigit: (Char) -> Unit, onErase: () -> Unit) {
    val keys = listOf("123", "456", "789", "⌫0")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.length == 2) Spacer(Modifier.width(72.dp))
                row.forEach { key ->
                    OutlinedButton(
                        onClick = { if (key == '⌫') onErase() else onDigit(key) },
                        modifier = Modifier.size(72.dp, 56.dp)
                    ) { Text(key.toString(), fontSize = 22.sp) }
                }
            }
        }
    }
}

@Composable
private fun GrantChoiceContent(app: App, choice: GrantChoice, onGrant: (GrantScope, Long) -> Unit) {
    val colors = LocalChildColors.current
    var grantScope by remember { mutableStateOf(GrantScope.App) }
    val now = remember { System.currentTimeMillis() }

    Text(stringResource(R.string.child_code_ok), color = colors.allowed, fontSize = 18.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScopeButton(app.title, grantScope == GrantScope.App, enabled = true) { grantScope = GrantScope.App }
        ScopeButton(
            stringResource(R.string.child_grant_category, choice.categoryTitle ?: stringResource(R.string.child_no_category)),
            grantScope == GrantScope.Category, enabled = choice.categoryTitle != null
        ) { grantScope = GrantScope.Category }
    }
    if (choice.categoryTitle == null) Text(stringResource(R.string.child_grant_category_unavailable), color = colors.secondary, fontSize = 14.sp)
    Text(stringResource(R.string.child_grant_over_modes), color = colors.secondary, fontSize = 14.sp)

    Spacer(Modifier.height(4.dp))
    listOf(
        formatDuration(15 * MINUTE) to now + 15 * MINUTE,
        formatDuration(30 * MINUTE) to now + 30 * MINUTE,
        formatDuration(60 * MINUTE) to now + 60 * MINUTE,
        stringResource(R.string.child_grant_day_end) to choice.dayEnd,
    ).filter { it.second > now }.forEach { (label, until) ->
        Button(onClick = { onGrant(grantScope, until) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("$label — ${stringResource(R.string.child_until, formatClock(until))}", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ScopeButton(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChildColors.current

    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) colors.action else colors.surface,
            contentColor = if (selected) Color.White else colors.text
        )
    ) { Text(text) }
}

@Preview(widthDp = 700, heightDp = 900)
@Composable
private fun ParentNearbyPreview() = ChildTheme {
    ParentNearby(FakeChildApi.minecraft, GrantChoice("Игры", FakeChildApi.NOW + 160 * MINUTE), { null }, { _, _, _ -> }, {}, {})
}
