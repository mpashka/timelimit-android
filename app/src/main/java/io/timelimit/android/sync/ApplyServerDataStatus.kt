/*
 * TimeLimit Copyright <C> 2019 - 2022 Jonas Lochmann
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
package io.timelimit.android.sync

import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsync
import io.timelimit.android.crypto.Curve25519
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.*
import io.timelimit.android.integration.platform.PlatformIntegration
import io.timelimit.android.logic.crypto.CryptDataHandler
import io.timelimit.android.logic.crypto.KeyRequestSignedData
import io.timelimit.android.sync.actions.*
import io.timelimit.android.sync.actions.apply.ApplyActionUtil
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher
import io.timelimit.android.sync.network.ServerCryptContainer
import io.timelimit.android.sync.network.ServerDataStatus
import io.timelimit.android.sync.network.ServerPing

object ApplyServerDataStatus {
    suspend fun applyServerDataStatusCoroutine(status: ServerDataStatus, database: Database, platformIntegration: PlatformIntegration): Result {
        return Threads.database.executeAndWait {
            applyServerDataStatusSync(status, database, platformIntegration)
        }
    }

    fun applyServerDataStatusSync(status: ServerDataStatus, database: Database, platformIntegration: PlatformIntegration): Result {
        return database.runInTransaction {
            // this would override some local data which was not sent yet
            // so it's better to cancel in this case (or, more complicated,
            // apply the delta which was not sent locally)
            //
            // locking the database during a sync is no option as the network
            // connection could be really bad
            if (database.pendingSyncAction().countAllActions() > 0)
                throw PendingSyncActionException()

            run {
                // apply ful version until and message

                database.config().setFullVersionUntilSync(status.fullVersionUntil)
                database.config().setServerMessage(status.message)
                database.config().setServerApiLevelSync(status.apiLevel)
            }

            status.dh?.also { database.config().setLastDhKeySync(it) }

            var didCreateNewActions = false
            val answeredRequests = mutableListOf<AnsweredRequest>()

            run {
                val newUserList = status.newUserList

                if (newUserList != null) {
                    val oldUserList = database.user().getAllUsersSync()

                    run {
                        // update/ create entries (first because there must be always one parent user)

                        newUserList.data.forEach { newEntry ->
                            val newData = User(
                                    id = newEntry.id,
                                    name = newEntry.name,
                                    password = newEntry.password,
                                    secondPasswordSalt = newEntry.secondPasswordSalt,
                                    type = newEntry.type,
                                    timeZone = newEntry.timeZone,
                                    disableLimitsUntil = newEntry.disableLimitsUntil,
                                    mail = newEntry.mail,
                                    currentDevice = newEntry.currentDevice,
                                    categoryForNotAssignedApps = newEntry.categoryForNotAssignedApps,
                                    relaxPrimaryDevice = newEntry.relaxPrimaryDevice,
                                    mailNotificationFlags = newEntry.mailNotificationFlags,
                                    flags = newEntry.flags,
                                    urlFilter = newEntry.urlFilter,
                                    childRequests = newEntry.childRequests,
                                    appAllowances = newEntry.appAllowances
                            )

                            val oldEntry = oldUserList.find { it.id == newData.id }

                            // @tag:child-request
                            val ownDeviceId = database.config().getOwnDeviceIdSync()
                            newData.childRequests
                                .filter { request -> request.deviceId == ownDeviceId && request.answer != null }
                                .filter { request -> oldEntry?.childRequests?.find { it.id == request.id }?.answer == null }
                                .forEach { request ->
                                    answeredRequests.add(AnsweredRequest(
                                        request, newUserList.data.find { it.id == request.answer!!.parentUserId }?.name ?: ""
                                    ))
                                }

                            if (oldEntry == null) {
                                // create entry

                                database.user().addUserSync(newData)
                            } else {
                                // eventually update entry

                                if (newData != oldEntry) {
                                    database.user().updateUserSync(newData)
                                }
                            }
                        }
                    }

                    run {
                        // remove old entries
                        val newUserIds = newUserList.data.map { it.id }
                        val oldUserIds = oldUserList.map { it.id }

                        val oldUserIdsToRemove = ArrayList(oldUserIds)
                        oldUserIdsToRemove.removeAll(newUserIds)

                        oldUserIdsToRemove.forEach {
                            oldUserId ->

                            LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                                    action = RemoveUserAction(userId = oldUserId, authentication = null),
                                    database = database,
                                    fromChildSelfLimitAddChildUserId = null,
                                    parentUserId = null
                            )
                        }
                    }

                    run {
                        // update version

                        database.config().setUserListVersionSync(newUserList.version)
                        database.config().setParentCodeSecretSync(newUserList.parentCodeSecret) // @tag:parent-code
                    }
                }
            }

            val newDeviceTitles = run {
                // apply new device list
                val newDeviceList = status.newDeviceList
                val newDeviceTitles = mutableListOf<String>()

                if (newDeviceList != null) {
                    val oldDeviceList = database.device().getAllDevicesSync()

                    run {
                        // remove obsolete entries

                        val newDeviceListIds = newDeviceList.data.map { it.deviceId }
                        val oldDeviceListIds = oldDeviceList.map { it.id }

                        val removedDeviceEntryIds = ArrayList(oldDeviceListIds)
                        removedDeviceEntryIds.removeAll(newDeviceListIds)

                        if (removedDeviceEntryIds.isNotEmpty()) {
                            database.device().removeDevicesById(removedDeviceEntryIds)
                            database.app().removeAppsByDeviceIds(removedDeviceEntryIds)
                            database.appActivity().deleteAppActivitiesByDeviceIds(removedDeviceEntryIds)
                        }
                    }

                    run {
                        // add/ update entries
                        val thisDeviceId = database.config().getOwnDeviceIdSync()!!

                        newDeviceList.data.forEach {
                            newDevice ->
                            val oldDeviceEntry = oldDeviceList.find { it.id == newDevice.deviceId }

                            if (oldDeviceEntry == null) {
                                // create new entry

                                database.device().addDeviceSync(Device(
                                        id = newDevice.deviceId,
                                        name = newDevice.name,
                                        model = newDevice.model,
                                        addedAt = newDevice.addedAt,
                                        currentUserId = newDevice.currentUserId,
                                        installedAppsVersion = "",
                                        networkTime = newDevice.networkTime,
                                        currentProtectionLevel = newDevice.currentProtectionLevel,
                                        highestProtectionLevel = newDevice.highestProtectionLevel,
                                        currentUsageStatsPermission = newDevice.currentUsageStatsPermission,
                                        highestUsageStatsPermission = newDevice.highestUsageStatsPermission,
                                        currentNotificationAccessPermission = newDevice.currentNotificationAccessPermission,
                                        highestNotificationAccessPermission = newDevice.highestNotificationAccessPermission,
                                        currentAppVersion = newDevice.currentAppVersion,
                                        highestAppVersion = newDevice.highestAppVersion,
                                        manipulationTriedDisablingDeviceAdmin = newDevice.triedDisablingAdmin,
                                        manipulationDidReboot = newDevice.didReboot,
                                        hadManipulation = newDevice.hadManipulation,
                                        hadManipulationFlags = newDevice.hadManipulationFlags,
                                        didReportUninstall = newDevice.didReportUninstall,
                                        isUserKeptSignedIn = newDevice.isUserKeptSignedIn,
                                        showDeviceConnected = newDevice.showDeviceConnected,
                                        defaultUser = newDevice.defaultUser,
                                        defaultUserTimeout = newDevice.defaultUserTimeout,
                                        considerRebootManipulation = newDevice.considerRebootManipulation,
                                        currentOverlayPermission = newDevice.currentOverlayPermission,
                                        highestOverlayPermission = newDevice.highestOverlayPermission,
                                        accessibilityServiceEnabled = newDevice.accessibilityServiceEnabled,
                                        wasAccessibilityServiceEnabled = newDevice.wasAccessibilityServiceEnabled,
                                        enableActivityLevelBlocking = newDevice.enableActivityLevelBlocking,
                                        qOrLater = newDevice.qOrLater,
                                        manipulationFlags = newDevice.manipulationFlags,
                                        platformType = newDevice.platformType,
                                        platformLevel = newDevice.platformLevel ?: 0
                                ))

                                newDeviceTitles.add(newDevice.name)
                            } else {
                                // eventually update old entry

                                val updatedDeviceEntry = oldDeviceEntry.copy(
                                        name = newDevice.name,
                                        model = newDevice.model,
                                        addedAt = newDevice.addedAt,
                                        currentUserId = newDevice.currentUserId,
                                        networkTime = newDevice.networkTime,
                                        currentProtectionLevel = newDevice.currentProtectionLevel,
                                        highestProtectionLevel = newDevice.highestProtectionLevel,
                                        currentUsageStatsPermission = newDevice.currentUsageStatsPermission,
                                        highestUsageStatsPermission = newDevice.highestUsageStatsPermission,
                                        currentNotificationAccessPermission = newDevice.currentNotificationAccessPermission,
                                        highestNotificationAccessPermission = newDevice.highestNotificationAccessPermission,
                                        currentAppVersion = newDevice.currentAppVersion,
                                        highestAppVersion = newDevice.highestAppVersion,
                                        manipulationTriedDisablingDeviceAdmin = newDevice.triedDisablingAdmin,
                                        manipulationDidReboot = newDevice.didReboot,
                                        hadManipulation = newDevice.hadManipulation,
                                        hadManipulationFlags = newDevice.hadManipulationFlags,
                                        didReportUninstall = newDevice.didReportUninstall,
                                        isUserKeptSignedIn = newDevice.isUserKeptSignedIn,
                                        showDeviceConnected = newDevice.showDeviceConnected,
                                        defaultUser = newDevice.defaultUser,
                                        defaultUserTimeout = newDevice.defaultUserTimeout,
                                        considerRebootManipulation = newDevice.considerRebootManipulation,
                                        currentOverlayPermission = newDevice.currentOverlayPermission,
                                        highestOverlayPermission = newDevice.highestOverlayPermission,
                                        accessibilityServiceEnabled = newDevice.accessibilityServiceEnabled,
                                        wasAccessibilityServiceEnabled = newDevice.wasAccessibilityServiceEnabled,
                                        enableActivityLevelBlocking = newDevice.enableActivityLevelBlocking,
                                        qOrLater = newDevice.qOrLater,
                                        manipulationFlags = newDevice.manipulationFlags,
                                        platformType = newDevice.platformType ?: oldDeviceEntry.platformType,
                                        platformLevel = newDevice.platformLevel ?: oldDeviceEntry.platformLevel
                                )

                                if (updatedDeviceEntry != oldDeviceEntry) {
                                    database.device().updateDeviceEntry(updatedDeviceEntry)
                                }

                                if (updatedDeviceEntry.id == thisDeviceId) {
                                    if (updatedDeviceEntry.currentUserId != oldDeviceEntry.currentUserId) {
                                        runAsync {
                                            platformIntegration.stopSuspendingForAllApps()
                                        }
                                    }
                                }
                            }

                            if (newDevice.publicKey != null) {
                                val entry = database.deviceKey().getSync(newDevice.deviceId)

                                if (entry == null) {
                                    database.deviceKey().insert(DevicePublicKey(
                                        deviceId = newDevice.deviceId,
                                        publicKey = newDevice.publicKey,
                                        nextSequenceNumber = 0
                                    ))
                                }
                            }
                        }
                    }

                    database.config().setDeviceListVersionSync(newDeviceList.version)
                }

                newDeviceTitles.toList()
            }

            run {
                status.updatedExtendedDeviceData.forEach { item ->
                    fun handle(data: ServerCryptContainer, type: Int) {
                        val result = CryptDataHandler.process(
                            database = database,
                            data = data,
                            type = type,
                            categoryId = null,
                            deviceId = item.deviceId
                        )

                        if (result.didCreateKeyRequests) {
                            didCreateNewActions = true
                        }
                    }

                    item.appsBase?.let { handle(it, CryptContainerMetadata.TYPE_APP_LIST_BASE) }
                    item.appsDiff?.let { handle(it, CryptContainerMetadata.TYPE_APP_LIST_DIFF) }
                }
            }

            run {
                for (item in status.newInstalledApps) {
                    // apply changed version number
                    database.device().updateAppsVersion(
                        deviceId = item.deviceId,
                        appsVersion = item.version
                    )
                }
            }

            run {
                // apply removed categories
                // there is much data related to a removed category, so we use an existing function for this

                status.removedCategories.forEach {
                    val categoryId = it

                    if (database.category().getCategoryByIdSync(categoryId) == null) {
                        // category was likely deleted with the user, ignore it
                    } else {
                        LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                                action = DeleteCategoryAction(
                                        categoryId = categoryId
                                ),
                                database = database,
                                fromChildSelfLimitAddChildUserId = null,
                                parentUserId = null
                        )
                    }
                }
            }

            run {
                // apply category base data
                val newCategories = status.newCategoryBaseData

                if (newCategories.isNotEmpty()) {
                    newCategories.forEach {
                        val newCategory = it
                        val oldCategory = database.category().getCategoryByIdSync(it.categoryId)

                        DatabaseValidation.assertChildExists(database, newCategory.childId)

                        if (oldCategory == null) {
                            // create new category

                            database.category().addCategory(Category(
                                    id = newCategory.categoryId,
                                    childId = newCategory.childId,
                                    title = newCategory.title,
                                    blockedMinutesInWeek = newCategory.blockedMinutesInWeek,
                                    extraTimeInMillis = newCategory.extraTimeInMillis,
                                    extraTimeDay = newCategory.extraTimeDay,
                                    temporarilyBlocked = newCategory.temporarilyBlocked,
                                    temporarilyBlockedEndTime = newCategory.temporarilyBlockedEndTime,
                                    blockAllNotifications = newCategory.blockAllNotifications,
                                    baseVersion = newCategory.baseDataVersion,
                                    assignedAppsVersion = "",
                                    timeLimitRulesVersion = "",
                                    usedTimesVersion = "",
                                    tasksVersion = "",
                                    parentCategoryId = newCategory.parentCategoryId,
                                    timeWarnings = newCategory.timeWarnings,
                                    minBatteryLevelMobile = newCategory.minBatteryLevelMobile,
                                    minBatteryLevelWhileCharging = newCategory.minBatteryLevelCharging,
                                    sort = newCategory.sort,
                                    disableLimitsUntil = newCategory.disableLimitsUntil,
                                    flags = newCategory.flags,
                                    blockNotificationDelay = newCategory.blockNotificationDelay
                            ))
                        } else {
                            val updatedCategory = oldCategory.copy(
                                    childId = newCategory.childId,
                                    title = newCategory.title,
                                    blockedMinutesInWeek = newCategory.blockedMinutesInWeek,
                                    extraTimeInMillis = newCategory.extraTimeInMillis,
                                    extraTimeDay = newCategory.extraTimeDay,
                                    temporarilyBlocked = newCategory.temporarilyBlocked,
                                    temporarilyBlockedEndTime = newCategory.temporarilyBlockedEndTime,
                                    blockAllNotifications = newCategory.blockAllNotifications,
                                    baseVersion = newCategory.baseDataVersion,
                                    parentCategoryId = newCategory.parentCategoryId,
                                    timeWarnings = newCategory.timeWarnings,
                                    minBatteryLevelMobile = newCategory.minBatteryLevelMobile,
                                    minBatteryLevelWhileCharging = newCategory.minBatteryLevelCharging,
                                    sort = newCategory.sort,
                                    disableLimitsUntil = newCategory.disableLimitsUntil,
                                    flags = newCategory.flags,
                                    blockNotificationDelay = newCategory.blockNotificationDelay
                            )

                            if (updatedCategory != oldCategory) {
                                database.category().updateCategorySync(updatedCategory)
                            }
                        }

                        // apply networks
                        database.categoryNetworkId().deleteByCategoryId(newCategory.categoryId)

                        if (newCategory.networks.isNotEmpty()) {
                            database.categoryNetworkId().insertItemsSync(
                                    newCategory.networks.map { network ->
                                        CategoryNetworkId(
                                                categoryId = newCategory.categoryId,
                                                networkItemId = network.itemId,
                                                hashedNetworkId = network.hashedNetworkId
                                        )
                                    }
                            )
                        }

                        // apply time warnings
                        database.timeWarning().deleteByCategoryIdSync(newCategory.categoryId)

                        if (newCategory.additionalTimeWarnings.isNotEmpty()) {
                            database.timeWarning().insertItemsSync(
                                newCategory.additionalTimeWarnings.map { minutes ->
                                    CategoryTimeWarning(
                                        categoryId = newCategory.categoryId,
                                        minutes = minutes
                                    )
                                }
                            )
                        }
                    }
                }
            }

            run {
                // apply used times

                val newUsedTimes = status.newCategoryUsedTimes

                newUsedTimes.forEach {
                    newUsedTime ->
                    val categoryId = newUsedTime.categoryId

                    DatabaseValidation.assertCategoryExists(database, categoryId)

                    // replace items
                    database.usedTimes().deleteUsedTimeItems(categoryId)
                    database.usedTimes().insertUsedTimes(
                            newUsedTime.usedTimeItems.map {
                                UsedTimeItem(
                                        dayOfEpoch = it.dayOfEpoch,
                                        usedMillis = it.usedMillis,
                                        categoryId = categoryId,
                                        startTimeOfDay = it.startTimeOfDay,
                                        endTimeOfDay = it.endTimeOfDay
                                )
                            }
                    )

                    database.sessionDuration().deleteByCategoryId(categoryId)
                    database.sessionDuration().insertSessionDurationItemsSync(
                            newUsedTime.sessionDurations.map {
                                SessionDuration(
                                        categoryId = categoryId,
                                        maxSessionDuration = it.maxSessionDuration,
                                        sessionPauseDuration = it.sessionPauseDuration,
                                        startMinuteOfDay = it.startMinuteOfDay,
                                        endMinuteOfDay = it.endMinuteOfDay,
                                        lastUsage = it.lastUsage,
                                        lastSessionDuration = it.lastSessionDuration
                                )
                            }
                    )

                    // update version
                    database.category().updateCategoryUsedTimesVersion(
                            categoryId = categoryId,
                            usedTimesVersion = newUsedTime.version
                    )
                }
            }

            run {
                // apply assigned apps
                val thisDeviceId = database.config().getOwnDeviceIdSync()!!
                val thisDeviceEntry = database.device().getDeviceByIdSync(thisDeviceId)!!
                val thisDeviceUserCategories = if (thisDeviceEntry.currentUserId == "")
                    emptyList()
                else
                    database.category().getCategoriesByChildIdSync(thisDeviceEntry.currentUserId)
                val thisDeviceUserCategoryIds = thisDeviceUserCategories.map { it.id }.toSet()

                status.newCategoryAssignedApps.forEach {
                    item ->

                    DatabaseValidation.assertCategoryExists(database, item.categoryId)

                    database.categoryApp().deleteCategoryAppsByCategoryId(item.categoryId)
                    database.categoryApp().addCategoryAppsSync(item.assignedApps.map {
                        CategoryApp(
                                categoryId = item.categoryId,
                                appSpecifierString = it
                        )
                    })

                    database.category().updateCategoryAssignedAppsVersion(
                            categoryId = item.categoryId,
                            assignedAppsVersion = item.version
                    )
                }
            }

            run {
                // apply time limit rules
                status.newCategoryTimeLimitRules.forEach {
                    newTimeLimitRulesItem ->

                    val categoryId = newTimeLimitRulesItem.categoryId
                    val newRules = newTimeLimitRulesItem.rules
                    val oldRules = database.timeLimitRules().getTimeLimitRulesByCategorySync(categoryId)
                    val oldRuleIds = HashSet(oldRules.map { it.id })

                    DatabaseValidation.assertCategoryExists(database, categoryId)

                    newRules.forEach {
                        newRule ->
                        val oldRule = oldRules.find { it.id == newRule.id }

                        if (oldRule == null) {
                            // create new rule
                            database.timeLimitRules().addTimeLimitRule(newRule.toRealRule(categoryId))
                        } else {
                            // eventually update rule
                            oldRuleIds.remove(oldRule.id)

                            val newRuleEntry = newRule.toRealRule(categoryId)

                            if (newRuleEntry != oldRule) {
                                database.timeLimitRules().updateTimeLimitRule(newRuleEntry)
                            }
                        }
                    }

                    if (oldRuleIds.isNotEmpty()) {
                        database.timeLimitRules().deleteTimeLimitRulesByIdsSync(oldRuleIds.toList())
                    }

                    // save new version
                    database.category().updateCategoryRulesVersion(
                            categoryId = categoryId,
                            rulesVersion = newTimeLimitRulesItem.version
                    )
                }
            }

            status.newCategoryTasks.forEach { tasks ->
                database.childTasks().removeTasksByCategoryId(tasks.categoryId)

                database.childTasks().forceInsertItemsSync(
                        tasks.tasks.map { task ->
                            ChildTask(
                                    taskId = task.taskId,
                                    categoryId = tasks.categoryId,
                                    taskTitle = task.taskTitle,
                                    extraTimeDuration = task.extraTimeDuration,
                                    pendingRequest = task.pendingRequest,
                                    lastGrantTimestamp = task.lastGrantTimestamp
                            )
                        }
                )

                database.category().updateCategoryTasksVersion(categoryId = tasks.categoryId, tasksVersion = tasks.version)
            }

            status.newUserList?.data?.forEach { user ->
                if (user.limitLoginCategory == null) {
                    database.userLimitLoginCategoryDao().removeItemSync(user.id)
                } else {
                    val oldItem = database.userLimitLoginCategoryDao().getByParentUserIdSync(user.id)

                    if (oldItem == null || oldItem.categoryId != user.limitLoginCategory || oldItem.preBlockDuration != user.preBlockDuration) {
                        database.userLimitLoginCategoryDao().removeItemSync(user.id)
                        database.userLimitLoginCategoryDao().insertOrIgnoreItemSync(
                                UserLimitLoginCategory(
                                        userId = user.id,
                                        categoryId = user.limitLoginCategory,
                                        preBlockDuration = user.preBlockDuration
                                )
                        )
                    }
                }
            }

            status.pings.forEach { ping ->
                ApplyActionUtil.addAppLogicActionToDatabaseSync(
                    PingAction(
                        deviceId = ping.deviceId,
                        event = when (ping.type) {
                            ServerPing.Type.Ping -> PingAction.Event.Pong
                            ServerPing.Type.Pong -> PingAction.Event.Clear
                        },
                        token = ping.token
                    ),
                    database
                )

                didCreateNewActions = true
            }

            status.u2f?.also { u2f ->
                database.config().setU2fListVersionSync(u2f.version)

                val savedKeys = database.u2f().getAllSync().toMutableList()

                u2f.data.forEach { newKey ->
                    val oldKey = savedKeys.find {
                        it.keyHandle.contentEquals(newKey.keyHandle) &&
                                it.publicKey.contentEquals(newKey.publicKey)
                    }

                    if (oldKey != null) {
                        savedKeys.remove(oldKey)
                    } else {
                        database.u2f().addKey(
                            UserU2FKey(
                                keyId = 0,
                                userId = newKey.userId,
                                addedAt = newKey.addedAt,
                                keyHandle = newKey.keyHandle,
                                publicKey = newKey.publicKey,
                                nextCounter = 0
                            )
                        )
                    }
                }

                savedKeys.forEach { key ->
                    database.u2f().deleteKey(
                        parentUserId = key.userId,
                        keyHandle = key.keyHandle,
                        publicKey = key.publicKey
                    )
                }
            }

            Result(
                newDeviceTitles = newDeviceTitles,
                didCreateNewActions = didCreateNewActions,
                answeredRequests = answeredRequests
            )
        }
    }

    fun postNotifications(result: Result, platformIntegration: PlatformIntegration) {
        result.newDeviceTitles.forEach { deviceTitle ->
            platformIntegration.showNewDeviceNotification(deviceTitle)
        }
    }

    class PendingSyncActionException: RuntimeException()

    data class Result (
        val newDeviceTitles: List<String>,
        val didCreateNewActions: Boolean,
        val answeredRequests: List<AnsweredRequest>
    )

    data class AnsweredRequest(val request: ChildRequest, val parentName: String)
}
