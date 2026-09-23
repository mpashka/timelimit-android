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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.BottomNavigation
import androidx.compose.material.BottomNavigationItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
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
import androidx.compose.material.icons.filled.Phone
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
import io.timelimit.api.ChildHome
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
    Tablets(R.string.parent_tab_tablets, Icons.Default.Phone),
}

/** Performs a command and says its outcome in the snackbar — reversible actions are not confirmed. */
class ParentActions(private val run: (String, suspend () -> Unit) -> Unit) {
    operator fun invoke(done: String, block: suspend () -> Unit) = run(done, block)
}

/** The parent's phone (docs/specification/parent-ui.md): header, bottom bar, one column. */
// @tag:new-ui
@Composable
fun ParentScreen(api: ParentApi, startOnRequests: Boolean, openOldInterface: () -> Unit) {
    val home by api.home.collectAsState(initial = null)
    val code by api.parentCode.collectAsState(initial = null)
    var tab by rememberSaveable { mutableStateOf(ParentTab.Today) }
    var requestsOpen by rememberSaveable { mutableStateOf(startOnRequests) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val actions = remember {
        ParentActions { done, block -> scope.launch { block(); snackbar.showSnackbar(done) } }
    }

    ChildTheme {
        val colors = LocalChildColors.current
        val child = home?.child

        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            scaffoldState = androidx.compose.material.rememberScaffoldState(snackbarHostState = snackbar),
            topBar = { Header(child, code, child?.requests?.size ?: 0, onBell = { requestsOpen = !requestsOpen; openApp = null }) },
            bottomBar = {
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
            },
            backgroundColor = colors.ground,
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                home?.cannotAct?.let { Text(it, color = colors.refused, fontSize = 14.sp) }

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
                    else -> OldInterfaceHint(openOldInterface)
                }
            }
        }
    }
}

@Composable
private fun Header(child: ChildHome?, code: ParentCodeNow?, waiting: Int, onBell: () -> Unit) {
    val colors = LocalChildColors.current
    var showCode by remember { mutableStateOf(false) }

    TopAppBar(backgroundColor = colors.surface, contentColor = colors.text) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.action), contentAlignment = Alignment.Center) {
                Text(child?.name?.take(1) ?: "", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(child?.name ?: "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))

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
private fun OldInterfaceHint(openOldInterface: () -> Unit) {
    Text(stringResource(R.string.parent_not_yet), color = LocalChildColors.current.secondary)
    TextButton(onClick = openOldInterface) { Text(stringResource(R.string.parent_open_old)) }
}
