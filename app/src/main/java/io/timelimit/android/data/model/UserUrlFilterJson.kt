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
package io.timelimit.android.data.model

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.TypeConverter
import io.timelimit.android.util.parseJsonStringArray
import java.io.StringReader
import java.io.StringWriter

// @tag:url-filter
object UserUrlFilterJson {
    private const val ENABLED = "enabled"
    private const val ALLOW = "allow"
    private const val BLOCK = "block"

    fun parse(reader: JsonReader): UserUrlFilter {
        var enabled: Boolean? = null
        var allow: List<String>? = null
        var block: List<String>? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                ENABLED -> enabled = reader.nextBoolean()
                ALLOW -> allow = parseJsonStringArray(reader)
                BLOCK -> block = parseJsonStringArray(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return UserUrlFilter(enabled = enabled!!, allow = allow!!, block = block!!)
    }

    fun parseNullable(reader: JsonReader): UserUrlFilter? =
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else parse(reader)

    fun serializeFields(filter: UserUrlFilter, writer: JsonWriter) {
        writer.name(ENABLED).value(filter.enabled)
        writer.name(ALLOW).beginArray().also { filter.allow.forEach { writer.value(it) } }.endArray()
        writer.name(BLOCK).beginArray().also { filter.block.forEach { writer.value(it) } }.endArray()
    }

    fun serialize(filter: UserUrlFilter, writer: JsonWriter) {
        writer.beginObject()
        serializeFields(filter, writer)
        writer.endObject()
    }
}

class UserUrlFilterConverter {
    @TypeConverter
    fun fromString(value: String?): UserUrlFilter? = value?.let { UserUrlFilterJson.parse(JsonReader(StringReader(it))) }

    @TypeConverter
    fun toString(value: UserUrlFilter?): String? = value?.let {
        StringWriter().also { out -> JsonWriter(out).use { writer -> UserUrlFilterJson.serialize(value, writer) } }.toString()
    }
}
