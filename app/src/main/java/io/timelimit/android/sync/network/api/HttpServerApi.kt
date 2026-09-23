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

import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import de.wivewa.android.network.X509ClientKeyManager
import io.timelimit.android.BuildConfig
import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.coroutines.waitForResponse
import io.timelimit.android.sync.network.*
import io.timelimit.android.util.okio.LengthSink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.BufferedSink
import okio.GzipSink
import okio.Sink
import okio.buffer
import java.io.OutputStreamWriter
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStore.PrivateKeyEntry
import java.security.ProviderException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLContext

class HttpServerApi(private val endpointWithoutSlashAtEnd: String): ServerApi {
    companion object {
        fun createInstance(url: String) = HttpServerApi(url.removeSuffix("/"))

        private const val LOG_TAG = "HttpServerApi"

        private const val DEVICE_AUTH_TOKEN = "deviceAuthToken"
        private const val MAIL_AUTH_TOKEN = "mailAuthToken"
        private const val MAIL_AUTH_TOKENS = "mailAuthTokens"
        private const val REGISTER_TOKEN = "registerToken"
        private const val MILLISECONDS = "ms"
        private const val STATUS = "status"
        private const val PASSWORD = "password"
        private const val PARENT_PASSWORD = "parentPassword"
        private const val PARENT_DEVICE = "parentDevice"
        private const val CHILD_DEVICE = "childDevice"
        private const val DEVICE_NAME = "deviceName"
        private const val TIMEZONE = "timeZone"
        private const val PARENT_NAME = "parentName"
        private const val PARENT_ID = "parentId"
        private const val PARENT_USER_ID = "parentUserId"
        private const val PARENT_PASSWORD_SECOND_HASH = "parentPasswordSecondHash"
        private const val DEVICE_ID = "deviceId"
        private const val MAIL = "mail"
        private const val LOCALE = "locale"
        private const val MAIL_LOGIN_TOKEN = "mailLoginToken"
        private const val RECEIVED_CODE = "receivedCode"
        private const val CLIENT_LEVEL = "clientLevel"

        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

        private suspend fun createJsonRequestBody(
            serialize: (writer: JsonWriter) -> Unit,
            measureContentLength: Boolean
        ): RequestBody {
            fun write(sink: Sink) {
                val writer = JsonWriter(
                    OutputStreamWriter(
                        GzipSink(sink)
                            .buffer().outputStream()
                    )
                )

                serialize(writer)

                writer.close()
            }

            val length =
                if (measureContentLength) Threads.network.executeAndWait { LengthSink().also { write(it) }.length }
                else null

            return object: RequestBody() {
                override fun contentType() = JSON
                override fun writeTo(sink: BufferedSink) = write(sink)
                override fun contentLength(): Long = length ?: -1
            }
        }
    }

    private var sendContentLength = false

    override suspend fun getTimeInMillis(): Long {
        httpClient.newCall(
                Request.Builder()
                        .get()
                        .url("$endpointWithoutSlashAtEnd/time")
                        .build()
        ).waitForResponse().use {
            it.assertSuccess()

            return Threads.network.executeAndWait {
                val body = it.body!!
                val reader = JsonReader(body.charStream())
                var result: Long? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        MILLISECONDS -> result = reader.nextLong()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                result!!
            }
        }
    }

    override suspend fun sendMailLoginCode(mail: String, locale: String, deviceAuthToken: String?): String =
        sendMailLoginCode(mail, locale, deviceAuthToken, false)

