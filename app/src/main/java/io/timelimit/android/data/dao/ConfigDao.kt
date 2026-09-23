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
package io.timelimit.android.data.dao

import android.content.ComponentName
import android.util.Base64
import android.util.JsonWriter
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.room.*
import io.timelimit.android.data.model.ConfigurationItem
import io.timelimit.android.data.model.ConfigurationItemType
import io.timelimit.android.data.model.ConfigurationItemTypeConverter
import io.timelimit.android.data.model.ConfigurationItemTypeUtil
import io.timelimit.android.extensions.base64
import io.timelimit.android.extensions.parseBase64
import io.timelimit.android.extensions.toJsonReader
import io.timelimit.android.livedata.ignoreUnchanged
import io.timelimit.android.sync.network.ServerDhKey
import io.timelimit.android.ui.model.managechild.ManageChildCurrentDevice
import io.timelimit.android.update.UpdateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.StringWriter

@Dao
@TypeConverters(ConfigurationItemTypeConverter::class)
abstract class ConfigDao {
    @Query("SELECT * FROM config WHERE id IN (:keys)")
    protected abstract fun getConfigItemsSync(keys: List<Int>): List<ConfigurationItem>

    fun getConfigItemsSync() = getConfigItemsSync(ConfigurationItemTypeUtil.TYPES.map { ConfigurationItemTypeUtil.serialize(it) })

    @Query("SELECT * FROM config WHERE id = :key")
    protected abstract fun getRowByKeyAsync(key: ConfigurationItemType): LiveData<ConfigurationItem?>

    private fun getValueOfKeyAsync(key: ConfigurationItemType): LiveData<String?> {
        return getRowByKeyAsync(key).map { it?.value }.ignoreUnchanged()
    }

    @Query("SELECT * FROM config WHERE id = :key")
    protected abstract fun getRowByKeySync(key: ConfigurationItemType): ConfigurationItem?

    private fun getValueOfKeySync(key: ConfigurationItemType): String? {
        return getRowByKeySync(key)?.value
    }

    @Query("SELECT * FROM config WHERE id = :key")
    protected abstract suspend fun getRowCoroutine(key: ConfigurationItemType): ConfigurationItem?

    @Query("SELECT * FROM config WHERE id = :key")
    protected abstract fun getRowFlow(key: ConfigurationItemType): Flow<ConfigurationItem?>

    private fun getValueOfKeyFlow(key: ConfigurationItemType): Flow<String?> {
        return getRowFlow(key).map { it?.value }
    }

