package io.timelimit.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.timelimit.api.CategoryToday
import io.timelimit.api.ChildApi
import io.timelimit.api.Today
import io.timelimit.ui.R

/** C2·в of docs/specification/mockups/child.html: what can be used now, opened from the widget. */
// @tag:new-ui
@Composable
fun WhatCanScreen(api: ChildApi) {
    val today by api.today.collectAsState(initial = null)

    ChildTheme { today?.let { WhatCan(it) } }
}

@Composable
fun WhatCan(today: Today) {
    val colors = LocalChildColors.current

    Column(
        Modifier.fillMaxSize().background(colors.ground).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.child_what_can_title), color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        today.nextMode?.let { mode ->
            Text(
                if (mode.name != null) stringResource(R.string.child_mode_next_named, formatClock(mode.from), mode.name!!, formatClock(mode.until))
                else stringResource(R.string.child_mode_next, formatClock(mode.from), formatClock(mode.until)),
                color = colors.secondary, fontSize = 16.sp
            )
        }

        if (today.categories.isEmpty()) Text(stringResource(R.string.child_no_categories), color = colors.secondary, fontSize = 18.sp)

        today.categories.forEach { CategoryCard(it) }

        today.waiting.forEach { waiting ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(waiting.app.packageName, waiting.app.title, 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.child_waiting_request, waiting.app.title, formatClock(waiting.sentAt)),
                    color = colors.text, fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(category: CategoryToday) {
    val colors = LocalChildColors.current
    val accent = when {
        category.closedNow -> colors.closed
        category.remaining != null && category.remaining!! <= 10 * 60_000 -> colors.endingSoon
        else -> colors.allowed
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(category.title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                category.apps.take(8).forEach { AppIcon(it.packageName, it.title, 32.dp) }
            }
        }
        Text(
            when {
                category.closedNow -> stringResource(R.string.child_closed_now)
                category.remaining == null -> stringResource(R.string.child_no_limit)
                else -> formatDuration(category.remaining!!)
            },
            color = accent, fontSize = 30.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Preview(widthDp = 800, heightDp = 1100)
@Composable
private fun WhatCanPreview() = ChildTheme { WhatCan(FakeChildApi.exampleToday) }
