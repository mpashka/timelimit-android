package io.timelimit.android.child

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.timelimit.android.R
import io.timelimit.android.integration.platform.android.NotificationChannels
import io.timelimit.android.integration.platform.android.NotificationIds
import io.timelimit.android.integration.platform.android.PendingIntentIds
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.ui.child.formatClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.timelimit.ui.R as UiR

/** C4: heads-up "осталось 5 мин" over the game, with "Попросить" right in it. */
// @tag:new-ui
object ChildWarning {
    const val DEFAULT_MINUTES = 5
    private const val EXTRA_PACKAGE_NAME = "pkg"

    fun show(context: Context, categoryTitle: String, remainingMillis: Long, packageName: String?) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val minutes = (remainingMillis / 60_000).toInt()
        val closesAt = System.currentTimeMillis() + remainingMillis
        val appTitle = packageName?.let { DefaultAppLogic.with(context).platformIntegration.getLocalAppTitle(it) }

        NotificationChannels.createNotificationChannels(manager, context)
        manager.notify(
            NotificationIds.TIME_WARNING,
            NotificationCompat.Builder(context, NotificationChannels.TIME_WARNING)
                .setSmallIcon(R.drawable.ic_stat_timelapse)
                .setContentTitle(context.getString(UiR.string.child_warning_title, categoryTitle, context.getString(UiR.string.child_minutes, minutes)))
                .setContentText(context.getString(UiR.string.child_warning_text, appTitle ?: categoryTitle, formatClock(closesAt)))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setLocalOnly(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .apply {
                    if (packageName != null) addAction(
                        0, context.getString(UiR.string.child_ask),
                        PendingIntent.getBroadcast(
                            context, PendingIntentIds.CHILD_WARNING_ASK,
                            Intent(context, AskReceiver::class.java).putExtra(EXTRA_PACKAGE_NAME, packageName),
                            PendingIntentIds.PENDING_INTENT_FLAGS
                        )
                    )
                }
                .build()
        )
    }

    // @tag:child-request
    class AskReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
            val pending = goAsync()

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    ChildApiOverLogic.with(context).ask(packageName, "")
                    context.getSystemService<NotificationManager>()?.notify(
                        NotificationIds.TIME_WARNING,
                        NotificationCompat.Builder(context, NotificationChannels.TIME_WARNING)
                            .setSmallIcon(R.drawable.ic_stat_timelapse)
                            .setContentTitle(context.getString(UiR.string.child_request_sent, formatClock(System.currentTimeMillis())))
                            .setLocalOnly(true)
                            .build()
                    )
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
