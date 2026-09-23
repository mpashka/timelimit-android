package io.timelimit.ui.child

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.AppAccess
import io.timelimit.api.ChildApi
import io.timelimit.api.CloseReason
import io.timelimit.api.Request
import io.timelimit.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * C1 and C3 of docs/specification/mockups/child.html: why the app is closed, until when, and the request.
 * [onClosedNever] fires when the app turns out to be open before the screen ever showed it closed.
 */
// @tag:new-ui
@Composable
fun AppAccessScreen(
    api: ChildApi,
    packageName: String,
    onParentNearby: () -> Unit,
    onUseThisDevice: () -> Unit,
    onClosedNever: () -> Unit,
) {
    val access by remember(api, packageName) { api.appAccess(packageName) }.collectAsState(initial = null)
    var wasClosed by remember { mutableStateOf(false) }
    var parentNearby by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(access) {
        when (access) {
            is AppAccess.Closed -> wasClosed = true
            is AppAccess.Open -> if (!wasClosed) onClosedNever()
            null -> {}
        }
    }

    ChildTheme {
        when (val current = access) {
            is AppAccess.Closed -> if (parentNearby && current.grant != null) Column(
                Modifier.fillMaxSize().background(LocalChildColors.current.ground).safeDrawingPadding()
                    .verticalScroll(rememberScrollState()).padding(24.dp)
            ) {
                ParentNearby(
                    app = current.app,
                    choice = current.grant!!,
                    checkCode = api::checkParentCode,
                    grant = { code, grantScope, until -> api.grant(packageName, code, grantScope, until) },
                    onBack = { parentNearby = false },
                    onUpstreamScreen = onParentNearby,
                )
            } else ClosedApp(
                current,
                onAsk = { word -> scope.launch { api.ask(packageName, word) } },
                onParentNearby = { if (current.grant != null) parentNearby = true else onParentNearby() },
                onUseThisDevice = onUseThisDevice,
            )
            is AppAccess.Open -> if (wasClosed) OpenApp(current)
            null -> {}
        }
    }
}

@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun TwoPanes(accent: Color, left: @Composable () -> Unit, right: @Composable () -> Unit) {
    val colors = LocalChildColors.current

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.ground).safeDrawingPadding()) {
        val wide = maxWidth > 700.dp
        val pane = Modifier.padding(24.dp)

        if (wide) Row(Modifier.fillMaxSize()) {
            Column(pane.weight(1.3f).verticalScroll(rememberScrollState())) { left() }
            Column(pane.weight(1f).background(colors.surface).padding(24.dp).verticalScroll(rememberScrollState())) { right() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(pane) { left() }
            Column(Modifier.fillMaxWidth().background(colors.surface).padding(24.dp)) { right() }
        }

        Spacer(Modifier.fillMaxWidth().height(6.dp).background(accent))
    }
}

@Composable
private fun AppHeader(title: String, packageName: String, subtitle: String?) {
    val colors = LocalChildColors.current

    Row {
        AppIcon(packageName, title)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = colors.secondary, fontSize = 15.sp)
        }
    }
}

