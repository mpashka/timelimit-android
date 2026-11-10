package io.timelimit.android.integration.platform.android.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.getSystemService
import io.timelimit.android.logic.DefaultAppLogic

// @tag:bypass-audit
class ProcessWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val INTERVAL_MILLIS = 60 * 1000L

        // Only a device owner may start the foreground BackgroundService from an inexact alarm;
        // elsewhere (parent phones) the alarm would merely revive a process the system reclaimed.
        fun schedule(context: Context) {
            if (!context.getSystemService<DevicePolicyManager>()!!.isDeviceOwnerApp(context.packageName)) return

            // Non-wakeup and inexact: no exact alarm permission needed, and a sleeping tablet is not woken —
            // the alarm fires once the screen is on, which is when limits matter.
            context.getSystemService<AlarmManager>()!!.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MILLIS,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, ProcessWatchdogReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        DefaultAppLogic.with(context)
        schedule(context)
    }
}
