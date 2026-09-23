/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
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
package io.timelimit.android.sync.actions.dispatch

import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.*
import io.timelimit.android.extensions.MinuteOfDay
import io.timelimit.android.integration.platform.NewPermissionStatusUtil
import io.timelimit.android.integration.platform.ProtectionLevelUtil
import io.timelimit.android.integration.platform.RuntimePermissionStatusUtil
import io.timelimit.android.logic.BackgroundTaskLogic
import io.timelimit.android.sync.actions.*

object LocalDatabaseAppLogicActionDispatcher {
    private const val LOG_TAG = "AppLogicAction"

    fun dispatchAppLogicActionSync(action: AppLogicAction, deviceId: String, database: Database) {
        DatabaseValidation.assertDeviceExists(database, deviceId)

        database.runInTransaction {
            when(action) {
                is AddUsedTimeActionVersion2 -> {
                    action.items.forEach { item ->
                        database.category().getCategoryByIdSync(item.categoryId)
                                ?: throw CategoryNotFoundException()

                        fun handle(start: Int, end: Int) {
                            val lengthInMinutes = (end - start) + 1
                            val lengthInMs = lengthInMinutes * 1000 * 60

                            val updatedRows = database.usedTimes().addUsedTime(
                                    categoryId = item.categoryId,
                                    timeToAdd = item.timeToAdd,
                                    dayOfEpoch = action.dayOfEpoch,
                                    start = start,
                                    end = end,
                                    maximum = lengthInMs
                            )

                            if (updatedRows == 0) {
                                // create new entry

                                val oldTime = database.usedTimes().getUsedTimeItemsSyncIncludingSmaller(
                                    item.categoryId, action.dayOfEpoch, start, end
                                ).map { it.usedMillis }.maxOrNull() ?: 0

                                database.usedTimes().insertUsedTime(UsedTimeItem(
                                        categoryId = item.categoryId,
                                        dayOfEpoch = action.dayOfEpoch,
                                        usedMillis = (oldTime + item.timeToAdd).coerceAtMost(lengthInMs.toLong()),
                                        startTimeOfDay = start,
                                        endTimeOfDay = end
                                ))
                            }
                        }

                        handle(MinuteOfDay.MIN, MinuteOfDay.MAX)
                        item.additionalCountingSlots.forEach { handle(it.start, it.end) }

                        kotlin.run {
                            val hasTrustedTimestamp = action.trustedTimestamp != 0L

                            item.sessionDurationLimits.forEach { limit ->
                                val oldItem = database.sessionDuration().getSessionDurationItemSync(
                                        categoryId = item.categoryId,
                                        maxSessionDuration = limit.maxSessionDuration,
                                        sessionPauseDuration = limit.sessionPauseDuration,
                                        startMinuteOfDay = limit.startMinuteOfDay,
                                        endMinuteOfDay = limit.endMinuteOfDay
                                )

                                fun oldDuration(): Long {
                                    val fittingDurationItems = database.sessionDuration().getFittingSessionDurationItemsSync(
                                        categoryId = item.categoryId,
                                        startMinuteOfDay = limit.startMinuteOfDay,
                                        endMinuteOfDay = limit.endMinuteOfDay,
                                        maxSessionDuration = limit.maxSessionDuration,
                                        sessionPauseDuration = limit.sessionPauseDuration
                                        // this ignores the last usage that is checked later
                                    )

                                    val fittingDurationItemsLastUsageFiltered =
                                        if (hasTrustedTimestamp) fittingDurationItems.filter {
                                            action.trustedTimestamp - item.timeToAdd <= it.lastUsage + it.sessionPauseDuration - BackgroundTaskLogic.EXTEND_SESSION_TOLERANCE
                                        } else fittingDurationItems

                                    return fittingDurationItemsLastUsageFiltered.map { it.lastSessionDuration }.maxOrNull() ?: 0
                                }

                                if (BuildConfig.DEBUG) {
                                    Log.d(LOG_TAG, "handle session duration limit $limit")
                                    Log.d(LOG_TAG, "timestamp: ${action.trustedTimestamp}")
                                    Log.d(LOG_TAG, "time to add: ${item.timeToAdd}")
                                    Log.d(LOG_TAG, "oldItem: $oldItem")
                                }

                                val newItem = if (oldItem != null) {
                                    val extendSession = if (!hasTrustedTimestamp)
                                        true
                                    else {
                                        /*
                                         * Why the tolerance?
                                         *
                                         * The main loop is executed in some interval and it assumes
                                         * at the end of the interval that the same application was used during
                                         * the previous phase.
                                         *
                                         * Now, if the session duration limit ends during this phase and the application is
                                         * launched again, then it extends the session (because it is assumed to be running
                                         * before the session ended) and blocks again.
                                         *
                                         * Due to this, a session is reset if it would be over in a few seconds, too.
                                         */
                                        val tolerance = BackgroundTaskLogic.EXTEND_SESSION_TOLERANCE
                                        val timeWhenStartingCurrentUsage = action.trustedTimestamp - item.timeToAdd
                                        val nextSessionStart = oldItem.lastUsage + oldItem.sessionPauseDuration - tolerance

                                        Log.d(LOG_TAG, "timeWhenStartingCurrentUsage = $timeWhenStartingCurrentUsage")
                                        Log.d(LOG_TAG, "nextSessionStart = $nextSessionStart")
                                        Log.d(LOG_TAG, "timeWhenStartingCurrentUsage - nextSessionStart = ${timeWhenStartingCurrentUsage - nextSessionStart}")

                                        timeWhenStartingCurrentUsage <= nextSessionStart
                                    }

                                    oldItem.copy(
                                            lastUsage = action.trustedTimestamp.coerceAtLeast(oldItem.lastUsage),
                                            lastSessionDuration = if (extendSession) oldItem.lastSessionDuration + item.timeToAdd.toLong() else oldDuration() + item.timeToAdd.toLong()
                                    )
                                } else {
                                    SessionDuration(
                                        categoryId = item.categoryId,
                                        maxSessionDuration = limit.maxSessionDuration,
                                        sessionPauseDuration = limit.sessionPauseDuration,
                                        startMinuteOfDay = limit.startMinuteOfDay,
                                        endMinuteOfDay = limit.endMinuteOfDay,
                                        lastSessionDuration = oldDuration() + item.timeToAdd.toLong(),
                                        lastUsage = action.trustedTimestamp // can be zero
                                    )
                                }

                                if (BuildConfig.DEBUG) {
                                    Log.d(LOG_TAG, "newItem: $newItem")
                                }

                                if (oldItem == null) {
                                    database.sessionDuration().insertSessionDurationItemSync(newItem)
                                } else {
                                    database.sessionDuration().updateSessionDurationItemSync(newItem)
                                }
                            }
                        }

                        if (item.extraTimeToSubtract != 0) {
                            database.category().subtractCategoryExtraTime(
                                    categoryId = item.categoryId,
                                    removedExtraTime = item.extraTimeToSubtract
                            )
                        }
                    }

                    null
                }
                is AddInstalledAppsAction -> {
                    database.app().addAppsSync(
                            action.apps.map {
                                App(
                                        deviceId = deviceId,
                                        packageName = it.packageName,
                                        title = it.title,
                                        isLaunchable = it.isLaunchable,
                                        recommendation = it.recommendation
                                )
                            }
                    )
                }
                is RemoveInstalledAppsAction -> {
                    database.app().removeAppsByDeviceIdAndPackageNamesSync(
                            deviceId = deviceId,
                            packageNames = action.packageNames
                    )
                }
                is UpdateDeviceStatusAction -> {
                    var device = database.device().getDeviceByIdSync(deviceId)!!

                    if (action.newProtectionLevel != null) {
                        if (device.currentProtectionLevel != action.newProtectionLevel) {
                            device = device.copy(
                                    currentProtectionLevel = action.newProtectionLevel
                            )

                            if (ProtectionLevelUtil.toInt(action.newProtectionLevel) > ProtectionLevelUtil.toInt(device.highestProtectionLevel)) {
                                device = device.copy(
                                        highestProtectionLevel = action.newProtectionLevel
                                )
                            }

                            if (device.currentProtectionLevel != device.highestProtectionLevel) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.PROTECTION_LEVEL
                                )
                            }
                        }
                    }

                    if (action.newUsageStatsPermissionStatus != null) {
                        if (device.currentUsageStatsPermission != action.newUsageStatsPermissionStatus) {
                            device = device.copy(
                                    currentUsageStatsPermission = action.newUsageStatsPermissionStatus
                            )

                            if (RuntimePermissionStatusUtil.toInt(action.newUsageStatsPermissionStatus) > RuntimePermissionStatusUtil.toInt(device.highestUsageStatsPermission)) {
                                device = device.copy(
                                        highestUsageStatsPermission = action.newUsageStatsPermissionStatus
                                )
                            }

                            if (device.currentUsageStatsPermission != device.highestUsageStatsPermission) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.USAGE_STATS_ACCESS
                                )
                            }
                        }
                    }

