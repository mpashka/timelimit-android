/*
 * TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.integration.platform.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import io.timelimit.android.R

object NotificationIds {
    const val APP_STATUS = 1
    const val NOTIFICATION_BLOCKED = 2
    const val REVOKE_TEMPORARILY_ALLOWED_APPS = 3
    const val APP_RESET = 4
    const val USER_NOTIFICATION = 5
    const val TIME_WARNING = 6
    const val LOCAL_UPDATE_NOTIFICATION = 7
    const val WORKER_REPORT_UNINSTALL = 8
    const val WORKER_SYNC_BACKGROUND = 9
    const val NEW_DEVICE = 10
    const val EXTRA_TIME_STARTED = 11
}

object NotificationChannels {
    const val APP_STATUS = "app status"
    const val BLOCKED_NOTIFICATIONS_NOTIFICATION = "notification blocked notification"
    const val MANIPULATION_WARNING = "manipulation warning"
    const val UPDATE_NOTIFICATION = "update notification"
    const val TIME_WARNING = "time warning"
    const val PREMIUM_EXPIRES_NOTIFICATION = "premium expires"
    const val BACKGROUND_SYNC_NOTIFICATION = "background sync"
    const val TEMP_ALLOWED_APP = "temporarily allowed App"
    const val APP_RESET = "app reset"
    const val NEW_DEVICE = "new device"
    const val EXTRA_TIME_STARTED = "extra time started"

    private fun createAppStatusChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            APP_STATUS,
                            context.getString(R.string.notification_channel_app_status_title),
                            NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.notification_channel_app_status_description)
                        enableLights(false)
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                    }
            )
        }
    }

    private fun createBlockedNotificationChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            NotificationChannels.BLOCKED_NOTIFICATIONS_NOTIFICATION,
                            context.getString(R.string.notification_channel_blocked_notification_title),
                            NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(R.string.notification_channel_blocked_notification_text)
                    }
            )
        }
    }

    private fun createManipulationNotificationChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            NotificationChannels.MANIPULATION_WARNING,
                            context.getString(R.string.notification_channel_manipulation_title),
                            NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = context.getString(R.string.notification_channel_manipulation_text)
                    }
            )
        }
    }

    private fun createUpdateNotificationChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            NotificationChannels.UPDATE_NOTIFICATION,
                            context.getString(R.string.notification_channel_update_title),
                            NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(R.string.notification_channel_update_text)
                        enableLights(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
    }

    private fun createTimeWarningsNotificationChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            NotificationChannels.TIME_WARNING,
                            context.getString(R.string.notification_channel_time_warning_title),
                            NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = context.getString(R.string.notification_channel_time_warning_text)
                    }
            )
        }
    }

    private fun createPremiumExpiresChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            NotificationChannels.PREMIUM_EXPIRES_NOTIFICATION,
                            context.getString(R.string.notification_channel_premium_expires_title),
                            NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(R.string.notification_channel_premium_expires_text)
                        enableLights(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
            )
        }
    }

    private fun createBackgroundSyncChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    NotificationChannel(
                            BACKGROUND_SYNC_NOTIFICATION,
                            context.getString(R.string.notification_channel_background_sync_title),
                            NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.notification_channel_background_sync_text)
                        enableLights(false)
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                        lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                    }
            )
        }
    }

    private fun createTempAllowedAppChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    TEMP_ALLOWED_APP,
                    context.getString(R.string.notification_channel_apps_temporarily_allowed_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_apps_temporarily_allowed_text)
                    enableLights(false)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun createAppResetChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    APP_RESET,
                    context.getString(R.string.notification_channel_reset_title),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_reset_text)
                }
            )
        }
    }

    private fun createNewDeviceChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NEW_DEVICE,
                    context.getString(R.string.notification_channel_new_device_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_new_device_description)
                    enableLights(false)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun createExtraTimeStartedNotificationChannel(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    EXTRA_TIME_STARTED,
                    context.getString(R.string.notification_channel_extra_time_started_title),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_extra_time_started_description)
                }
            )
        }
    }

    fun createNotificationChannels(notificationManager: NotificationManager, context: Context) {
        createAppStatusChannel(notificationManager, context)
        createBlockedNotificationChannel(notificationManager, context)
        createManipulationNotificationChannel(notificationManager, context)
        createUpdateNotificationChannel(notificationManager, context)
        createTimeWarningsNotificationChannel(notificationManager, context)
        createPremiumExpiresChannel(notificationManager, context)
        createBackgroundSyncChannel(notificationManager, context)
        createTempAllowedAppChannel(notificationManager, context)
        createAppResetChannel(notificationManager, context)
        createNewDeviceChannel(notificationManager, context)
        createExtraTimeStartedNotificationChannel(notificationManager, context)
    }
}

object PendingIntentIds {
    const val OPEN_MAIN_APP = 1
    const val REVOKE_TEMPORARILY_ALLOWED = 2
    const val SWITCH_TO_DEFAULT_USER = 3
    const val SYNC_NOTIFICATIONS = 4
    const val UPDATE_STATUS = 5
    const val OPEN_UPDATER = 6
    const val U2F_NFC_DISCOVERY = 7
    const val U2F_USB_RESPONSE = 8
    const val OPEN_MAIN_APP_WITH_ERROR = 9
    const val WIDGET_CLICK = 10
    const val CHILD_WARNING_ASK = 11
    const val OPEN_WHAT_CAN = 12
    val DYNAMIC_NOTIFICATION_RANGE = 100..10000

    val PENDING_INTENT_FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    val PENDING_INTENT_FLAG_CANCEL_CURRENT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_CANCEL_CURRENT
    }

    val PENDING_INTENT_FLAGS_ALLOW_MUTATION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
}
