package io.timelimit.android.logic

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsyncExpectForever
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.DeviceAndUserRelatedData
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.sync.actions.AppLogicAction
import io.timelimit.android.sync.actions.ForgetNewAppAction
import io.timelimit.android.sync.actions.ReportNewAppAction
import io.timelimit.android.sync.actions.SetAppUsageAction
import io.timelimit.android.sync.actions.SetForegroundAppAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate

/**
 * Tells the server once a minute what the child's tablet does: time per app today (SET_APP_USAGE),
 * the app in the foreground (SET_FOREGROUND_APP) and apps closed until a parent decides (REPORT_NEW_APP),
 * docs/specification/protocol-new-ui.md, sections 4–6.
 */
// @tag:app-usage @tag:new-app @tag:device-state
class AppActivityReportLogic(private val appLogic: AppLogic) {
    companion object {
        private const val LOG_TAG = "AppActivityReport"
        private const val PERIOD = 60_000L
        private const val NEW_APPS_EVERY_TICKS = 10
        private const val MIN_SERVER_API_LEVEL = 12
        private const val PREFS = "app_activity_report"
        private const val KEY_NEW_APPS = "new_apps"

        fun section(category: Int): String = when (category) {
            ApplicationInfo.CATEGORY_GAME -> "game"
            ApplicationInfo.CATEGORY_AUDIO -> "audio"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "image"
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            8 -> "accessibility" // ApplicationInfo.CATEGORY_ACCESSIBILITY, API 31
            else -> ""
        }
    }

    @Volatile
    private var foregroundPackage = ""

    @Volatile
    private var ownUnsent: Map<String, Long> = emptyMap()

    private var sentForeground: String? = null
    private var sentUsageDay = -1
    private val sentUsage = mutableMapOf<String, Long>()
    private var ticks = 0

    /** From the blocking loop: the app in the foreground, "" while the screen is off. */
    fun reportForeground(packageName: String) {
        foregroundPackage = packageName
    }

    /** Own time of the app today that the server does not know yet (docs, section 3, the limit formula). */
    // @tag:app-rule
    fun unsentToday(packageName: String): Long = ownUnsent[packageName] ?: 0

    init {
        runAsyncExpectForever {
            while (true) {
                delay(PERIOD)

                try {
                    tick()
                } catch (ex: Exception) {
                    if (BuildConfig.DEBUG) Log.w(LOG_TAG, "report failed", ex)
                }
            }
        }
    }

    private suspend fun dispatch(action: AppLogicAction) {
        ApplyActionUtil.applyAppLogicAction(action = action, appLogic = appLogic, ignoreIfDeviceIsNotConfigured = true)
    }

    private suspend fun tick() {
        val (data, apiLevel) = Threads.database.executeAndWait {
            appLogic.database.derivedDataDao().getUserAndDeviceRelatedDataSync() to appLogic.database.config().getServerApiLevelSync()
        }
        val user = data?.userRelatedData?.takeIf { it.user.type == UserType.Child } ?: return

        if (data.deviceRelatedData.isLocalMode || apiLevel < MIN_SERVER_API_LEVEL) return

        reportUsage(user.timeZone.toZoneId())
        reportForegroundApp()
        if (ticks++ % NEW_APPS_EVERY_TICKS == 0) reportNewApps(data)
    }

    private suspend fun reportUsage(zone: java.time.ZoneId) {
        val now = RealTime.newInstance().also { appLogic.realTimeLogic.getRealTime(it) }.timeInMillis
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        if (sentUsageDay != -1 && sentUsageDay.toLong() != today.toEpochDay()) {
            val day = LocalDate.ofEpochDay(sentUsageDay.toLong())
            sendChanged(day, usage(day, zone, now))
            sentUsage.clear()
        }

        sentUsageDay = today.toEpochDay().toInt()

        val own = usage(today, zone, now)
        // ponytail: the server is taken to know everything up to the previous report; the own limit of
        // an app is off by a report still on its way, move to acknowledged reports if a minute matters
        val previous = sentUsage.toMap()
        sendChanged(today, own)
        ownUnsent = own.mapValues { (packageName, ms) -> (ms - (previous[packageName] ?: 0)).coerceAtLeast(0) }
    }

    private suspend fun usage(day: LocalDate, zone: java.time.ZoneId, now: Long): Map<String, Long> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(now)

        return Threads.backgroundOSInteraction.executeAndWait { ForegroundTimeAggregation.foregroundTime(appLogic.context, start, end) }
    }

    private suspend fun sendChanged(day: LocalDate, own: Map<String, Long>) {
        val changed = own.filter { (packageName, ms) -> sentUsage[packageName] != ms }
            .mapValues { it.value.coerceAtMost(24 * 60 * 60 * 1000L) }

        changed.entries.chunked(SetAppUsageAction.MAX_ITEMS).forEach { chunk ->
            dispatch(SetAppUsageAction(day.toEpochDay().toInt(), chunk.associate { it.key to it.value }))
        }

        sentUsage.putAll(changed)
    }

    private suspend fun reportForegroundApp() {
        val current = foregroundPackage

        if (current != sentForeground) {
            dispatch(SetForegroundAppAction(current))
            sentForeground = current
        }
    }

    private suspend fun reportNewApps(data: DeviceAndUserRelatedData) {
        val context = appLogic.context
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val reported = prefs.getStringSet(KEY_NEW_APPS, emptySet())!!.toSet()
        val user = data.userRelatedData ?: return
        val pm = context.packageManager

        val launchable = Threads.backgroundOSInteraction.executeAndWait {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }
        val newApps = launchable.filter { packageName ->
            packageName != context.packageName && AppBaseHandling.calculate(
                foregroundAppPackageName = packageName,
                foregroundAppActivityName = null,
                pauseForegroundAppBackgroundLoop = false,
                pauseCounting = false,
                userRelatedData = user,
                deviceRelatedData = data.deviceRelatedData,
                isSystemImageApp = appLogic.platformIntegration.isSystemImageApp(packageName)
            ) is AppBaseHandling.BlockDueToNoCategory
        }.toSet()

        (newApps - reported).forEach { packageName ->
            val info = pm.getPackageInfo(packageName, 0)
            val applicationInfo = info.applicationInfo ?: return@forEach

            dispatch(ReportNewAppAction(
                packageName = packageName,
                title = pm.getApplicationLabel(applicationInfo).toString(),
                section = section(applicationInfo.category),
                installedAt = info.firstInstallTime
            ))
        }

        (reported - launchable).forEach { dispatch(ForgetNewAppAction(it)) }

        prefs.edit().putStringSet(KEY_NEW_APPS, newApps).apply()
    }
}
