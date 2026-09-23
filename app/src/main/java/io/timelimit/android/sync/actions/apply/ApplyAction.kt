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
package io.timelimit.android.sync.actions.apply

import android.util.JsonWriter
import android.util.Log
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.crypto.Sha512
import io.timelimit.android.data.Database
import io.timelimit.android.data.model.PendingSyncAction
import io.timelimit.android.data.model.PendingSyncActionType
import io.timelimit.android.data.model.UserType
import io.timelimit.android.extensions.base64
import io.timelimit.android.extensions.toByteArray
import io.timelimit.android.integration.platform.PlatformIntegration
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.ServerApiLevelLogic
import io.timelimit.android.sync.SyncUtil
import io.timelimit.android.sync.actions.*
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseAppLogicActionDispatcher
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseChildActionDispatcher
import io.timelimit.android.sync.actions.dispatch.LocalDatabaseParentActionDispatcher
import io.timelimit.android.ui.main.AuthenticatedUser
import org.json.JSONObject
import java.io.StringWriter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ApplyActionUtil {
    private const val LOG_TAG = "ApplyActionUtil"

    fun addAppLogicActionToDatabaseSync(action: AppLogicAction, database: Database) = database.runInUnobservedTransaction {
        val sequenceNumber = database.config().getNextSyncActionSequenceActionAndIncrementIt()

        database.pendingSyncAction().addSyncActionSync(
            PendingSyncAction(
                sequenceNumber = sequenceNumber,
                scheduledForUpload = false,
                type = PendingSyncActionType.AppLogic,
                userId = "",
                integrity = "",
                encodedAction = SerializationUtil.serializeAction(action)
            )
        )

        sequenceNumber
    }

    suspend fun applyAppLogicAction(
        action: AppLogicAction,
        appLogic: AppLogic,
        ignoreIfDeviceIsNotConfigured: Boolean
    ) = applyAppLogicAction(
        action = action,
        database = appLogic.database,
        syncUtil = appLogic.syncUtil,
        ignoreIfDeviceIsNotConfigured = ignoreIfDeviceIsNotConfigured
    )

    private suspend fun applyAppLogicAction(
            action: AppLogicAction,
            database: Database,
            syncUtil: SyncUtil,
            ignoreIfDeviceIsNotConfigured: Boolean
    ): ActionExecutionInfo {
        // uncomment this if you need to know what's dispatching an action
        /*
        if (BuildConfig.DEBUG) {
            try {
                throw Exception()
            } catch (ex: Exception) {
                Log.d(LOG_TAG, "handling action: $action", ex)
            }
        }
        */

        return Threads.database.executeAndWait {
            database.runInTransaction {
                val ownDeviceId = database.config().getOwnDeviceIdSync()

                if (ownDeviceId == null && ignoreIfDeviceIsNotConfigured) {
                    return@runInTransaction ActionExecutionInfo.NotConfigured
                }

                LocalDatabaseAppLogicActionDispatcher.dispatchAppLogicActionSync(action, ownDeviceId!!, database)

                if (isSyncEnabled(database)) {
                    if (action is AddUsedTimeActionVersion2) {
                        val previousAction = database.pendingSyncAction().getLatestUnscheduledActionSync()

                        if (previousAction != null && previousAction.type == PendingSyncActionType.AppLogic) {
                            val jsonObject = JSONObject(previousAction.encodedAction)

                            if (AddUsedTimeActionVersion2.doesMatch(jsonObject)) {
                                val parsed = AddUsedTimeActionVersion2.parse(jsonObject)

                                if (parsed.dayOfEpoch == action.dayOfEpoch) {
                                    var updatedAction: AddUsedTimeActionVersion2 = parsed
                                    var issues = false

                                    if (parsed.trustedTimestamp != 0L && action.trustedTimestamp != 0L) {
                                        issues = action.items.map { it.categoryId } != parsed.items.map { it.categoryId } ||
                                                parsed.trustedTimestamp >= action.trustedTimestamp

                                        updatedAction = updatedAction.copy(trustedTimestamp = action.trustedTimestamp)

                                        // keep timestamp of the old action
                                    } else if (parsed.trustedTimestamp != 0L || action.trustedTimestamp != 0L) {
                                        issues = true
                                    }

                                    action.items.forEach { newItem ->
                                        if (issues) return@forEach

                                        val oldItem = updatedAction.items.find { it.categoryId == newItem.categoryId }

                                        if (oldItem == null) {
                                            updatedAction = updatedAction.copy(
                                                    items = updatedAction.items + listOf(newItem)
                                            )
                                        } else {
                                            if (
                                                    oldItem.additionalCountingSlots != newItem.additionalCountingSlots ||
                                                    oldItem.sessionDurationLimits != newItem.sessionDurationLimits
                                            ) {
                                                issues = true
                                            }

                                            if (parsed.trustedTimestamp != 0L && action.trustedTimestamp != 0L) {
                                                val timeBeforeCurrentItem = action.trustedTimestamp - newItem.timeToAdd
                                                val diff = Math.abs(timeBeforeCurrentItem - parsed.trustedTimestamp)

                                                if (diff > 2 * 1000) {
                                                    issues = true
                                                }
                                            }

                                            val mergedItem = AddUsedTimeActionItem(
                                                    timeToAdd = oldItem.timeToAdd + newItem.timeToAdd,
                                                    extraTimeToSubtract = oldItem.extraTimeToSubtract + newItem.extraTimeToSubtract,
                                                    categoryId = newItem.categoryId,
                                                    additionalCountingSlots = oldItem.additionalCountingSlots,
                                                    sessionDurationLimits = oldItem.sessionDurationLimits
                                            )

                                            updatedAction = updatedAction.copy(
                                                    items = updatedAction.items.filter { it.categoryId != mergedItem.categoryId } + listOf(mergedItem)
                                            )
                                        }
                                    }

                                    if (!issues) {
                                        // update the previous action
                                        database.pendingSyncAction().updateEncodedActionSync(
                                                sequenceNumber = previousAction.sequenceNumber,
                                                action = StringWriter().apply {
                                                    JsonWriter(this).apply {
                                                        updatedAction.serialize(this)
                                                    }
                                                }.toString()
                                        )

                                        syncUtil.requestVeryUnimportantSync()

                                        return@runInTransaction ActionExecutionInfo.Enqueued(previousAction.sequenceNumber)
                                    }
                                }
                            }
                        }
                    }

                    val sequenceNumber = addAppLogicActionToDatabaseSync(action, database)

                    // @tag:app-usage @tag:device-state
                    if (action is AddUsedTimeActionVersion2 || action is SetAppUsageAction || action is SetForegroundAppAction) {
                        syncUtil.requestVeryUnimportantSync()
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(LOG_TAG, "request important sync due to dispatched app logic action")
                        }

                        syncUtil.requestImportantSync()
                    }

                    return@runInTransaction ActionExecutionInfo.Enqueued(sequenceNumber)
                } else {
                    return@runInTransaction ActionExecutionInfo.LocalMode
                }
            }
        }
    }

    suspend fun applyParentAction(
        action: ParentAction,
        authentication: ApplyActionParentAuthentication,
        logic: AppLogic
    ) = applyParentAction(
        action = action,
        database = logic.database,
        authentication = authentication,
        syncUtil = logic.syncUtil,
        platformIntegration = logic.platformIntegration
    )

    suspend fun applyParentAction(
        action: ParentAction,
        database: Database,
        authentication: ApplyActionParentAuthentication,
        syncUtil: SyncUtil,
        platformIntegration: PlatformIntegration
    ) {
        Threads.database.executeAndWait {
            database.runInTransaction {
                val deviceUserIdBeforeDispatchingForDeviceAuth = if (authentication is ApplyActionParentDeviceAuthentication || authentication is ApplyActionChildAddLimitAuthentication) {
                    val deviceId = database.config().getOwnDeviceIdSync()!!
                    val device = database.device().getDeviceByIdSync(deviceId)!!
                    val user = database.user().getUserByIdSync(device.currentUserId)

                    if (authentication is ApplyActionParentDeviceAuthentication) {
                        if (user?.type != UserType.Parent) {
                            throw IllegalStateException("no parent assigned to device")
                        }

                        if (!device.isUserKeptSignedIn) {
                            throw IllegalArgumentException("user is not kept signed in")
                        }
                    } else if (authentication is ApplyActionChildAddLimitAuthentication) {
                        if (user?.type != UserType.Child) {
                            throw IllegalStateException("no child assigned to device")
                        } else if (!user.allowSelfLimitAdding) {
                            throw IllegalStateException("this child may not add limits by itself")
                        }
                    } else {
                        throw IllegalStateException()
                    }

                    user.id
                } else null

                LocalDatabaseParentActionDispatcher.dispatchParentActionSync(
                        action = action,
                        database = database,
                        fromChildSelfLimitAddChildUserId = if (authentication is ApplyActionChildAddLimitAuthentication) deviceUserIdBeforeDispatchingForDeviceAuth else null,
                        parentUserId = when (authentication) {
                            is ApplyActionUserAuthentication -> authentication.user.userId
                            is ApplyActionParentDeviceAuthentication -> deviceUserIdBeforeDispatchingForDeviceAuth
                            is ApplyActionChildAddLimitAuthentication -> null
                        }
                )

                if (action is SetDeviceUserAction) {
                    val thisDeviceId = database.config().getOwnDeviceIdSync()!!

                    if (action.deviceId == thisDeviceId) {
                        platformIntegration.stopSuspendingForAllApps()
                    }
                }

                if (isSyncEnabled(database)) {
                    val serializedAction = StringWriter().apply {
                        JsonWriter(this).apply {
                            action.serialize(this)
                        }
                    }.toString()

                    val sequenceNumber = database.config().getNextSyncActionSequenceActionAndIncrementIt()

                    fun mac(secret: ByteArray) = Mac.getInstance("HmacSHA256").also {
                        val binaryDeviceId = database.config().getOwnDeviceIdSync()!!.toByteArray(Charsets.UTF_8)
                        val binaryAction = serializedAction.toByteArray(Charsets.UTF_8)

                        it.init(SecretKeySpec(secret, "HmacSHA256"))

                        it.update(sequenceNumber.toByteArray())

                        it.update(binaryDeviceId.size.toByteArray())
                        it.update(binaryDeviceId)

                        it.update(binaryAction.size.toByteArray())
                        it.update(binaryAction)
                    }.doFinal()

                    val pendingAction = when (authentication) {
                        is ApplyActionUserAuthentication -> {
                            val integrity = when (authentication.user) {
                                is AuthenticatedUser.Password -> {
                                    val serverLevel = ServerApiLevelLogic.getSync(database)

                                    if (serverLevel.hasLevelOrIsOffline(6)) {
                                        val mac = mac(authentication.user.secondPasswordHash.toByteArray(Charsets.UTF_8))

                                        "password:${mac.base64()}"
                                    } else {
                                        val integrityData = sequenceNumber.toString() +
                                                database.config().getOwnDeviceIdSync() +
                                                authentication.user.secondPasswordHash +
                                                serializedAction

                                        val hashedIntegrityData = Sha512.hashSync(integrityData)

                                        if (BuildConfig.DEBUG) {
                                            Log.d(LOG_TAG, "integrity data: $integrityData")
                                            Log.d(LOG_TAG, "integrity hash: $hashedIntegrityData")
                                        }

                                        hashedIntegrityData
                                    }
                                }
                                is AuthenticatedUser.U2fSigned -> {
                                    val mac = mac(authentication.user.dh.sharedSecret)

                                    constructU2fIntegrityString(authentication.user, mac)
                                }
                                is AuthenticatedUser.LocalAuth -> throw IllegalStateException()
                            }

                            PendingSyncAction(
                                sequenceNumber = sequenceNumber,
                                encodedAction = serializedAction,
                                integrity = integrity,
                                scheduledForUpload = false,
                                type = PendingSyncActionType.Parent,
                                userId = authentication.user.userId
                            )
                        }
                        ApplyActionParentDeviceAuthentication -> {
                            PendingSyncAction(
                                    sequenceNumber = sequenceNumber,
                                    encodedAction = serializedAction,
                                    integrity = "device",
                                    scheduledForUpload = false,
                                    type = PendingSyncActionType.Parent,
                                    userId = deviceUserIdBeforeDispatchingForDeviceAuth!!
                            )
                        }
                        ApplyActionChildAddLimitAuthentication -> {
                            PendingSyncAction(
                                    sequenceNumber = sequenceNumber,
                                    encodedAction = serializedAction,
                                    integrity = "childDevice",
                                    scheduledForUpload = false,
                                    type = PendingSyncActionType.Parent,
                                    userId = deviceUserIdBeforeDispatchingForDeviceAuth!!
                            )
                        }
                    }

                    database.pendingSyncAction().addSyncActionSync(pendingAction)

                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "request important sync due to dispatched parent action")
                    }

                    syncUtil.requestImportantSync(enqueueIfOffline = true)
                }
            }
        }
    }

    suspend fun applyChildAction(action: ChildAction, database: Database, authentication: ApplyActionChildAuthentication, syncUtil: SyncUtil) {
        Threads.database.executeAndWait {
            database.runInTransaction {
                LocalDatabaseChildActionDispatcher.dispatchChildActionSync(action, authentication.childUserId, database)

                if (isSyncEnabled(database)) {
                    val serializedAction = StringWriter().apply {
                        JsonWriter(this).apply {
                            action.serialize(this)
                        }
                    }.toString()

                    val sequenceNumber = database.config().getNextSyncActionSequenceActionAndIncrementIt()

                    val integrityData = sequenceNumber.toString() +
                            database.config().getOwnDeviceIdSync() +
                            authentication.secondPasswordHash +
                            serializedAction

                    val hashedIntegrityData = Sha512.hashSync(integrityData)

                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "integrity data: $integrityData")
                        Log.d(LOG_TAG, "integrity hash: $hashedIntegrityData")
                    }

                    database.pendingSyncAction().addSyncActionSync(
                            PendingSyncAction(
                                    sequenceNumber = sequenceNumber,
                                    encodedAction = serializedAction,
                                    integrity = hashedIntegrityData,
                                    scheduledForUpload = false,
                                    type = PendingSyncActionType.Child,
                                    userId = authentication.childUserId
                            )
                    )

                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "request important sync due to dispatched child action")
                    }

                    syncUtil.requestImportantSync(enqueueIfOffline = true)
                }
            }
        }
    }

    fun constructU2fIntegrityString(authentication: AuthenticatedUser.U2fSigned, mac: ByteArray): String {
        return "u2f:${authentication.dh.keyVersion}.${authentication.dh.ownPublicKey.base64()}.${authentication.u2fServerKeyId}.${authentication.signature.raw.base64()}.${mac.base64()}"
    }

    private fun isSyncEnabled(database: Database): Boolean {
        return database.config().getDeviceAuthTokenSync() != ""
    }
}

