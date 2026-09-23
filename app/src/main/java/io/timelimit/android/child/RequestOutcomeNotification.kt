package io.timelimit.android.child

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.timelimit.android.R
import io.timelimit.android.data.model.ChildRequestAnswer
import io.timelimit.android.integration.platform.android.NotificationChannels
import io.timelimit.android.integration.platform.android.NotificationIds
import io.timelimit.android.integration.platform.android.PendingIntentIds
import io.timelimit.android.sync.ApplyServerDataStatus
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.R as UiR

/** C3·в: the answer to a request of this tablet, wherever the child is now. */
// @tag:new-ui @tag:child-request
object RequestOutcomeNotification {
    fun show(context: Context, answered: List<ApplyServerDataStatus.AnsweredRequest>) {
        if (answered.isEmpty() || !UiChoice.isNew(context)) return

        val manager = context.getSystemService<NotificationManager>() ?: return
        NotificationChannels.createNotificationChannels(manager, context)

        answered.forEach { (request, parentName) ->
            val answer = request.answer ?: return@forEach
            val title = context.packageManager.runCatching {
                getApplicationLabel(getApplicationInfo(request.packageName, 0)).toString()
            }.getOrDefault(request.packageName)
            val allowed = answer.kind != ChildRequestAnswer.KIND_DENY
            val by = parentName.ifEmpty { context.getString(UiR.string.child_parent) }

            val notification = NotificationCompat.Builder(context, NotificationChannels.TIME_WARNING)
                .setSmallIcon(R.drawable.ic_stat_timelapse)
                .setContentTitle(
                    if (allowed) context.getString(UiR.string.child_outcome_allowed, title, formatClock(answer.until))
                    else context.getString(UiR.string.child_outcome_refused, title)
                )
                .setContentText(
                    if (answer.word.isNotEmpty()) "$by: ${answer.word}"
                    else if (allowed) context.getString(UiR.string.child_outcome_allowed_by, by)
                    else context.getString(UiR.string.child_outcome_ask_again, formatClock(answer.repeatAfter))
                )
                .setWhen(answer.at)
                .setShowWhen(true)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .apply {
                    val launch = context.packageManager.getLaunchIntentForPackage(request.packageName)
                    if (allowed && launch != null) {
                        val play = PendingIntent.getActivity(
                            context, PendingIntentIds.REQUEST_OUTCOME_PLAY, launch, PendingIntentIds.PENDING_INTENT_FLAGS
                        )
                        setContentIntent(play)
                        addAction(0, context.getString(UiR.string.child_play), play)
                    }
                }
                .build()

            manager.notify(request.id, NotificationIds.REQUEST_OUTCOME, notification)
        }
    }
}