@Composable
private fun BigTime(label: String, time: String, color: Color) {
    Spacer(Modifier.height(24.dp))
    Text(label, color = LocalChildColors.current.secondary, fontSize = 18.sp)
    Text(time, color = color, fontSize = 72.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun reasonText(reason: CloseReason, category: String): String = when (reason) {
    CloseReason.LimitOver -> stringResource(R.string.child_reason_limit_over, category)
    is CloseReason.ExtraTimeLater -> stringResource(R.string.child_reason_extra_time_later, formatDuration(reason.extraTime))
    is CloseReason.Mode -> reason.name?.let { stringResource(R.string.child_reason_mode_named, it) }
        ?: stringResource(R.string.child_reason_mode)
    CloseReason.ClosedByParent -> stringResource(R.string.child_reason_closed_by_parent, category)
    is CloseReason.Break -> stringResource(
        R.string.child_reason_break, formatDuration(reason.playLength), formatDuration(reason.breakLength)
    )
    CloseReason.NewApp -> stringResource(R.string.child_reason_new_app)
    CloseReason.LowBattery -> stringResource(R.string.child_reason_low_battery)
    CloseReason.WifiRequired -> stringResource(R.string.child_reason_wifi_required, category)
    CloseReason.ForbiddenNetwork -> stringResource(R.string.child_reason_forbidden_network, category)
    CloseReason.NoExactTime -> stringResource(R.string.child_reason_no_exact_time)
    CloseReason.NoNetworkPermission -> stringResource(R.string.child_reason_no_network_permission)
    CloseReason.OtherDevice -> stringResource(R.string.child_reason_other_device, category)
}

private val CloseReason.canAsk
    get() = when (this) {
        CloseReason.LimitOver, is CloseReason.ExtraTimeLater, is CloseReason.Mode,
        CloseReason.ClosedByParent, CloseReason.NewApp -> true
        else -> false
    }

private val CloseReason.needsWifi
    get() = this == CloseReason.WifiRequired || this == CloseReason.ForbiddenNetwork || this == CloseReason.NoExactTime

@Composable
fun ClosedApp(
    access: AppAccess.Closed,
    onAsk: (String) -> Unit,
    onParentNearby: () -> Unit,
    onUseThisDevice: () -> Unit,
) {
    val colors = LocalChildColors.current
    val context = LocalContext.current
    val now = rememberNow()
    val reason = access.reason
    val category = access.categoryTitle ?: stringResource(R.string.child_category_fallback)

    TwoPanes(
        accent = colors.closed,
        left = {
            AppHeader(
                access.app.title, access.app.packageName,
                access.categoryTitle?.let { stringResource(R.string.child_category_line, it) }
            )
            Spacer(Modifier.height(24.dp))
            Text(reasonText(reason, category), color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

            when {
                reason == CloseReason.NoExactTime || reason == CloseReason.NoNetworkPermission -> {
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.child_call_parent), color = colors.closed, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    if (reason == CloseReason.NoExactTime) Text(stringResource(R.string.child_call_parent_or_wifi), color = colors.secondary, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.child_tell_parent,
                            stringResource(
                                if (reason == CloseReason.NoExactTime) R.string.child_tell_parent_no_time
                                else R.string.child_tell_parent_permission
                            )
                        ),
                        color = colors.text, fontSize = 18.sp
                    )
                }
                access.opensAt != null -> BigTime(
                    stringResource(R.string.child_opens_at, formatDay(access.opensAt!!, now)),
                    formatClock(access.opensAt!!), colors.closed
                )
                reason == CloseReason.ClosedByParent -> {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.child_opens_unknown_parent), color = colors.closed, fontSize = 22.sp)
                }
            }

            access.remainingToday?.let { remaining ->
                Spacer(Modifier.height(12.dp))
                Text(
                    if (reason is CloseReason.Break) stringResource(R.string.child_after_break_remaining, formatDuration(remaining))
                    else stringResource(R.string.child_remaining_today, formatDuration(remaining)),
                    color = colors.secondary, fontSize = 16.sp
                )
            }
        },
        right = {
            if (reason.canAsk) RequestPane(access.request, now, onAsk)

            if (reason.needsWifi) Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(stringResource(R.string.child_connect_wifi), fontSize = 18.sp) }

            if (reason == CloseReason.OtherDevice) Button(
                onClick = onUseThisDevice,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(stringResource(R.string.child_use_this_device), fontSize = 18.sp) }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onParentNearby, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.child_parent_nearby))
            }
        }
    )
}