    private suspend fun sendMailLoginCode(mail: String, locale: String, deviceAuthToken: String?, skipDeviceVerification: Boolean): String = withDeviceVerification (enable = !skipDeviceVerification) { client ->
        postJsonRequest(
            "auth/send-mail-login-code-v2",
            client = client,
            transformRequest = { it
                .header("X-Client-Package", BuildConfig.APPLICATION_ID)
                .header("X-Client-Version", BuildConfig.VERSION_NAME)
            }
        ) { writer ->
            writer.beginObject()
            writer.name(MAIL).value(mail)
            writer.name(LOCALE).value(locale)
            if (deviceAuthToken != null) { writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken) }
            writer.endObject()
        }.use {
            try {
                it.assertSuccess()
            } catch (ex: BadRequestHttpError) {
                if (deviceAuthToken != null || !skipDeviceVerification) {
                    // retry without device auth token

                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "sendMailLoginCode() try again without deviceAuthToken and device verification")
                    }

                    Threads.network.executeAndWait { it.close() }

                    return@use sendMailLoginCode(mail, locale, null, true)
                } else {
                    throw ex
                }
            }

            val body = it.body!!

            return@use Threads.network.executeAndWait {
                var response: String? = null

                JsonReader(body.charStream()).use { reader ->
                    reader.beginObject()

                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            MAIL_LOGIN_TOKEN -> response = reader.nextString()
                            "mailServerBlacklisted" -> {
                                if (reader.nextBoolean()) {
                                    throw MailServerBlacklistedException()
                                }
                            }
                            "mailAddressNotWhitelisted" -> {
                                if (reader.nextBoolean()) {
                                    throw MailAddressNotWhitelistedException()
                                }
                            }
                            "blockedForIntegrityReasons" -> {
                                if (reader.nextBoolean()) {
                                    throw MailLoginBlockedForIntegrityReasonsException()
                                }
                            }
                            "mailServerBlacklistedTemporarily" -> {
                                if (reader.nextBoolean()) {
                                    throw MailServerTemporarilyBlacklistedException()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }

                    reader.endObject()
                }

                response!!
            }
        }
    }

    override suspend fun signInByMailCode(mailLoginToken: String, code: String): String {
        postJsonRequest(
            "auth/sign-in-by-mail-code"
        ) { writer ->

            writer.beginObject()
            writer.name(MAIL_LOGIN_TOKEN).value(mailLoginToken)
            writer.name(RECEIVED_CODE).value(code)
            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            return Threads.network.executeAndWait {
                var response: String? = null

                JsonReader(body.charStream()).use { reader ->
                    reader.beginObject()

                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            MAIL_AUTH_TOKEN -> response = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }

                    reader.endObject()
                }

                response!!
            }
        }
    }

    override suspend fun getStatusByMailToken(mailAuthToken: String): StatusOfMailAddressResponse {
        postJsonRequest("parent/get-status-by-mail-address") { writer ->
            writer.beginObject()
            writer.name(MAIL_AUTH_TOKEN).value(mailAuthToken)
            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            return Threads.network.executeAndWait {
                StatusOfMailAddressResponse.parse(JsonReader(body.charStream()))
            }
        }
    }

    override suspend fun createFamilyByMailToken(
        mailToken: String, parentPassword: ParentPassword, parentDevice: NewDeviceInfo,
        timeZone: String, parentName: String, deviceName: String
    ): AddDeviceResponse {
        try {
            return createFamilyByMailTokenInternal(
                mailToken, parentPassword, parentDevice, timeZone, parentName, deviceName, skipClientLevel = false
            )
        } catch (ex: BadRequestHttpError) {
            return createFamilyByMailTokenInternal(
                mailToken, parentPassword, parentDevice, timeZone, parentName, deviceName, skipClientLevel = true
            )
        }
    }

    private suspend fun createFamilyByMailTokenInternal(
        mailToken: String, parentPassword: ParentPassword, parentDevice: NewDeviceInfo,
        timeZone: String, parentName: String, deviceName: String, skipClientLevel: Boolean
    ): AddDeviceResponse {
        return postJsonRequest("parent/create-family") { writer ->
            writer.beginObject()

            writer.name(MAIL_AUTH_TOKEN).value(mailToken)
            writer.name(TIMEZONE).value(timeZone)
            writer.name(PARENT_NAME).value(parentName)
            writer.name(DEVICE_NAME).value(deviceName)

            writer.name(PARENT_PASSWORD)
            parentPassword.serialize(writer)

            writer.name(PARENT_DEVICE)
            parentDevice.serialize(writer)

            if (!skipClientLevel) {
                writer.name(CLIENT_LEVEL).value(ClientDataStatus.CLIENT_LEVEL_VALUE)
            }

            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            Threads.network.executeAndWait {
                ServerAddDeviceResponse.parse(JsonReader(body.charStream()))
            }
        }.let {
            AddDeviceResponse(
                deviceAuthToken = it.deviceAuthToken,
                ownDeviceId = it.ownDeviceId,
                data = it.data ?: pullChanges(it.deviceAuthToken, ClientDataStatus.empty)
            )
        }
    }

    override suspend fun signInToFamilyByMailToken(mailToken: String, parentDevice: NewDeviceInfo, deviceName: String): AddDeviceResponse {
        try {
            return signInToFamilyByMailTokenInternal(mailToken, parentDevice, deviceName, skipClientLevel = false)
        } catch (ex: BadRequestHttpError) {
            return signInToFamilyByMailTokenInternal(mailToken, parentDevice, deviceName, skipClientLevel = true)
        }
    }

    private suspend fun signInToFamilyByMailTokenInternal(
        mailToken: String, parentDevice: NewDeviceInfo, deviceName: String, skipClientLevel: Boolean
    ): AddDeviceResponse {
        return postJsonRequest("parent/sign-in-into-family") { writer ->
            writer.beginObject()

            writer.name(MAIL_AUTH_TOKEN).value(mailToken)
            writer.name(DEVICE_NAME).value(deviceName)

            writer.name(PARENT_DEVICE)
            parentDevice.serialize(writer)

            if (!skipClientLevel) {
                writer.name(CLIENT_LEVEL).value(ClientDataStatus.CLIENT_LEVEL_VALUE)
            }

            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            Threads.network.executeAndWait {
                ServerAddDeviceResponse.parse(JsonReader(body.charStream()))
            }
        }.let {
            AddDeviceResponse(
                deviceAuthToken = it.deviceAuthToken,
                ownDeviceId = it.ownDeviceId,
                data = it.data ?: pullChanges(it.deviceAuthToken, ClientDataStatus.empty)
            )
        }
    }

    override suspend fun recoverPasswordByMailToken(mailToken: String, parentPassword: ParentPassword) {
        postJsonRequest("parent/recover-parent-password") { writer ->
            writer.beginObject()

            writer.name(MAIL_AUTH_TOKEN).value(mailToken)

            writer.name(PASSWORD)
            parentPassword.serialize(writer)

            writer.endObject()
        }.use {
            it.assertSuccess()
        }
    }

    override suspend fun registerChildDevice(registerToken: String, childDeviceInfo: NewDeviceInfo, deviceName: String): AddDeviceResponse {
        try {
            return registerChildDeviceInternal(registerToken, childDeviceInfo, deviceName, skipClientLevel = false)
        } catch (ex: BadRequestHttpError) {
            return registerChildDeviceInternal(registerToken, childDeviceInfo, deviceName, skipClientLevel = true)
        }
    }

    private suspend fun registerChildDeviceInternal(
        registerToken: String, childDeviceInfo: NewDeviceInfo, deviceName: String, skipClientLevel: Boolean
    ): AddDeviceResponse {
        return postJsonRequest("child/add-device") { writer ->
            writer.beginObject()

            writer.name(REGISTER_TOKEN).value(registerToken)

            writer.name(CHILD_DEVICE)
            childDeviceInfo.serialize(writer)

            writer.name(DEVICE_NAME).value(deviceName)

            if (!skipClientLevel) {
                writer.name(CLIENT_LEVEL).value(ClientDataStatus.CLIENT_LEVEL_VALUE)
            }

            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            Threads.network.executeAndWait {
                ServerAddDeviceResponse.parse(JsonReader(body.charStream()))
            }
        }.let {
            AddDeviceResponse(
                deviceAuthToken = it.deviceAuthToken,
                ownDeviceId = it.ownDeviceId,
                data = it.data ?: pullChanges(it.deviceAuthToken, ClientDataStatus.empty)
            )
        }
    }

    override suspend fun pushChanges(request: ActionUploadRequest): ActionUploadResponse {
        postJsonRequest("sync/push-actions") { request.serialize(it) }.use {
            it.assertSuccess()

            val body = it.body!!

            return Threads.network.executeAndWait {
                ActionUploadResponse.parse(JsonReader(body.charStream()))
            }
        }
    }

    override suspend fun pullChanges(deviceAuthToken: String, status: ClientDataStatus): ServerDataStatus {
        postJsonRequest("sync/pull-status") { writer ->
            writer.beginObject()

            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)

            writer.name(STATUS)
            status.serialize(writer)

            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            return Threads.network.executeAndWait {
                ServerDataStatus.parse(JsonReader(body.charStream()))
            }
        }
    }

    override suspend fun createAddDeviceToken(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String): CreateAddDeviceTokenResponse {
        postJsonRequest("parent/create-add-device-token") { writer ->
            writer.beginObject()

            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name(PARENT_ID).value(parentUserId)
            writer.name(PARENT_PASSWORD_SECOND_HASH).value(parentPasswordSecondHash)

            writer.endObject()
        }.use {
            it.assertSuccess()

            val body = it.body!!

            return Threads.network.executeAndWait {
                CreateAddDeviceTokenResponse.parse(JsonReader(body.charStream()))
            }
        }
    }

    override suspend fun canDoPurchase(deviceAuthToken: String): CanDoPurchaseStatus {
        postJsonRequest("purchase/can-do-purchase") { writer ->
            writer.beginObject()

            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name("type").value("googleplay")

            writer.endObject()
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())

                CanDoPurchaseParser.parse(reader)
            }
        }
    }

    override suspend fun finishPurchaseByGooglePlay(receipt: String, signature: String, deviceAuthToken: String) {
        postJsonRequest("purchase/finish-purchase-by-google-play") { writer ->
            writer.beginObject()

            writer.name("receipt").value(receipt)
            writer.name("signature").value(signature)
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)

            writer.endObject()
        }.use {
            response ->

            response.assertSuccess()
        }
    }

    override suspend fun linkParentMailAddress(mailAuthToken: String, deviceAuthToken: String, parentUserId: String, secondPasswordHash: String) {
        postJsonRequest("parent/link-mail-address") { writer ->
            writer.beginObject()

            writer.name(MAIL_AUTH_TOKEN).value(mailAuthToken)
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name(PARENT_USER_ID).value(parentUserId)
            writer.name(PARENT_PASSWORD_SECOND_HASH).value(secondPasswordHash)

            writer.endObject()
        }.use { response ->
            response.assertSuccess()
        }
    }

    override suspend fun updatePrimaryDevice(request: UpdatePrimaryDeviceRequest): UpdatePrimaryDeviceResponse {
        postJsonRequest("child/update-primary-device") {
            request.serialize(it)
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())

                UpdatePrimaryDeviceResponse.parse(reader)
            }
        }
    }

    override suspend fun requestSignOutAtPrimaryDevice(deviceAuthToken: String) {
        postJsonRequest("child/logout-at-primary-device") { writer ->
            writer.beginObject()

            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)

            writer.endObject()
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())

                reader.skipValue()
            }
        }
    }

    override suspend fun reportDeviceRemoved(deviceAuthToken: String) {
        postJsonRequest("sync/report-removed") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.endObject()
        }.use { response ->
            response.assertSuccess()
        }
    }

    override suspend fun removeDevice(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String, deviceId: String) {
        postJsonRequest("parent/remove-device") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name(PARENT_USER_ID).value(parentUserId)
            writer.name(PARENT_PASSWORD_SECOND_HASH).value(parentPasswordSecondHash)
            writer.name(DEVICE_ID).value(deviceId)
            writer.endObject()
        }.use { response ->
            response.assertSuccess()
        }
    }

    // @tag:app-usage
    override suspend fun getAppUsage(deviceAuthToken: String, parentUserId: String, parentPasswordSecondHash: String, userId: String, fromDay: Int, toDay: Int): List<AppUsageRow> {
        postJsonRequest("parent/get-app-usage") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name(PARENT_USER_ID).value(parentUserId)
            writer.name(PARENT_PASSWORD_SECOND_HASH).value(parentPasswordSecondHash)
            writer.name("userId").value(userId)
            writer.name("fromDay").value(fromDay)
            writer.name("toDay").value(toDay)
            writer.endObject()
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())
                val result = mutableListOf<AppUsageRow>()

                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "items") { reader.skipValue(); continue }

                    reader.beginArray()
                    while (reader.hasNext()) {
                        var deviceId = ""; var day = 0; var packageName = ""; var ms = 0L

                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "deviceId" -> deviceId = reader.nextString()
                                "day" -> day = reader.nextInt()
                                "packageName" -> packageName = reader.nextString()
                                "ms" -> ms = reader.nextLong()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()

                        result.add(AppUsageRow(deviceId, day, packageName, ms))
                    }
                    reader.endArray()
                }
                reader.endObject()

                result
            }
        }
    }

    override suspend fun isDeviceRemoved(deviceAuthToken: String): Boolean {
        postJsonRequest("sync/is-device-removed") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.endObject()
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())
                var result: Boolean? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "isDeviceRemoved" -> result = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                result!!
            }
        }
    }

    override suspend fun createIdentityToken(
        deviceAuthToken: String,
        parentUserId: String,
        parentPasswordSecondHash: String
    ): String {
        postJsonRequest("parent/create-identity-token") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)
            writer.name(PARENT_USER_ID).value(parentUserId)
            writer.name(PARENT_PASSWORD_SECOND_HASH).value(parentPasswordSecondHash)
            writer.name("purpose").value("purchase")
            writer.endObject()
        }.use { response ->
            response.assertSuccess()

            return Threads.network.executeAndWait {
                val reader = JsonReader(response.body!!.charStream())
                var token: String? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "token" -> token = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                token!!
            }
        }
    }

    override suspend fun requestAccountDeletion(
        deviceAuthToken: String,
        mailAuthTokens: List<String>
    ) {
        postJsonRequest("parent/delete-account") { writer ->
            writer.beginObject()
            writer.name(DEVICE_AUTH_TOKEN).value(deviceAuthToken)

            writer.name(MAIL_AUTH_TOKENS).beginArray()
            mailAuthTokens.forEach { writer.value(it) }
            writer.endArray()

            writer.endObject()
        }.use { response ->
            response.assertSuccess()
        }
    }

    private suspend fun postJsonRequest(
        path: String,
        client: OkHttpClient = httpClient,
        transformRequest: (Request.Builder) -> Request.Builder = { it },
        requestBody: (writer: JsonWriter) -> Unit,
    ): Response {
        if (!sendContentLength) {
            val response = postJsonRequest(
                path,
                requestBody,
                transmitContentLength = false,
                client = client,
                transformRequest = transformRequest
            )

            if (response.code != 411) return response

            Threads.network.executeAndWait { response.closeQuietly() }

            sendContentLength = true
        }

        return postJsonRequest(
            path,
            requestBody,
            transmitContentLength = true,
            client = client,
            transformRequest = transformRequest
        )
    }

    private suspend fun postJsonRequest(
        path: String,
        requestBody: (writer: JsonWriter) -> Unit,
        transmitContentLength: Boolean,
        client: OkHttpClient = httpClient,
        transformRequest: (Request.Builder) -> Request.Builder = { it }
    ): Response {
        val body = createJsonRequestBody(requestBody, transmitContentLength)

        return client.newCall(
            Request.Builder()
                .url("$endpointWithoutSlashAtEnd/$path")
                .post(body)
                .header("Content-Encoding", "gzip")
                .let { transformRequest(it) }
                .build()
        ).waitForResponse()
    }

    private suspend fun <T> withDeviceVerification(enable: Boolean = true, block: suspend (client: OkHttpClient) -> T): T {
        if (VERSION.SDK_INT >= VERSION_CODES.N && enable) {
            val keyStoreName = "AndroidKeyStore"
            val keyStore = KeyStore.getInstance(keyStoreName).also { it.load(null) }
            val keyId = "temp-" + UUID.randomUUID().toString()
            val now = getTimeInMillis()

            try {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreName)
                    .also {
                        it.initialize(
                            KeyGenParameterSpec.Builder(
                                keyId,
                                KeyProperties.PURPOSE_SIGN
                            )
                                .setAlgorithmParameterSpec(
                                    ECGenParameterSpec("prime256v1")
                                )
                                .setDigests(
                                    KeyProperties.DIGEST_NONE,
                                    KeyProperties.DIGEST_SHA256,
                                    KeyProperties.DIGEST_SHA384,
                                    KeyProperties.DIGEST_SHA512
                                )
                                .setCertificateNotBefore(Date(now - 1000 * 60))
                                .setCertificateNotAfter(Date(now + 1000 * 60))
                                .setAttestationChallenge(byteArrayOf())
                                .build()
                        )
                    }.genKeyPair()
            } catch (ex: ProviderException) {
                // java.security.ProviderException: Failed to generate attestation certificate chain

                return block(httpClient)
            }

            try {
                val key = keyStore.getEntry(keyId, null) as PrivateKeyEntry
                val keyManager = X509ClientKeyManager(key.privateKey, key.certificateChain.map { it as X509Certificate })

                val socketFactory = SSLContext.getInstance("TLS").also {
                    it.init(arrayOf(keyManager), null, null)
                }.socketFactory

                return block(
                    httpClient.newBuilder()
                        .sslSocketFactory(socketFactory, httpClient.x509TrustManager!!)
                        .build()
                )
            } finally {
                keyStore.deleteEntry(keyId)
            }
        } else return block(httpClient)
    }
}
