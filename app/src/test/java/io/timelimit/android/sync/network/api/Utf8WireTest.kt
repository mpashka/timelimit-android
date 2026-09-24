package io.timelimit.android.sync.network.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8WireTest {
    private val json = """{"type":"UPDATE_CATEGORY_TITLE","categoryId":"gAm3s1","newTitle":"Игры 🎮"}"""

    @Test
    fun cyrillicAndEmojiSurviveTheRequestAndTheResponse() {
        val wire = Buffer()
        HttpServerApi.gzipUtf8Writer(wire).use { it.write(json) }
        assertEquals(json, GzipSource(wire).buffer().readUtf8())

        val response = json.encodeToByteArray().toResponseBody("application/json".toMediaType())
        assertEquals(json, response.charStream().readText())
    }
}