    private suspend fun getValueOfKeyCoroutine(key: ConfigurationItemType): String? {
        return getRowCoroutine(key)?.value
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun updateValueOfKeySync(item: ConfigurationItem)

    @Query("DELETE FROM config WHERE id = :key")
    protected abstract fun removeConfigItemSync(key: ConfigurationItemType)

    private fun updateValueSync(key: ConfigurationItemType, value: String?) {
        if (value != null) {
            updateValueOfKeySync(ConfigurationItem(key, value))
        } else {
            removeConfigItemSync(key)
        }
    }

    fun getOwnDeviceId(): LiveData<String?> {
        return getValueOfKeyAsync(ConfigurationItemType.OwnDeviceId)
    }

    fun getOwnDeviceIdFlow(): Flow<String?> {
        return getValueOfKeyFlow(ConfigurationItemType.OwnDeviceId)
    }

    fun getOwnDeviceIdSync(): String? {
        return getValueOfKeySync(ConfigurationItemType.OwnDeviceId)
    }

    fun setOwnDeviceIdSync(deviceId: String) {
        updateValueSync(ConfigurationItemType.OwnDeviceId, deviceId)
    }

    fun getDeviceListVersionSync(): String {
        return getValueOfKeySync(ConfigurationItemType.DeviceListVersion) ?: ""
    }

    fun setDeviceListVersionSync(deviceListVersion: String) {
        updateValueSync(ConfigurationItemType.DeviceListVersion, deviceListVersion)
    }

    fun getUserListVersionSync(): String {
        return getValueOfKeySync(ConfigurationItemType.UserListVersion) ?: ""
    }

    fun setUserListVersionSync(userListVersion: String) {
        updateValueSync(ConfigurationItemType.UserListVersion, userListVersion)
    }

    private fun getNextSyncActionSequenceNumberSync(): Long {
        val row = getValueOfKeySync(ConfigurationItemType.NextSyncSequenceNumber)

        if (row == null) {
            return 0
        } else {
            return row.toLong()
        }
    }

    private fun setNextSyncActionSequenceNumberSync(nextSyncSequenceNumber: Long) {
        updateValueSync(ConfigurationItemType.NextSyncSequenceNumber, nextSyncSequenceNumber.toString())
    }

    fun getNextSyncActionSequenceActionAndIncrementIt(): Long {
        val current = getNextSyncActionSequenceNumberSync()
        setNextSyncActionSequenceNumberSync(current + 1)

        return current
    }

    fun getDeviceAuthTokenSync(): String {
        val value = getValueOfKeySync(ConfigurationItemType.DeviceAuthToken)

        if (value == null) {
            return ""
        } else {
            return value
        }
    }

    fun getDeviceAuthTokenAsync(): LiveData<String> = getValueOfKeyAsync(ConfigurationItemType.DeviceAuthToken).map {
        if (it == null) {
            ""
        } else {
            it
        }
    }

    fun setDeviceAuthTokenSync(token: String) {
        updateValueSync(ConfigurationItemType.DeviceAuthToken, token)
    }

    fun getFullVersionUntilAsync() = getValueOfKeyAsync(ConfigurationItemType.FullVersionUntil).map {
        if (it == null || it.isEmpty()) {
            0L
        } else {
            it.toLong()
        }
    }

    fun getFullVersionUntilSync() = getValueOfKeySync(ConfigurationItemType.FullVersionUntil).let {
        if (it == null || it.isEmpty()) {
            0L
        } else {
            it.toLong()
        }
    }

    fun setFullVersionUntilSync(fullVersionUntil: Long) {
        updateValueSync(ConfigurationItemType.FullVersionUntil, fullVersionUntil.toString())
    }

    private fun getShownHintsLive(): LiveData<Long> {
        return getValueOfKeyAsync(ConfigurationItemType.ShownHints).map {
            if (it == null) {
                0
            } else {
                it.toLong(16)
            }
        }
    }

    private fun getShownHintsSync(): Long {
        val v = getValueOfKeySync(ConfigurationItemType.ShownHints)

        if (v == null) {
            return 0
        } else {
            return v.toLong(16)
        }
    }

    fun wereHintsShown(flags: Long) = getShownHintsLive().map {
        (it and flags) == flags
    }.ignoreUnchanged()

    fun wereAnyHintsShown() = getShownHintsLive().map { it != 0L }.ignoreUnchanged()

    fun setHintsShownSync(flags: Long) {
        updateValueSync(
                ConfigurationItemType.ShownHints,
                (getShownHintsSync() or flags).toString(16)
        )
    }

    fun setHintsNotShownSync(flags: Long) {
        updateValueSync(
            ConfigurationItemType.ShownHints,
            (getShownHintsSync() and flags.inv()).toString(16)
        )
    }

    fun resetShownHintsSync() {
        updateValueSync(ConfigurationItemType.ShownHints, null)
    }

    fun getLastAppVersionWhichSynced() = getValueOfKeySync(ConfigurationItemType.LastAppVersionWhichSynced)
    fun setLastAppVersionWhichSynced(version: String) = updateValueSync(ConfigurationItemType.LastAppVersionWhichSynced, version)

    fun setLastScreenOnTime(time: Long) = updateValueSync(ConfigurationItemType.LastScreenOnTime, time.toString())
    fun getLastScreenOnTime() = getValueOfKeySync(ConfigurationItemType.LastScreenOnTime)?.toLong() ?: 0L

    fun setServerMessage(message: String?) = updateValueSync(ConfigurationItemType.ServerMessage, message ?: "")
    fun getServerMessage() = getValueOfKeyAsync(ConfigurationItemType.ServerMessage).map { if (it.isNullOrBlank()) null else it }
    fun getServerMessageFlow() = getValueOfKeyFlow(ConfigurationItemType.ServerMessage).map { if (it.isNullOrBlank()) null else it }

    fun getCustomServerUrlSync() = getValueOfKeySync(ConfigurationItemType.CustomServerUrl) ?: ""
    fun getCustomServerUrlAsync() = getValueOfKeyAsync(ConfigurationItemType.CustomServerUrl).map { it ?: "" }
    fun getCustomServerUrlFlow() = getValueOfKeyFlow(ConfigurationItemType.CustomServerUrl).map { it ?: "" }
    fun setCustomServerUrlSync(url: String) = updateValueSync(ConfigurationItemType.CustomServerUrl, url)

    fun getForegroundAppQueryIntervalAsync(): LiveData<Long> = getValueOfKeyAsync(ConfigurationItemType.ForegroundAppQueryRange).map { (it ?: "0").toLong() }
    fun setForegroundAppQueryIntervalSync(interval: Long) {
        if (interval < 0) {
            throw IllegalArgumentException()
        }

        updateValueSync(ConfigurationItemType.ForegroundAppQueryRange, interval.toString())
    }

    fun getEnableBackgroundSyncAsync(): LiveData<Boolean> = getValueOfKeyAsync(ConfigurationItemType.EnableBackgroundSync).map { (it ?: "0") != "0" }
    fun setEnableBackgroundSync(enable: Boolean) = updateValueSync(ConfigurationItemType.EnableBackgroundSync, if (enable) "1" else "0")

    fun getEnableAlternativeDurationSelectionAsync() = getValueOfKeyAsync(ConfigurationItemType.EnableAlternativeDurationSelection).map { it == "1" }
    fun setEnableAlternativeDurationSelectionSync(enable: Boolean) = updateValueSync(ConfigurationItemType.EnableAlternativeDurationSelection, if (enable) "1" else "0")

    val experimentalFlags: LiveData<Long> by lazy {
        getValueOfKeyAsync(ConfigurationItemType.ExperimentalFlags).map {
            it?.toLong(16) ?: 0
        }
    }

    val experimentalFlagsFlow: Flow<Long> by lazy {
        getValueOfKeyFlow(ConfigurationItemType.ExperimentalFlags).map {
            it?.toLong(16) ?: 0
        }
    }

    fun getExperimentalFlagsSync(): Long {
        val v = getValueOfKeySync(ConfigurationItemType.ExperimentalFlags)

        if (v == null) {
            return 0
        } else {
            return v.toLong(16)
        }
    }

    fun isExperimentalFlagsSetAsync(flags: Long) = experimentalFlags.map {
        (it and flags) == flags
    }.ignoreUnchanged()

    fun isExperimentalFlagsSetFlow(flags: Long) = experimentalFlagsFlow.map {
        (it and flags) == flags
    }.distinctUntilChanged()

    fun isExperimentalFlagsSetSync(flags: Long) = (getExperimentalFlagsSync() and flags) == flags

    fun setExperimentalFlag(flags: Long, enable: Boolean) {
        updateValueSync(
                ConfigurationItemType.ExperimentalFlags,
                if (enable)
                    (getExperimentalFlagsSync() or flags).toString(16)
                else
                    (getExperimentalFlagsSync() and (flags.inv())).toString(16)
        )
    }

    fun getDefaultHomescreenSync(): ComponentName? = getValueOfKeySync(ConfigurationItemType.DefaultHomescreen)?.let {
        ComponentName.unflattenFromString(it)
    }

    fun setDefaultHomescreenSync(componentName: ComponentName?) = updateValueSync(ConfigurationItemType.DefaultHomescreen, componentName?.flattenToString())

    fun getHomescreenDelaySync(): Int = getValueOfKeySync(ConfigurationItemType.HomescreenDelay)?.toIntOrNull(radix = 10) ?: 5
    fun setHomescreenDelaySync(delay: Int) = updateValueSync(ConfigurationItemType.HomescreenDelay, delay.toString(radix = 10))

    fun setParentModeKeySync(key: ByteArray) = updateValueSync(ConfigurationItemType.ParentModeKey, Base64.encodeToString(key, 0))
    fun getParentModeKeySync() = getValueOfKeySync(ConfigurationItemType.ParentModeKey)?.let { value -> Base64.decode(value, 0) }
    fun getParentModeKeyLive() = getValueOfKeyAsync(ConfigurationItemType.ParentModeKey).map { value -> value?.let { Base64.decode(it, 0) } }

    fun areUpdatesEnabledLive() = getValueOfKeyAsync(ConfigurationItemType.EnableUpdates).map { it == "1" }
    fun getUpdateStatusLive() = getValueOfKeyAsync(ConfigurationItemType.UpdateStatus).map {
        try {
            it?.let { it.toJsonReader().use { UpdateStatus.parse(it) } }
        } catch (ex: Exception) {
            null
        }
    }
    fun getUpdateStatusSync() = getValueOfKeySync(ConfigurationItemType.UpdateStatus).let {
        try {
            it?.let { it.toJsonReader().use { UpdateStatus.parse(it) } }
        } catch (ex: Exception) {
            null
        }
    }

    fun setUpdatesEnabledSync(enable: Boolean) = updateValueSync(ConfigurationItemType.EnableUpdates, if (enable) "1" else "0")
    fun setUpdateStatus(status: UpdateStatus) = updateValueSync(
            ConfigurationItemType.UpdateStatus,
            StringWriter().use {
                JsonWriter(it).use {
                    status.serialize(it)
                }

                it.buffer.toString()
            }
    )

    suspend fun getCustomOrganizationName(): String? = getValueOfKeyCoroutine(ConfigurationItemType.CustomOrganizationName)
    fun setCustomOrganizationName(value: String) = updateValueSync(ConfigurationItemType.CustomOrganizationName, value)

    fun getServerApiLevelSync() = getValueOfKeySync(ConfigurationItemType.ServerApiLevel).let { it?.toInt() ?: 0 }
    fun getServerApiLevelLive() = getValueOfKeyAsync(ConfigurationItemType.ServerApiLevel).map { it?.toInt() ?: 0 }
    fun setServerApiLevelSync(serverApiLevel: Int) { updateValueSync(ConfigurationItemType.ServerApiLevel, serverApiLevel.toString()) }

    fun getAnnoyManualUnblockCounter() = getValueOfKeySync(ConfigurationItemType.AnnoyManualUnblockCounter).let { it?.toInt() ?: 0 }
    fun setAnoyManualUnblockCounterSync(counter: Int) { updateValueSync(ConfigurationItemType.AnnoyManualUnblockCounter, counter.toString()) }

    private val consentFlags: LiveData<Long> by lazy {
        getValueOfKeyAsync(ConfigurationItemType.ConsentFlags).map {
            it?.toLong(16) ?: 0
        }
    }

    fun getConsentFlagsSync(): Long = getValueOfKeySync(ConfigurationItemType.ConsentFlags).let {
        it?.toLong(16) ?: 0
    }

    fun isConsentFlagSetAsync(flags: Long) = consentFlags.map {
        (it and flags) == flags
    }.ignoreUnchanged()

    fun setConsentFlagSync(flags: Long, enable: Boolean) {
        updateValueSync(
            ConfigurationItemType.ConsentFlags,
            if (enable)
                (getConsentFlagsSync() or flags).toString(16)
            else
                (getConsentFlagsSync() and (flags.inv())).toString(16)
        )
    }

    fun getSigningKeySync() = getValueOfKeySync(ConfigurationItemType.SigningKey)?.parseBase64()
    fun getSigningKeyAsync() = getValueOfKeyAsync(ConfigurationItemType.SigningKey).map { v -> v?.let { Base64.decode(it, 0) } }
    fun setSigningKeySync(value: ByteArray) = updateValueSync(ConfigurationItemType.SigningKey, value.base64())

    private fun getNextSigningSequenceNumberSync(): Long = getValueOfKeySync(ConfigurationItemType.SignSequenceNumber)?.toLong() ?: 0L
    private fun setNextSigningSequenceNumberSync(value: Long) = updateValueSync(ConfigurationItemType.SignSequenceNumber, value.toString())
    fun getNextSigningSequenceNumberAndIncrementIt(): Long {
        val current = getNextSigningSequenceNumberSync()
        setNextSigningSequenceNumberSync(current + 1)

        return current
    }

    fun getLastServerKeyRequestSequenceSync(): Long? = getValueOfKeySync(ConfigurationItemType.LastServerKeyRequestSequence)?.toLong()
    fun setLastServerKeyRequestSequenceSync(value: Long) = updateValueSync(ConfigurationItemType.LastServerKeyRequestSequence, value.toString())

    fun getLastServerKeyResponseSequenceSync(): Long? = getValueOfKeySync(ConfigurationItemType.LastKeyResponseSequence)?.toLong()
    fun setLastServerKeyResponseSequenceSync(value: Long) = updateValueSync(ConfigurationItemType.LastKeyResponseSequence, value.toString())

    @Transaction
    open fun getLastDhKeySync(): ServerDhKey? {
        val version = getValueOfKeySync(ConfigurationItemType.DhKeyVersion)
        val key = getValueOfKeySync(ConfigurationItemType.DhKey)

        return if (version != null && key != null) ServerDhKey(version = version, key = key.parseBase64())
        else null
    }

    @Transaction
    open fun setLastDhKeySync(key: ServerDhKey) {
        updateValueSync(ConfigurationItemType.DhKeyVersion, key.version)
        updateValueSync(ConfigurationItemType.DhKey, key.key.base64())
    }

    fun getU2fVersionSync(): String? {
        return getValueOfKeySync(ConfigurationItemType.U2fListVersion)
    }

    fun setU2fListVersionSync(version: String?) {
        updateValueSync(ConfigurationItemType.U2fListVersion, version)
    }

    fun getRememberedCurrentDeviceChoiceFlow(): Flow<ManageChildCurrentDevice.RememberedChoice?> =
        getValueOfKeyFlow(ConfigurationItemType.CurrentDeviceRememberedChoice).map { value ->
            value?.let { ManageChildCurrentDevice.decodeRememberedChoice(it) }
        }

    fun setRememberedCurrentDeviceChoiceSync(value: ManageChildCurrentDevice.RememberedChoice?) {
        updateValueSync(
            ConfigurationItemType.CurrentDeviceRememberedChoice,
            value?.let { ManageChildCurrentDevice.encodeRememberedChoice((it)) }
        )
    }

    // @tag:parent-code
    fun getParentCodeSecretSync(): String? = getValueOfKeySync(ConfigurationItemType.ParentCodeSecret)

    fun setParentCodeSecretSync(value: String?) = updateValueSync(ConfigurationItemType.ParentCodeSecret, value)
}
