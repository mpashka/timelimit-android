package io.timelimit.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.BottomNavigation
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarResult
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.CannotAct
import io.timelimit.api.ChildHome
import io.timelimit.api.ChildRef
import io.timelimit.api.Undo
import io.timelimit.api.ParentApi
import io.timelimit.api.ParentCodeNow
import io.timelimit.ui.R
import io.timelimit.ui.child.ChildTheme
import io.timelimit.ui.child.LocalChildColors
import kotlinx.coroutines.launch

enum class ParentTab(val title: Int, val icon: ImageVector) {
    Today(R.string.parent_tab_today, Icons.Default.Home),
    Apps(R.string.parent_tab_apps, Icons.Default.List),
    Modes(R.string.parent_tab_modes, Icons.Default.DateRange),
    Sites(R.string.parent_tab_sites, Icons.Default.Lock),
    Tablets(R.string.parent_tab_tablets, Icons.Default.TabletAndroid),
}

/** Performs a command and says its outcome with «Отменить» — reversible actions are not confirmed, they are undone. */
class ParentActions(private val run: (String, suspend () -> Undo?) -> Unit) {
    operator fun invoke(done: String, block: suspend () -> Undo?) = run(done, block)
}

/** The parent's phone (docs/specification/parent-ui.md): header, bottom bar, one column. */
// @tag:new-ui
@Composable
fun ParentScreen(api: ParentApi, startOnRequests: Boolean, openOldInterface: () -> Unit, signIn: () -> Unit) {
    val home by api.home.collectAsState(initial = null)
    val code by api.parentCode.collectAsState(initial = null)
    var tab by rememberSaveable { mutableStateOf(ParentTab.Today) }
    var requestsOpen by rememberSaveable { mutableStateOf(startOnRequests) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.parent_undo)
    val undone = stringResource(R.string.parent_undone)
    val failed = stringResource(R.string.parent_action_failed)
    val actions = remember {
        ParentActions { done, block ->
            scope.launch {
                val undo = try { block() } catch (ex: Exception) { snackbar.showSnackbar(failed.format(ex.message ?: "")); return@launch }
                val result = snackbar.showSnackbar(done, actionLabel = undo?.let { undoLabel }, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed && undo != null) { undo.undo(); snackbar.showSnackbar(undone) }
            }
        }
    }

    ChildTheme {
        val colors = LocalChildColors.current
        val child = home?.child

        // header, content, bottom bar stacked in a column: the scrolled content ends where the bar starts
        Column(Modifier.fillMaxSize().background(colors.ground).safeDrawingPadding()) {
            Header(child, home?.children.orEmpty(), api::selectChild, code, child?.requests?.size ?: 0, onBell = { requestsOpen = !requestsOpen; openApp = null })

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // one column of phone width in the middle of a wide screen, so a name and its time stay together
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        home?.cannotAct?.let { CannotActCard(it, signIn) }

                        when {
                            home == null -> {}
                            child == null -> Text(stringResource(R.string.parent_no_child), color = colors.secondary)
                            requestsOpen -> RequestsScreen(child, api, actions)
                            openApp != null -> AppCard(child, openApp!!, api, actions, onBack = { openApp = null })
                            tab == ParentTab.Today -> {
                                TodayScreen(child, api, actions, onApp = { openApp = it })
                                TextButton(onClick = openOldInterface) { Text(stringResource(R.string.parent_open_old)) }
                            }
                            tab == ParentTab.Apps -> AppsScreen(child, api, actions, onApp = { openApp = it })
                            tab == ParentTab.Tablets -> TabletsScreen(child)
                            tab == ParentTab.Modes -> ModesScreen(child, api, actions)
                            tab == ParentTab.Sites -> SitesScreen(child, api, actions)
                        }
                    }
                }

                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }

            BottomNavigation(backgroundColor = colors.surface, contentColor = colors.action) {
                ParentTab.entries.forEach { item ->
                    BottomNavigationItem(
                        selected = tab == item && !requestsOpen && openApp == null,
                        onClick = { tab = item; requestsOpen = false; openApp = null },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.title), fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(child: ChildHome?, children: List<ChildRef>, select: (String) -> Unit, code: ParentCodeNow?, waiting: Int, onBell: () -> Unit) {
    val colors = LocalChildColors.current
    var showCode by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    TopAppBar(backgroundColor = colors.surface, contentColor = colors.text) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.action), contentAlignment = Alignment.Center) {
                Text(child?.name?.take(1) ?: "", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    (child?.name ?: "") + if (children.size > 1) " ▾" else "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = if (children.size > 1) Modifier.clickable { choosing = true } else Modifier
                )
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    children.forEach { other ->
                        DropdownMenuItem(onClick = { choosing = false; select(other.id) }) { Text(other.name) }
                    }
                }
            }

            if (code != null) {
                if (showCode) Text(
                    code.code.chunked(3).joinToString(" "), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showCode = false }
                ) else TextButton(onClick = { showCode = true }) { Text(stringResource(R.string.parent_code_button)) }
            }

            IconButton(onClick = onBell) {
                Box {
                    Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.parent_requests))
                    if (waiting > 0) Box(
                        Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(colors.refused),
                        contentAlignment = Alignment.Center
                    ) { Text(waiting.toString(), color = Color.White, fontSize = 10.sp) }
                }
            }
        }
    }
}

@Composable
private fun CannotActCard(cannotAct: CannotAct, signIn: () -> Unit) {
    val colors = LocalChildColors.current

    Card {
        Text(
            stringResource(when (cannotAct) {
                CannotAct.NotConnected -> R.string.parent_cannot_not_connected
                CannotAct.NotParent -> R.string.parent_cannot_not_parent
                CannotAct.NotKeptSignedIn -> R.string.parent_cannot_not_kept
            }),
            color = colors.refused, fontSize = 14.sp
        )
        if (cannotAct != CannotAct.NotConnected) Button(onClick = signIn) { Text(stringResource(R.string.parent_sign_in)) }
    }
}
