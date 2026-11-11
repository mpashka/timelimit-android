package io.timelimit.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.App
import io.timelimit.api.AppAccess
import io.timelimit.api.ChildApi
import io.timelimit.ui.R

/**
 * The child's home screen: every launchable app, open ones first, closed ones grey.
 * Tapping a closed app opens its lock screen with «Попросить».
 */
// @tag:child-homescreen
@Composable
fun HomeScreen(api: ChildApi, launch: (App) -> Unit, askFor: (App) -> Unit, openWhatCan: () -> Unit, openParent: () -> Unit) {
    val apps by api.home.collectAsState(initial = emptyList())

    ChildTheme { Home(apps, launch, askFor, openWhatCan, openParent) }
}

@Composable
fun Home(
    apps: List<AppAccess>,
    launch: (App) -> Unit = {},
    askFor: (App) -> Unit = {},
    openWhatCan: () -> Unit = {},
    openParent: () -> Unit = {},
) {
    val colors = LocalChildColors.current
    val sorted = apps.sortedWith(compareBy<AppAccess> { it is AppAccess.Closed }.thenBy { it.app.title.lowercase() })

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize().background(colors.ground).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = openWhatCan) {
                    Text(stringResource(R.string.child_what_can_title), color = colors.text, fontSize = 18.sp)
                }
                TextButton(onClick = openParent) {
                    Text(stringResource(R.string.child_for_parent), color = colors.secondary, fontSize = 16.sp)
                }
            }
        }
        items(sorted, key = { it.app.packageName }) { access ->
            HomeApp(access.app, closed = access is AppAccess.Closed) {
                if (access is AppAccess.Closed) askFor(access.app) else launch(access.app)
            }
        }
    }
}

private val grey = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun HomeApp(app: App, closed: Boolean, onClick: () -> Unit) {
    val colors = LocalChildColors.current

    Column(
        Modifier.clickable(onClick = onClick).alpha(if (closed) 0.45f else 1f).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.graphicsLayer(colorFilter = if (closed) grey else null)) {
            AppIcon(app.packageName, app.title, 64.dp)
        }
        Text(
            app.title, color = colors.text, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Preview(widthDp = 800, heightDp = 1100)
@Composable
private fun HomePreview() = ChildTheme { Home(FakeChildApi.exampleHome) }
