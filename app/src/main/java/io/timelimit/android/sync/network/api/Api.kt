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
package io.timelimit.android.sync.network.api

import io.timelimit.android.sync.network.*

interface ServerApi {
    suspend fun getTimeInMillis(): Long
    suspend fun sendMailLoginCode(mail: String, locale: String, deviceAuthToken: String?): String
    suspend fun signInByMailCode(mailLoginToken: String, code: String): String
    suspend fun getStatusByMailToken(mailAuthToken: String): StatusOfMailAddressResponse
    suspend fun createFamilyByMailToken(
            mailToken: String,
            parentPassword: ParentPassword,
            parentDevice: NewDeviceInfo,
            timeZone: String,
            parentName: String,
            deviceName: String
    ): AddDeviceResponse
    suspend fun signInToFamilyByMailToken(
            mailToken: String,
            parentDevice: NewDeviceInfo,
            deviceName: String
    ): AddDeviceResponse
    suspend fun recoverPasswordByMailToken(
            mailToken: String,
            parentPassword: ParentPassword
    )
    suspend fun registerChildDevice(
            registerToken: String,
            childDeviceInfo: NewDeviceInfo,
            deviceName: String
    ): AddDeviceResponse
    suspend fun pushChanges(request: ActionUploadRequest): ActionUploadResponse
    suspend fun pullChanges(deviceAuthToken: String, status: ClientDataStatus): ServerDataStatus
    suspend fun createAddDeviceToken(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String): CreateAddDeviceTokenResponse
    suspend fun canDoPurchase(deviceAuthToken: String): CanDoPurchaseStatus
    suspend fun finishPurchaseByGooglePlay(receipt: String, signature: String, deviceAuthToken: String)
    suspend fun linkParentMailAddress(mailAuthToken: String, deviceAuthToken: String, parentUserId: String, secondPasswordHash: String)
    suspend fun updatePrimaryDevice(request: UpdatePrimaryDeviceRequest): UpdatePrimaryDeviceResponse
    suspend fun requestSignOutAtPrimaryDevice(deviceAuthToken: String)
    suspend fun reportDeviceRemoved(deviceAuthToken: String)
    suspend fun removeDevice(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String, deviceId: String)
    suspend fun isDeviceRemoved(deviceAuthToken: String): Boolean
    // @tag:app-usage
    suspend fun getAppUsage(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String, userId: String, fromDay: Int, toDay: Int): List<AppUsageRow>
    suspend fun createIdentityToken(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String): String
    suspend fun requestAccountDeletion(deviceAuthToken: String, mailAuthTokens: List<String>)
}

class MailServerBlacklistedException: RuntimeException()
class MailAddressNotWhitelistedException: RuntimeException()
class MailLoginBlockedForIntegrityReasonsException: RuntimeException()
class MailServerTemporarilyBlacklistedException: RuntimeException()

// @tag:app-usage
data class AppUsageRow(val deviceId: String, val day: Int, val packageName: String, val ms: Long)
