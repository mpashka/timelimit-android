package io.timelimit.android.child

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.model.UserType
import io.timelimit.android.integration.platform.android.NotificationChannels
import io.timelimit.android.integration.platform.android.NotificationIds
import io.timelimit.android.integration.platform.android.PendingIntentIds
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.ApplyServerDataStatus
import io.timelimit.api.GrantScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.timelimit.ui.R as UiR

/**
 * A request on the parent's phone, answered right from the notification; tap opens the requests screen.
 * ponytail: arrives with a sync, so only while the process lives (websocket or the periodic background
 * sync); a push service would be needed for a phone that killed TimeLimit
 */
// @tag:new-ui @tag:child-request
object ParentRequestNotification {
    private const val EXTRA_REQUEST_ID = "request"
    private const val EXTRA_MINUTES = "minutes"
    private const val DENY = 0

    suspend fun show(logic: AppLogic, new: List<ApplyServerDataStatus.NewRequest>, answered: List<ApplyServerDataStatus.AnsweredRequest>) {
        val context = logic.context
        val manager = context.getSystemService<NotificationManager>() ?: return

        answered.forEach { manager.cancel(it.request.id, NotificationIds.PARENT_REQUEST) }

        if (new.isEmpty() || !UiChoice.isNew(context)) return

        val (isParent, deviceNames) = Threads.database.executeAndWait {
            val device = logic.database.config().getOwnDeviceIdSync()?.let { logic.database.device().getDeviceByIdSync(it) }
            val user = device?.let { logic.database.user().getUserByIdSync(it.currentUserId) }

            (user?.type == UserType.Parent && device.isUserKeptSignedIn) to logic.database.device().getAllDevicesSync().associate { it.id to it.name }
        }

        if (!isParent) return

        NotificationChannels.createNotificationChannels(manager, context)

        new.filter { it.request.expiresAt > System.currentTimeMillis() }.forEach { (request, child) ->
            fun answer(code: Int, minutes: Int, label: Int) = NotificationCompat.Action(
                0, context.getString(label),
                PendingIntent.getBroadcast(
                    context, PendingIntentIds.PARENT_REQUEST_ANSWER + code * 1000 + Math.floorMod(request.id.hashCode(), 1000),
                    Intent(context, AnswerReceiver::class.java).putExtra(EXTRA_REQUEST_ID, request.id).putExtra(EXTRA_MINUTES, minutes),
                    PendingIntentIds.PENDING_INTENT_FLAGS
                )
            )

            val appTitle = Threads.database.executeAndWait {
                logic.database.app().getAppsByPackageNameSync(request.packageName).firstOrNull()?.title
            } ?: child.newApps.find { it.packageName == request.packageName }?.title ?: request.packageName

            manager.notify(
                request.id, NotificationIds.PARENT_REQUEST,
                NotificationCompat.Builder(context, NotificationChannels.TIME_WARNING)
                    .setSmallIcon(R.drawable.ic_stat_timelapse)
                    .setContentTitle(context.getString(UiR.string.parent_request_title, child.name, appTitle))
                    .setContentText(listOfNotNull(
                        request.word.takeIf { it.isNotEmpty() }?.let { "«$it»" },
                        deviceNames[request.deviceId]
                    ).joinToString(" · "))
                    .setWhen(request.createdAt)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setTimeoutAfter(request.expiresAt - System.currentTimeMillis())
                    .setContentIntent(ParentActivity.requestsIntent(context))
                    .addAction(answer(1, 30, UiR.string.parent_allow_30))
                    .addAction(answer(2, 60, UiR.string.parent_allow_60))
                    .addAction(answer(3, DENY, UiR.string.parent_deny))
                    .build()
            )
        }
    }

    class AnswerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val minutes = intent.getIntExtra(EXTRA_MINUTES, DENY)
            val pending = goAsync()

            context.getSystemService<NotificationManager>()?.cancel(requestId, NotificationIds.PARENT_REQUEST)

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val api = ParentApiOverLogic.with(context)

                    if (minutes == DENY) api.deny(requestId)
                    else api.answer(requestId, GrantScope.App, System.currentTimeMillis() + minutes * 60_000L)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