sealed class ApplyActionParentAuthentication
object ApplyActionParentDeviceAuthentication: ApplyActionParentAuthentication()
object ApplyActionChildAddLimitAuthentication: ApplyActionParentAuthentication()
data class ApplyActionUserAuthentication(val user: AuthenticatedUser): ApplyActionParentAuthentication()
data class ApplyActionChildAuthentication(val childUserId: String, val secondPasswordHash: String)

data class ApplyDirectCallAuthentication (
    val parentUserId: String,
    val parentPasswordSecondHash: String
) {
    companion object {
        fun from(auth: ApplyActionParentAuthentication) = when (auth) {
            ApplyActionParentDeviceAuthentication -> ApplyDirectCallAuthentication(
                parentUserId = "",
                parentPasswordSecondHash = "device"
            )
            is ApplyActionUserAuthentication -> ApplyDirectCallAuthentication(
                parentUserId = auth.user.userId,
                parentPasswordSecondHash = when (auth.user) {
                    is AuthenticatedUser.Password -> auth.user.secondPasswordHash
                    is AuthenticatedUser.U2fSigned -> ApplyActionUtil.constructU2fIntegrityString(
                        auth.user,
                        Mac.getInstance("HmacSHA256").also {
                            it.init(SecretKeySpec(auth.user.dh.sharedSecret, "HmacSHA256"))
                            it.update("direct action".toByteArray(Charsets.UTF_8))
                        }.doFinal()
                    )
                    is AuthenticatedUser.LocalAuth -> throw RuntimeException("authentication does not support that")
                }
            )
            is ApplyActionChildAddLimitAuthentication -> throw RuntimeException("child can not do that")
        }
    }
}

sealed class ActionExecutionInfo {
    object NotConfigured: ActionExecutionInfo()
    object LocalMode: ActionExecutionInfo()
    data class Enqueued(val sequenceNumber: Long): ActionExecutionInfo()
}