package io.timelimit.android.child

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.timelimit.android.ui.widget.TimesWidgetProvider
import io.timelimit.ui.R as UiR

/** Setup step: put the "Что можно" widget on the launcher by the platform's own request, or say how by hand. */
// @tag:new-ui
@Composable
fun WidgetOffer() {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val provider = remember { ComponentName(context, TimesWidgetProvider::class.java) }
    var requested by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val placed = remember(resumes) { manager.getAppWidgetIds(provider).isNotEmpty() }
    val supported = manager.isRequestPinAppWidgetSupported

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(UiR.string.child_widget_offer_title), style = MaterialTheme.typography.h6)
            Text(
                stringResource(
                    when {
                        placed -> UiR.string.child_widget_offer_placed
                        !supported -> UiR.string.child_widget_offer_by_hand
                        requested -> UiR.string.child_widget_offer_confirm
                        else -> UiR.string.child_widget_offer_text
                    }
                )
            )
            if (supported && !placed) Button(
                onClick = { requested = manager.requestPinAppWidget(provider, null, null) || requested },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(stringResource(UiR.string.child_widget_offer_button)) }
            if (requested && !placed && isXiaomi) {
                Text(stringResource(UiR.string.child_widget_offer_xiaomi), Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { openMiuiPermissions(context) }) {
                    Text(stringResource(UiR.string.child_widget_offer_xiaomi_button))
                }
            }
        }
    }
}

private val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

// MIUI keeps "home screen shortcuts" (MIUIOP 10017) outside Android's permissions; a denied op drops
// requestPinAppWidget silently, and only MIUI's own permission editor can switch it back on.
private fun openMiuiPermissions(context: Context) {
    val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
    val plain = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(if (miui.resolveActivity(context.packageManager) != null) miui else plain)
}
