package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class XXXYaoiRegressionTest {

    private fun testChapter(url: String, memo: JsonObject = buildJsonObject {}): SChapter = object : SChapter {
        override var url: String = url
        override var name: String = "Cap"
        override var date_upload: Long = 0L
        override var chapter_number: Float = 1f
        override var scanlator: String? = null
        override var memo: JsonObject = memo
    }

    @Test fun resolvesMemoAndLegacyChapterUrls() {
        val withMemo = testChapter(
            url = "cap-01",
            memo = buildJsonObject { put("mangaPath", "/bl/minha-obra/") },
        )
        assertEquals("https://3xyaoi.com/bl/minha-obra/cap-01/", resolveChapterUrl("https://3xyaoi.com", withMemo))

        val legacyPath = testChapter(url = "/bl/minha-obra/cap-01/")
        assertEquals("https://3xyaoi.com/bl/minha-obra/cap-01/", resolveChapterUrl("https://3xyaoi.com/", legacyPath))

        val legacyAbsolute = testChapter(url = "https://3xyaoi.com/bl/minha-obra/cap-01/")
        assertEquals("https://3xyaoi.com/bl/minha-obra/cap-01/", resolveChapterUrl("https://3xyaoi.com", legacyAbsolute))

        val missingMemo = testChapter(url = "cap-01")
        assertThrows(IOException::class.java) {
            resolveChapterUrl("https://3xyaoi.com", missingMemo)
        }
    }

    @Test fun mergesSiteHeadersWithoutDroppingValidation() {
        val request = buildImageRequest(
            Page(0, "https://3xyaoi.com/bl/obra/cap-1/#%7B%7D", "https://3xyaoi.com/WP-manga/data/1.webp"),
            "UA",
            mapOf("X-Custom-Token" to "abc123"),
        )
        assertEquals("abc123", request.header("X-Custom-Token"))
        assertEquals("image/*", request.header("Accept"))
    }

    @Test fun parsesPageReadAndAssetsExist() {
        val decoded = Json.decodeFromString<PageRead>(
            """{"check":false,"status":200,"result":{"url":"https://3xyaoi.com/bl/a/1/","html":"<html></html>","pages":["https://3xyaoi.com/WP-manga/data/1.jpg"]}}""",
        )
        assertEquals(1, decoded.result?.pages?.size)
        for (asset in listOf("library.js", "loaded.js", "page.js", "reader.js", "session.js", "world.js")) {
            val file = File("assets/$asset")
            assertTrue("Missing asset $asset at ${file.absolutePath}", file.isFile && file.length() > 0)
        }
    }
}
