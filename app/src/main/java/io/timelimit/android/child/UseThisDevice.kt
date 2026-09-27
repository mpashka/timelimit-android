package io.timelimit.android.child

import io.timelimit.android.logic.AppLogic
import io.timelimit.android.sync.network.UpdatePrimaryDeviceRequest
import io.timelimit.android.sync.network.UpdatePrimaryDeviceRequestType
import io.timelimit.android.sync.network.UpdatePrimaryDeviceResponseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class UseThisDeviceResult { Done, NeedsParent, OtherDeviceKeepsIt }

/**
 * "Играть на этом планшете": makes this tablet the child's primary device in one tap, the same
 * request as ManageChildCurrentDevice.makePrimary without its screen. [UseThisDeviceResult.NeedsParent]
 * when the family relaxed the primary device rule or the server wants the full version — both need
 * the upstream screen. Network failures are thrown as IOException.
 */
// @tag:new-ui
suspend fun useThisDevice(logic: AppLogic): UseThisDeviceResult {
    val data = withContext(Dispatchers.IO) { logic.database.derivedDataDao().getUserAndDeviceRelatedDataSync() }
    val user = data?.userRelatedData?.user ?: return UseThisDeviceResult.NeedsParent
    if (user.relaxPrimaryDevice) return UseThisDeviceResult.NeedsParent

    logic.currentDeviceLogic.cancelBorrowRequest()
    val server = logic.serverLogic.getServerConfigCoroutine()
    val request = UpdatePrimaryDeviceRequest(
        action = UpdatePrimaryDeviceRequestType.SetThisDevice,
        currentUserId = user.id,
        deviceAuthToken = server.deviceAuthToken
    )

    repeat(2) {
        when (server.api.updatePrimaryDevice(request).status) {
            UpdatePrimaryDeviceResponseType.Success -> {
                // the server does not trigger a sync for this request
                logic.syncUtil.requestImportantSyncAndWait()
                logic.currentDeviceLogic.dropBorrow()
                return UseThisDeviceResult.Done
            }
            UpdatePrimaryDeviceResponseType.AssignedToOtherDevice -> {
                server.api.requestSignOutAtPrimaryDevice(server.deviceAuthToken)
                withTimeoutOrNull(10_000) {
                    logic.database.user().getUserByIdFlow(user.id).map { it?.currentDevice.isNullOrEmpty() }.first { it }
                }
            }
            UpdatePrimaryDeviceResponseType.RequiresFullVersion -> return UseThisDeviceResult.NeedsParent
            UpdatePrimaryDeviceResponseType.UnknownError -> throw IllegalStateException("server refused to change the primary device")
        }
    }
    return UseThisDeviceResult.OtherDeviceKeepsIt
}
