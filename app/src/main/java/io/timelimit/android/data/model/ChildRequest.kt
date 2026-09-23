package io.timelimit.android.data.model

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.TypeConverter
import java.io.StringReader
import java.io.StringWriter

/**
 * A request of a child as `users.data[].requests` of docs/specification/protocol-new-ui.md, section 1.
 * [pending] is local only: created on this device and not yet seen in a list from the server.
 */
// @tag:child-request
data class ChildRequest(
    val id: String,
    val packageName: String,
    val categoryId: String,
    val deviceId: String,
    val word: String,
    val createdAt: Long,
    val expiresAt: Long,
    val answer: ChildRequestAnswer?,
    val pending: Boolean = false,
) {
    companion object {
        const val LIFETIME = 30 * 60 * 1000L
    }
}

data class ChildRequestAnswer(
    val kind: String,
    val until: Long,
    val word: String,
    val parentUserId: String,
    val at: Long,
    val repeatAfter: Long,
) {
    companion object {
        const val KIND_APP = "app"
        const val KIND_CATEGORY = "category"
        const val KIND_DENY = "deny"
    }
}

// @tag:app-allowance
data class AppAllowance(val packageName: String, val until: Long) {
    companion object {
        fun activeUntil(allowances: List<AppAllowance>, packageName: String?, now: Long, trustTime: Boolean): Long? =
            if (!trustTime || packageName == null) null
            else allowances.filter { it.packageName == packageName && it.until > now }.maxOfOrNull { it.until }
    }
}

/**
 * `users.data[].appRules` of docs/specification/protocol-new-ui.md, section 3: [days] bit 0 is Monday,
 * [limitMinutes] -1 means no own limit; [usedMs] is the time of all tablets on [usedDay].
 */
// @tag:app-rule
data class AppRule(val packageName: String, val days: Int, val limitMinutes: Int, val usedDay: Int, val usedMs: Long)

// @tag:child-request @tag:app-allowance @tag:app-rule
object ChildRequestJson {
    private fun nextStringOrEmpty(reader: JsonReader): String =
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()

    private fun parseAnswer(reader: JsonReader): ChildRequestAnswer? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }

        var kind = ""; var until = 0L; var word = ""; var parentUserId = ""; var at = 0L; var repeatAfter = 0L

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "kind" -> kind = reader.nextString()
                "until" -> until = reader.nextLong()
                "word" -> word = nextStringOrEmpty(reader)
                "parentUserId" -> parentUserId = nextStringOrEmpty(reader)
                "at" -> at = reader.nextLong()
                "repeatAfter" -> repeatAfter = reader.nextLong()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return ChildRequestAnswer(kind, until, word, parentUserId, at, repeatAfter)
    }

    fun parseRequests(reader: JsonReader): List<ChildRequest> {
        val result = mutableListOf<ChildRequest>()

        reader.beginArray()
        while (reader.hasNext()) {
            var id = ""; var packageName = ""; var categoryId = ""; var deviceId = ""; var word = ""
            var createdAt = 0L; var expiresAt = 0L; var answer: ChildRequestAnswer? = null; var pending = false

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "packageName" -> packageName = reader.nextString()
                    "categoryId" -> categoryId = nextStringOrEmpty(reader)
                    "deviceId" -> deviceId = nextStringOrEmpty(reader)
                    "word" -> word = nextStringOrEmpty(reader)
                    "createdAt" -> createdAt = reader.nextLong()
                    "expiresAt" -> expiresAt = reader.nextLong()
                    "answer" -> answer = parseAnswer(reader)
                    "pending" -> pending = reader.nextBoolean()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            result.add(ChildRequest(id, packageName, categoryId, deviceId, word, createdAt, expiresAt, answer, pending))
        }
        reader.endArray()

        return result
    }

    fun parseAllowances(reader: JsonReader): List<AppAllowance> {
        val result = mutableListOf<AppAllowance>()

        reader.beginArray()
        while (reader.hasNext()) {
            var packageName = ""; var until = 0L

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "packageName" -> packageName = reader.nextString()
                    "until" -> until = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            result.add(AppAllowance(packageName, until))
        }
        reader.endArray()

        return result
    }

    fun parseRules(reader: JsonReader): List<AppRule> {
        val result = mutableListOf<AppRule>()

        reader.beginArray()
        while (reader.hasNext()) {
            var packageName = ""; var days = 127; var limitMinutes = -1; var usedDay = 0; var usedMs = 0L

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "packageName" -> packageName = reader.nextString()
                    "days" -> days = reader.nextInt()
                    "limitMinutes" -> limitMinutes = reader.nextInt()
                    "usedDay" -> usedDay = reader.nextInt()
                    "usedMs" -> usedMs = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            result.add(AppRule(packageName, days, limitMinutes, usedDay, usedMs))
        }
        reader.endArray()

        return result
    }

    fun serializeRules(rules: List<AppRule>, writer: JsonWriter) {
        writer.beginArray()
        rules.forEach {
            writer.beginObject().name("packageName").value(it.packageName).name("days").value(it.days)
                .name("limitMinutes").value(it.limitMinutes).name("usedDay").value(it.usedDay).name("usedMs").value(it.usedMs)
                .endObject()
        }
        writer.endArray()
    }

    fun serializeRequests(requests: List<ChildRequest>, writer: JsonWriter) {
        writer.beginArray()
        requests.forEach { request ->
            writer.beginObject()
            writer.name("id").value(request.id)
            writer.name("packageName").value(request.packageName)
            writer.name("categoryId").value(request.categoryId)
            writer.name("deviceId").value(request.deviceId)
            writer.name("word").value(request.word)
            writer.name("createdAt").value(request.createdAt)
            writer.name("expiresAt").value(request.expiresAt)
            request.answer?.let { answer ->
                writer.name("answer").beginObject()
                writer.name("kind").value(answer.kind)
                writer.name("until").value(answer.until)
                writer.name("word").value(answer.word)
                writer.name("parentUserId").value(answer.parentUserId)
                writer.name("at").value(answer.at)
                writer.name("repeatAfter").value(answer.repeatAfter)
                writer.endObject()
            }
            if (request.pending) writer.name("pending").value(true)
            writer.endObject()
        }
        writer.endArray()
    }

    fun serializeAllowances(allowances: List<AppAllowance>, writer: JsonWriter) {
        writer.beginArray()
        allowances.forEach { writer.beginObject().name("packageName").value(it.packageName).name("until").value(it.until).endObject() }
        writer.endArray()
    }

    fun write(block: (JsonWriter) -> Unit): String =
        StringWriter().also { out -> JsonWriter(out).use(block) }.toString()
}

class ChildRequestListConverter {
    @TypeConverter
    fun fromString(value: String): List<ChildRequest> = ChildRequestJson.parseRequests(JsonReader(StringReader(value)))

    @TypeConverter
    fun toString(value: List<ChildRequest>): String = ChildRequestJson.write { ChildRequestJson.serializeRequests(value, it) }
}

class AppAllowanceListConverter {
    @TypeConverter
    fun fromString(value: String): List<AppAllowance> = ChildRequestJson.parseAllowances(JsonReader(StringReader(value)))

    @TypeConverter
    fun toString(value: List<AppAllowance>): String = ChildRequestJson.write { ChildRequestJson.serializeAllowances(value, it) }
}

class AppRuleListConverter {
    @TypeConverter
    fun fromString(value: String): List<AppRule> = ChildRequestJson.parseRules(JsonReader(StringReader(value)))

    @TypeConverter
    fun toString(value: List<AppRule>): String = ChildRequestJson.write { ChildRequestJson.serializeRules(value, it) }
}