                    if (action.newNotificationAccessPermission != null) {
                        if (device.currentNotificationAccessPermission != action.newNotificationAccessPermission) {
                            device = device.copy(
                                    currentNotificationAccessPermission = action.newNotificationAccessPermission
                            )

                            if (NewPermissionStatusUtil.toInt(action.newNotificationAccessPermission) > NewPermissionStatusUtil.toInt(device.highestNotificationAccessPermission)) {
                                device = device.copy(
                                        highestNotificationAccessPermission = action.newNotificationAccessPermission
                                )
                            }

                            if (device.currentNotificationAccessPermission != device.highestNotificationAccessPermission) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.NOTIFICATION_ACCESS
                                )
                            }
                        }
                    }

                    if (action.newOverlayPermission != null) {
                        if (device.currentOverlayPermission != action.newOverlayPermission) {
                            device = device.copy(
                                    currentOverlayPermission = action.newOverlayPermission
                            )

                            if (RuntimePermissionStatusUtil.toInt(action.newOverlayPermission) > RuntimePermissionStatusUtil.toInt(device.highestOverlayPermission)) {
                                device = device.copy(
                                        highestOverlayPermission = action.newOverlayPermission
                                )
                            }

                            if (device.currentOverlayPermission != device.highestOverlayPermission) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.OVERLAY_PERMISSION
                                )
                            }
                        }
                    }

                    if (action.newAccessibilityServiceEnabled != null) {
                        if (device.accessibilityServiceEnabled != action.newAccessibilityServiceEnabled) {
                            device = device.copy(
                                    accessibilityServiceEnabled = action.newAccessibilityServiceEnabled
                            )

                            if (action.newAccessibilityServiceEnabled) {
                                device = device.copy(
                                        wasAccessibilityServiceEnabled = true
                                )
                            }

                            if (device.accessibilityServiceEnabled != device.wasAccessibilityServiceEnabled) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.ACCESSIBILITY_SERVICE
                                )
                            }
                        }
                    }

                    if (action.newAppVersion != null) {
                        if (device.currentAppVersion != action.newAppVersion) {
                            device = device.copy(
                                    currentAppVersion = action.newAppVersion,
                                    highestAppVersion = Math.max(device.highestAppVersion, action.newAppVersion)
                            )

                            if (device.currentAppVersion != device.highestAppVersion) {
                                device = device.copy(
                                        hadManipulation = true,
                                        hadManipulationFlags = device.hadManipulationFlags or HadManipulationFlag.APP_VERSION
                                )
                            }
                        }
                    }

                    if (action.didReboot && device.considerRebootManipulation) {
                        device = device.copy(
                                manipulationDidReboot = true
                        )
                    }

                    if (action.isQOrLaterNow && !device.qOrLater) {
                        device = device.copy(qOrLater = true)
                    }

                    if (action.newPlatformType != null) {
                        device = device.copy(platformType = action.newPlatformType)
                    }

                    if (action.newPlatformLevel != null) {
                        device = device.copy(platformLevel = action.newPlatformLevel)
                    }

                    if (action.addedManipulationFlags != 0L) {
                        device = device.copy(manipulationFlags = device.manipulationFlags or action.addedManipulationFlags)
                    }

                    database.device().updateDeviceEntry(device)

                    null
                }
                is TriedDisablingDeviceAdminAction -> {
                    database.device().updateDeviceEntry(
                            database.device().getDeviceByIdSync(
                                    database.config().getOwnDeviceIdSync()!!
                            )!!.copy(
                                    manipulationTriedDisablingDeviceAdmin = true
                            )
                    )

                    null
                }
                is SignOutAtDeviceAction -> {
                    val deviceEntry = database.device().getDeviceByIdSync(database.config().getOwnDeviceIdSync()!!)!!

                    if (deviceEntry.defaultUser.isEmpty()) {
                        throw IllegalStateException("can not sign out without configured default user")
                    }

                    LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                            action = SetDeviceUserAction(
                                    deviceId = deviceEntry.id,
                                    userId = deviceEntry.defaultUser
                            ),
                            database = database,
                            fromChildSelfLimitAddChildUserId = null,
                            parentUserId = null
                    )

                    null
                }
                is UpdateAppActivitiesAction -> {
                    if (action.updatedOrAddedActivities.isNotEmpty()) {
                        database.appActivity().addAppActivitiesSync(
                                action.updatedOrAddedActivities.map { item ->
                                    AppActivity(
                                            deviceId = deviceId,
                                            appPackageName = item.packageName,
                                            activityClassName = item.className,
                                            title = item.title
                                    )
                                }
                        )
                    }

                    if (action.removedActivities.isNotEmpty()) {
                        action.removedActivities.groupBy { it.first }.entries.forEach { item ->
                            val packageName = item.component1()
                            val activities = item.component2().map { it.second }

                            database.appActivity().deleteAppActivitiesSync(
                                    deviceId = deviceId,
                                    packageName = packageName,
                                    activities = activities
                            )
                        }
                    }

                    null
                }
                is ForceSyncAction -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "dispatched force sync action")
                    }

                    null
                }
                is MarkTaskPendingAction -> {
                    val task = database.childTasks().getTaskByTaskId(action.taskId) ?: throw RuntimeException()
                    val category = database.category().getCategoryByIdSync(task.categoryId)!!
                    val device = database.device().getDeviceByIdSync(deviceId)!!

                    if (category.childId != device.currentUserId) throw IllegalStateException()

                    database.childTasks().updateItemSync(task.copy(pendingRequest = true))
                }
                // @tag:child-request
                is CreateChildRequestAction -> {
                    val device = database.device().getDeviceByIdSync(deviceId)!!
                    val user = database.user().getUserByIdSync(device.currentUserId) ?: throw IllegalStateException()
                    val now = System.currentTimeMillis()

                    database.user().updateUserSync(user.copy(childRequests = listOf(ChildRequest(
                        id = action.requestId, packageName = action.packageName, categoryId = action.categoryId,
                        deviceId = deviceId, word = action.word, createdAt = now, expiresAt = now + ChildRequest.LIFETIME,
                        answer = null, pending = true
                    )) + user.childRequests))
                }
                // @tag:parent-code @tag:app-allowance
                is GrantByParentCodeAction -> {
                    val device = database.device().getDeviceByIdSync(deviceId)!!
                    val user = database.user().getUserByIdSync(device.currentUserId) ?: throw IllegalStateException()

                    if (action.grant == ChildRequestAnswer.KIND_APP) {
                        val until = user.appAllowances.filter { it.packageName == action.packageName }
                            .maxOfOrNull { it.until }?.coerceAtLeast(action.until) ?: action.until

                        database.user().updateUserSync(user.copy(
                            appAllowances = user.appAllowances.filterNot { it.packageName == action.packageName } +
                                    AppAllowance(action.packageName, until)
                        ))
                    } else {
                        val category = database.category().getCategoryByIdSync(action.categoryId) ?: throw CategoryNotFoundException()

                        if (category.childId != user.id) throw IllegalStateException()

                        database.category().updateCategorySync(
                            category.copy(disableLimitsUntil = category.disableLimitsUntil.coerceAtLeast(action.until))
                        )
                    }
                }
                is UpdateInstalledAppsAction -> {/* nothing to do, this is only for the server */}
                is UploadDevicePublicKeyAction -> {/* nothing to do, this is only for the server */}
                is SendKeyRequestAction -> {/* nothing to do, this is only for the server */}
                is FinishKeyRequestAction -> {/* nothing to do, this is only for the server */}
                is ReplyToKeyRequestAction -> {/* nothing to do, this is only for the server */}
                is PingAction -> {/* nothing to do, this is only for the server */}
            }.let {  }
        }
    }
}

class CategoryNotFoundException: NullPointerException()