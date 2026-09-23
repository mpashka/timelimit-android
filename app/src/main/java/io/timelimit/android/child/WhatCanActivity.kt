package io.timelimit.android.child

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.timelimit.android.integration.platform.android.PendingIntentIds
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.ui.child.WhatCanScreen

// @tag:new-ui
class WhatCanActivity : ComponentActivity() {
    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, PendingIntentIds.OPEN_WHAT_CAN,
            Intent(context, WhatCanActivity::class.java), PendingIntentIds.PENDING_INTENT_FLAGS
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WhatCanScreen(ChildApiOverLogic.with(this)) }
    }

    override fun onStart() {
        super.onStart()
        IsAppInForeground.reportStart()
    }

    override fun onStop() {
        super.onStop()
        IsAppInForeground.reportStop()
    }
}
