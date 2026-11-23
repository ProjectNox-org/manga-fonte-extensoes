package eu.kanade.tachiyomi.multisrc.aurora

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val CHAPTER = "https://reader.example/manga/series/54.2?from=feed"
private const val CHAPTER_PATH = "/manga/series/54.2"
private const val TOKEN = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
private const val START_ROUTE = "/api/gate/start"
private const val GATE_JSON =
    """{"data":{"token":"$TOKEN","returnTo":"$CHAPTER_PATH","minWaitSeconds":0,"gateUrl":null}}"""
private const val CALLBACK_FLIGHT =
    """5:["$","L20",null,{"pages":[{"id":1,"url":"/pages/1","width":800,"height":1200,"isDouble":false}],"capId":"54.2"}]"""

/** What the gate start reports when the site answers with something other than a usable gate payload. */
class GateDiagnosticTest {
    private fun response(
        request: Request,
        code: Int,
        body: String,
        contentType: String = "application/json",
        challenged: Boolean = false,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("stub")
        .apply { if (challenged) header("cf-mitigated", "challenge") }
        .header("Content-Type", contentType)
        .body(body.toResponseBody(null))
        .build()

    private fun unlock(
        chapterUrl: String = CHAPTER,
        waits: MutableList<Duration> = mutableListOf(),
        execute: (Request) -> Response,
    ): Response = runBlocking {
        unlockReader(
            client = OkHttpClient(),
            chapterUrl = chapterUrl,
            headers = Headers.headersOf("User-Agent", "TestAgent"),
            timing = GateTiming(),
            execute = { _, request, _ -> execute(request) },
            wait = { waits += it },
        )
    }

    private fun failure(execute: (Request) -> Response): String = assertThrows(IllegalStateException::class.java) {
        unlock(execute = execute)
    }.message.orEmpty()

    @Test
    fun challengeIsRetriedOnceAndSucceeds() {
        val waits = mutableListOf<Duration>()
        var starts = 0
        val result = unlock(waits = waits) { request ->
            when {
                request.url.encodedPath == START_ROUTE -> {
                    starts++
                    if (starts == 1) {
                        response(request, 403, "<html>Just a moment...</html>", "text/html", challenged = true)
                    } else {
                        response(request, 200, GATE_JSON)
                    }
                }
                else -> response(request, 200, CALLBACK_FLIGHT, "text/x-component")
            }
        }
        assertEquals(200, result.code)
        assertEquals(2, starts)
        assertEquals(1500.milliseconds, waits.first())
    }

    @Test
    fun challengeIsReportedBeforeTheChapterUrl() {
        val message = failure { request ->
            response(request, 403, "<html>Just a moment... token=$TOKEN</html>", "text/html", challenged = true)
        }
        assertTrue(message, message.startsWith("Aurora gate start: HTTP 403 cloudflare-challenge text/html $START_ROUTE $CHAPTER_PATH"))
        assertFalse(message, message.contains(TOKEN))
        assertFalse(message, message.contains("from=feed"))
    }

    @Test
    fun challengeBodyWithoutHeaderIsStillDetected() {
        val message = failure { request ->
            response(request, 503, "<html><script>window.__cf_chl_opt={}</script></html>", "text/html")
        }
        assertTrue(message, "cloudflare-challenge" in message)
    }

    @Test
    fun missingRouteIsLabelled() {
        val message = failure { request -> response(request, 404, """{"error":"not_found"}""") }
        assertTrue(message, "HTTP 404 route-missing" in message)
    }

    @Test
    fun rateLimitIsLabelled() {
        val message = failure { request -> response(request, 429, "") }
        assertTrue(message, "HTTP 429 rate-limited" in message)
    }

    @Test
    fun serverErrorIsLabelled() {
        val message = failure { request -> response(request, 502, "<html>bad gateway</html>", "text/html") }
        assertTrue(message, "HTTP 502 server-error" in message)
    }

    @Test
    fun applicationErrorKeepsTheReason() {
        val message = failure { request -> response(request, 400, """{"error":"invalid_return_to"}""") }
        assertTrue(message, "invalid_return_to" in message)
    }

    @Test
    fun failureLineNeverEchoesTheBody() {
        val message = failure { request ->
            response(request, 500, """{"message":"boom","token":"$TOKEN","cookie":"cf_clearance=secret"}""")
        }
        assertFalse(message, message.contains(TOKEN))
        assertFalse(message, message.contains("secret"))
        assertTrue(message, "server-error" in message)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            // ReaderTest may register Json first; a second registration must not fail the class.
            runCatching { Injekt.addSingleton(Json { ignoreUnknownKeys = true }) }
        }
    }
}