// @tag:child-request
@Composable
private fun RequestPane(request: Request, now: Long, onAsk: (String) -> Unit) {
    val colors = LocalChildColors.current
    var word by rememberSaveable(request is Request.Expired) {
        mutableStateOf((request as? Request.Expired)?.word ?: "")
    }

    @Composable
    fun wordAndButton(label: Int) {
        OutlinedTextField(
            value = word,
            onValueChange = { word = it.take(100) },
            label = { Text(stringResource(R.string.child_word_label)) },
            placeholder = { Text(stringResource(R.string.child_word_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onAsk(word.trim()) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(label), fontSize = 18.sp)
        }
    }

    @Composable
    fun disabledButton(text: String) {
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(disabledBackgroundColor = colors.ground)
        ) { Text(text, fontSize = 18.sp) }
    }

    @Composable
    fun quoted(text: String) {
        if (text.isNotEmpty()) Text("«$text»", color = colors.secondary, fontSize = 16.sp)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (request) {
            Request.None -> wordAndButton(R.string.child_ask)
            is Request.Sending -> {
                Text(stringResource(R.string.child_request_sending), color = colors.text, fontSize = 18.sp)
                quoted(request.word)
                disabledButton(stringResource(R.string.child_ask_waiting))
            }
            is Request.Sent -> {
                Text(stringResource(R.string.child_request_sent, formatClock(request.at)), color = colors.text, fontSize = 18.sp)
                quoted(request.word)
                Text(stringResource(R.string.child_request_sent_hint), color = colors.secondary, fontSize = 14.sp)
                disabledButton(stringResource(R.string.child_ask_waiting))
            }
            is Request.Refused -> {
                Text(stringResource(R.string.child_request_refused, formatClock(request.at)), color = colors.refused, fontSize = 18.sp)
                request.parentWord?.let { Text("${request.parentName}: $it", color = colors.text, fontSize = 16.sp) }
                if (now < request.askAgainAt) disabledButton(stringResource(R.string.child_ask_again_from, formatClock(request.askAgainAt)))
                else wordAndButton(R.string.child_ask_again)
            }
            is Request.Expired -> {
                Text(stringResource(R.string.child_request_expired), color = colors.text, fontSize = 18.sp)
                Text(stringResource(R.string.child_request_expired_hint), color = colors.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                wordAndButton(R.string.child_ask_again)
            }
        }
    }
}

@Composable
fun OpenApp(access: AppAccess.Open) {
    val colors = LocalChildColors.current
    val context = LocalContext.current

    TwoPanes(
        accent = colors.allowed,
        left = {
            AppHeader(access.app.title, access.app.packageName, null)
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.child_open_title, access.app.title),
                color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold
            )
            access.allowedUntil?.let { BigTime(stringResource(R.string.child_allowed_until), formatClock(it), colors.allowed) }
            access.allowedBy?.let { Text(stringResource(R.string.child_allowed_by, it), color = colors.secondary, fontSize = 16.sp) }
        },
        right = {
            Button(
                onClick = {
                    context.packageManager.getLaunchIntentForPackage(access.app.packageName)
                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = colors.allowed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(stringResource(R.string.child_play), fontSize = 18.sp) }
        }
    )
}

@Preview(widthDp = 1100, heightDp = 700)
@Composable
private fun LimitOverPreview() = ChildTheme {
    ClosedApp(FakeChildApi.closed(CloseReason.LimitOver, Request.None), {}, {}, {})
}

@Preview(widthDp = 1100, heightDp = 700)
@Composable
private fun SentPreview() = ChildTheme {
    ClosedApp(FakeChildApi.closed(CloseReason.LimitOver, Request.Sent(FakeChildApi.NOW, "Дострою дом и всё, честно")), {}, {}, {})
}

@Preview(widthDp = 600, heightDp = 900)
@Composable
private fun NoExactTimePreview() = ChildTheme {
    ClosedApp(FakeChildApi.closed(CloseReason.NoExactTime, Request.None), {}, {}, {})
}

@Preview(widthDp = 1100, heightDp = 700)
@Composable
private fun OpenPreview() = ChildTheme {
    OpenApp(AppAccess.Open(FakeChildApi.minecraft, FakeChildApi.NOW + 15 * 60_000, "Папа"))
}
