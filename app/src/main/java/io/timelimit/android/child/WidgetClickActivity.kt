package io.timelimit.android.child

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.timelimit.android.integration.platform.android.BackgroundActionService
import io.timelimit.android.integration.platform.android.PendingIntentIds
import io.timelimit.android.ui.MainActivity

/** Target of every tap on the times widget: one list item template can point at one component only. */
// @tag:new-ui
class WidgetClickActivity : Activity() {
    companion object {
        private const val EXTRA_SWITCH_TO_DEFAULT_USER = "switchToDefaultUser"

        fun template(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            PendingIntentIds.WIDGET_CLICK,
            Intent(context, WidgetClickActivity::class.java),
            PendingIntentIds.PENDING_INTENT_FLAGS_ALLOW_MUTATION
        )

        fun switchToDefaultUser() = Intent().putExtra(EXTRA_SWITCH_TO_DEFAULT_USER, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when {
            intent.getBooleanExtra(EXTRA_SWITCH_TO_DEFAULT_USER, false) ->
                BackgroundActionService.getSwitchToDefaultUserIntent(this).send()
            UiChoice.isNew(this) -> startActivity(Intent(this, WhatCanActivity::class.java))
            else -> startActivity(Intent(this, MainActivity::class.java))
        }

        finish()
    }
}
