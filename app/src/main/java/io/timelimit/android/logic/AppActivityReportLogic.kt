package io.timelimit.android.logic

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsyncExpectForever
import io.timelimit.android.data.model.UserType
import io.timelimit.android.data.model.derived.DeviceAndUserRelatedData
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.sync.actions.AppLogicAction
import io.timelimit.android.sync.actions.ForgetNewAppAction
import io.timelimit.android.sync.actions.ReportAppIconsAction
import io.timelimit.android.sync.actions.ReportNewAppAction
import io.timelimit.android.sync.actions.SetAppUsageAction
import io.timelimit.android.sync.actions.SetForegroundAppAction
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

/**
 * Tells the server once a minute what the child's tablet does: time per app today (SET_APP_USAGE),
 * the app in the foreground (SET_FOREGROUND_APP), apps closed until a parent decides (REPORT_NEW_APP)
 * and, once per app version, its icon and name (REPORT_APP_ICONS), docs/specification/protocol-new-ui.md, sections 4–6, 9.
 */
// @tag:app-usage @tag:new-app @tag:device-state @tag:app-icon
class AppActivityReportLogic(private val appLogic: AppLogic) {
    companion object {
        private const val LOG_TAG = "AppActivityReport"
        private const val PERIOD = 60_000L
        private const val NEW_APPS_EVERY_TICKS = 10
        private const val MIN_SERVER_API_LEVEL = 12
        private const val PREFS = "app_activity_report"
        private const val KEY_NEW_APPS = "new_apps"
        // renamed so every tablet sends its icons once more: since apiLevel 16 the server records which tablet
        // sent each one, and that is how the console tells home-screen apps from service ones
        private const val KEY_ICONS_SENT = "icons_sent_by_tablet"
        private const val ICON_SIZE = 96
        private const val MAX_ICON_BASE64 = 65536

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

        /**
         * Launchable apps whose current version has no icon sent yet; [sent] holds "packageName:versionCode",
         * a bare package name left by an older build never matches, so such an app is sent once more.
         */
        // @tag:app-icon
        fun iconsToSend(current: Map<String, Long>, sent: Set<String>): List<String> =
            current.filter { (packageName, versionCode) -> sentIconKey(packageName, versionCode) !in sent }.keys.toList()

        fun sentIconKey(packageName: String, versionCode: Long) = "$packageName:$versionCode"
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
        if (apiLevel >= ReportAppIconsAction.MIN_SERVER_API_LEVEL) reportAppIcons(apiLevel >= ReportAppIconsAction.MIN_SERVER_API_LEVEL_VERSION_CODE)
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

        val launchable = launchablePackages()
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

    private suspend fun launchablePackages(): Set<String> = Threads.backgroundOSInteraction.executeAndWait {
        appLogic.context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    // ponytail: "sent" lives on the tablet, so a server that loses its icons does not get them again;
    // add a list of known icons to the sync if a server is ever replaced without its database
    private suspend fun reportAppIcons(withVersionCode: Boolean) {
        val context = appLogic.context
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(KEY_ICONS_SENT, emptySet())!!.toSet()
        val current = launchableVersions() - context.packageName
        val batch = iconsToSend(current, sent).take(ReportAppIconsAction.MAX_ITEMS)

        if (batch.isNotEmpty()) {
            val items = Threads.backgroundOSInteraction.executeAndWait {
                batch.mapNotNull { iconItem(context.packageManager, it, current.getValue(it).takeIf { withVersionCode }) }
            }

            if (items.isNotEmpty()) dispatch(ReportAppIconsAction(items))
        }

        val newSent = current.filter { (packageName, versionCode) -> packageName in batch || sentIconKey(packageName, versionCode) in sent }
            .map { (packageName, versionCode) -> sentIconKey(packageName, versionCode) }.toSet()

        if (newSent != sent) prefs.edit().putStringSet(KEY_ICONS_SENT, newSent).apply()
    }

    private suspend fun launchableVersions(): Map<String, Long> {
        val launchable = launchablePackages()

        return Threads.backgroundOSInteraction.executeAndWait {
            appLogic.context.packageManager.getInstalledPackages(0)
                .filter { it.packageName in launchable }
                .associate { it.packageName to PackageInfoCompat.getLongVersionCode(it) }
        }
    }

    private fun iconItem(pm: PackageManager, packageName: String, versionCode: Long?): ReportAppIconsAction.Item? = try {
        val applicationInfo = pm.getApplicationInfo(packageName, 0)
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)

        pm.getApplicationIcon(applicationInfo).apply { setBounds(0, 0, ICON_SIZE, ICON_SIZE) }.draw(Canvas(bitmap))

        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val base64 = Base64.encodeToString(png, Base64.NO_WRAP)

        bitmap.recycle()

        if (base64.length > MAX_ICON_BASE64) null
        else ReportAppIconsAction.Item(packageName, pm.getApplicationLabel(applicationInfo).toString(), base64, versionCode)
    } catch (ex: PackageManager.NameNotFoundException) {
        null
    }
}
