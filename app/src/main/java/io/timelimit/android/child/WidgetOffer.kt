package io.timelimit.android.child

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val placed = manager.getAppWidgetIds(provider).isNotEmpty()
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
        }
    }
}
