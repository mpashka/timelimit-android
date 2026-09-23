package io.timelimit.android.child

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.timelimit.android.integration.platform.android.PendingIntentIds
import io.timelimit.android.ui.IsAppInForeground
import io.timelimit.android.ui.MainActivity
import io.timelimit.ui.parent.ParentScreen

// @tag:new-ui
class ParentActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_REQUESTS = "requests"

        fun requestsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, PendingIntentIds.OPEN_PARENT_REQUESTS,
            Intent(context, ParentActivity::class.java).putExtra(EXTRA_REQUESTS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntentIds.PENDING_INTENT_FLAGS
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ParentScreen(
                api = ParentApiOverLogic.with(this),
                startOnRequests = intent.getBooleanExtra(EXTRA_REQUESTS, false),
                openOldInterface = { startActivity(UiChoice.oldMainIntent(this)); finish() },
            )
        }
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
