/*
 * TimeLimit Copyright <C> 2019 - 2025 Jonas Lochmann
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
package io.timelimit.android.ui.setup.child

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.runAsync
import io.timelimit.android.data.backup.DatabaseBackup
import io.timelimit.android.data.devicename.DeviceName
import io.timelimit.android.data.model.ConsentFlags
import io.timelimit.android.livedata.castDown
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.sync.ApplyServerDataStatus
import io.timelimit.android.sync.network.NewDeviceInfo
import io.timelimit.android.sync.network.api.UnauthorizedHttpError
import io.timelimit.android.ui.setup.SetupUnprovisionedCheck
import java.security.SecureRandom
import java.util.Random

class SetupRemoteChildViewModel(application: Application): AndroidViewModel(application) {
    private val statusInternal = MutableLiveData<SetupRemoteChildStatus>().apply { value = SetupRemoteChildStatus.Idle }
    private val logic: AppLogic by lazy { DefaultAppLogic.with(application) }

    val status = statusInternal.castDown()
    val isSetupDone = logic.database.config().getOwnDeviceId().map { it != null }

    // @tag:family-join-google
    private var googleJoinCode: String? = null
    private var googleJoinDeadline = 0L
    private var googleJoinPolling: Job? = null

    fun trySetup(registerToken: String) {
        if (statusInternal.value != SetupRemoteChildStatus.Idle) {
            return
        }

        statusInternal.value = SetupRemoteChildStatus.Working

        runAsync {
            try {
                register(registerToken)
            } catch (ex: UnauthorizedHttpError) {
                statusInternal.value = SetupRemoteChildStatus.CodeInvalid
            } catch (ex: Exception) {
                statusInternal.value = SetupRemoteChildStatus.NetworkError
            }
        }
    }

    private suspend fun register(registerToken: String) {
        val api = logic.serverLogic.getServerConfigCoroutine().api
        val deviceModelName = Threads.database.executeAndWait { DeviceName.getDeviceNameSync(getApplication()) }

        val registerResponse = api.registerChildDevice(
                childDeviceInfo = NewDeviceInfo(model = deviceModelName),
                registerToken = registerToken,
                deviceName = deviceModelName
        )

        val clientStatusResponse = registerResponse.data

        Threads.database.executeAndWait {
            logic.database.runInTransaction {
                val customServerUrl = logic.database.config().getCustomServerUrlSync()

                SetupUnprovisionedCheck.checkSync(logic.database)

                logic.database.deleteAllData()
                logic.database.config().setCustomServerUrlSync(customServerUrl)
                logic.database.config().setOwnDeviceIdSync(registerResponse.ownDeviceId)
                logic.database.config().setDeviceAuthTokenSync(registerResponse.deviceAuthToken)

                logic.database.config().setConsentFlagSync(
                    ConsentFlags.BLOCK_USER_SWITCH_BY_DEFAULT,
                    true
                )

                ApplyServerDataStatus.applyServerDataStatusSync(clientStatusResponse, logic.database, logic.platformIntegration)
            }
        }

        DatabaseBackup.with(getApplication()).tryCreateDatabaseBackupAsync()
    }

    // @tag:family-join-google
    suspend fun startGoogleJoin(): String {
        val code = GoogleJoinCode.generate()

        googleJoinCode = code
        googleJoinDeadline = SystemClock.elapsedRealtime() + GoogleJoinCode.POLL_DURATION_MS

        return logic.serverLogic.getServerConfigCoroutine().serverUrl.trimEnd('/') + "/console/#/join/" + code
    }

    // @tag:family-join-google
    fun resumeGoogleJoinPolling() {
        val code = googleJoinCode ?: return

        if (googleJoinPolling?.isActive == true) return

        googleJoinPolling = viewModelScope.launch {
            while (SystemClock.elapsedRealtime() < googleJoinDeadline) {
                if (statusInternal.value == SetupRemoteChildStatus.Idle) {
                    try {
                        register(code)
                        googleJoinCode = null

                        return@launch
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        // the child has not confirmed in the browser yet or the network is flaky: retry silently
                    }
                }

                delay(GoogleJoinCode.POLL_INTERVAL_MS)
            }

            googleJoinCode = null
        }
    }

    fun pauseGoogleJoinPolling() {
        googleJoinPolling?.cancel()
        googleJoinPolling = null
    }

    fun confirmError() {
        if (statusInternal.value == SetupRemoteChildStatus.NetworkError || statusInternal.value == SetupRemoteChildStatus.CodeInvalid) {
            statusInternal.value = SetupRemoteChildStatus.Idle
        }
    }
}

// @tag:family-join-google
object GoogleJoinCode {
    const val POLL_INTERVAL_MS = 3_000L
    const val POLL_DURATION_MS = 15 * 60_000L
    private const val LENGTH = 32
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun generate(random: Random = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}

enum class SetupRemoteChildStatus {
    Idle, Working, NetworkError, CodeInvalid
}
