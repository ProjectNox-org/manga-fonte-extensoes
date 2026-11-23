package eu.kanade.tachiyomi.multisrc.aurora

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val CHAPTER_URL = "https://reader.example/manga/series/54.2"
private const val CHAPTER_PATH = "/manga/series/54.2"
private const val CAPTCHA_JSON = """{"error":{"code":"captcha_required","message":"Verificacao humana necessaria"}}"""
private const val FLIGHT = "5:[\"$\",\"L20\",null,{\"pages\":[{\"id\":1,\"url\":\"/pages/1\"}],\"capId\":\"54.2\"}]"

/** NXY-style site where gate/start keeps rejecting the token and only the unlock route accepts it. */
class ReaderUnlockTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun registerJson() {
            runCatching { Injekt.addSingleton(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun unlock(
        client: OkHttpClient,
        solver: (suspend (String) -> String)?,
        wait: suspend (Duration) -> Unit = {},
    ): Response = runBlocking {
        unlockReader(
            client = client,
            chapterUrl = CHAPTER_URL,
            headers = Headers.headersOf("User-Agent", "TestAgent"),
            timing = GateTiming(),
            captchaUnlocksReader = true,
            captchaSolver = solver,
            wait = wait,
            execute = { c, req, _ -> c.newCall(req).execute() },
        )
    }

    @Test
    fun `rejected start retries with the token before falling back to the unlock route`() {
        val seen = mutableListOf<String>()
        val startBodies = mutableListOf<String>()
        val unlockBodies = mutableListOf<String>()
        val client = client { req ->
            seen += "${req.method} ${req.url.encodedPath}"
            when (req.url.encodedPath) {
                "/api/gate/start" -> {
                    startBodies += req.bodyText()
                    jsonResponse(req, 403, CAPTCHA_JSON)
                }
                "/api/reader/unlock" -> {
                    unlockBodies += req.bodyText()
                    jsonResponse(req, 200, "")
                }
                CHAPTER_PATH -> textResponse(req, 200, "text/x-component", FLIGHT)
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        unlock(client, { "turnstile-1" }).use {
            assertEquals(CHAPTER_URL, it.request.url.toString())
            assertEquals("GET", it.request.method)
            assertEquals(FLIGHT, it.body.string())
        }
        // The gate start gets the token first: its 200 is what authorises the reader key, so the unlock is only the
        // fallback when the start still refuses it.
        assertEquals(
            listOf(
                """{"returnTo":"$CHAPTER_PATH"}""",
                """{"returnTo":"$CHAPTER_PATH","captchaToken":"turnstile-1"}""",
            ),
            startBodies,
        )
        assertEquals(listOf("""{"returnTo":"$CHAPTER_PATH","token":"turnstile-1"}"""), unlockBodies)
        assertEquals(
            listOf(
                "POST /api/gate/start",
                "GET $CHAPTER_PATH",
                "POST /api/gate/start",
                "POST /api/reader/unlock",
                "GET $CHAPTER_PATH",
            ),
            seen,
        )
    }

    @Test
    fun `too_fast on unlock waits the declared minimum and retries once`() {
        val waits = mutableListOf<Duration>()
        var unlockCalls = 0
        val client = client { req ->
            when (req.url.encodedPath) {
                "/api/gate/start" -> jsonResponse(req, 403, CAPTCHA_JSON)
                "/api/reader/unlock" -> {
                    unlockCalls++
                    if (unlockCalls == 1) {
                        jsonResponse(req, 429, """{"error":{"code":"too_fast"},"minWaitSeconds":3}""")
                    } else {
                        jsonResponse(req, 200, "")
                    }
                }
                CHAPTER_PATH -> textResponse(req, 200, "text/x-component", FLIGHT)
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        unlock(client, { "turnstile-1" }, wait = { waits += it }).use {
            assertEquals(CHAPTER_URL, it.request.url.toString())
        }
        assertEquals(listOf(3.seconds), waits)
        assertEquals(2, unlockCalls)
    }

    @Test
    fun `captcha without a solver still fails at the gate start`() {
        var unlockCalls = 0
        val client = client { req ->
            when (req.url.encodedPath) {
                "/api/gate/start" -> jsonResponse(req, 403, CAPTCHA_JSON)
                "/api/reader/unlock" -> {
                    unlockCalls++
                    jsonResponse(req, 200, "")
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val error = assertThrows(IllegalStateException::class.java) {
            unlock(client, solver = null).close()
        }
        assertTrue(error.message!!.contains("Aurora gate start: HTTP 403 captcha-required application/json"))
        assertEquals(0, unlockCalls)
    }


    @Test
    fun `reader key failure names the host and the cookies the jar sent`() = runBlocking {
        val keyUrl = "https://manganyx.com/api/atfield/key".toHttpUrl()
        val cookie = Cookie.parse(keyUrl, "mnx_reader_human=abc")!!
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(cookie)
        }
        val keyClient = OkHttpClient.Builder().cookieJar(jar).build()
        val lines = mutableListOf<String>()
        var keyRequest: Request? = null
        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Manga NXY" },
            captchaUnlocksReader = true,
            clientProvider = { keyClient },
            noRedirectClientProvider = { keyClient },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://manganyx.org" },
            diagnostic = { lines += it },
            cookieFlusher = {},
            wait = {},
            callExecutor = { _, req, _ ->
                keyRequest = req
                jsonResponse(req, 403, """{"error":"unauthorized"}""")
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.getKey(1 to 20779, "https://manganyx.org/manga/gantz/383") }
        }
        val message = error.message!!
        // Body first so Mihon cannot truncate the reason; host and cookie names are never tokens.
        assertTrue(message.startsWith("""{"error":"unauthorized"}"""))
        assertTrue(message.contains("manganyx.org/api/atfield/key"))
        assertTrue(message.contains("cookies=[mnx_reader_human]"))
        assertTrue(message.contains("chapter=/manga/gantz/383"))
        // The same timeline goes to Logcat so Mihon's truncated toast is not the only source.
        assertTrue(lines.last().startsWith("key host=manganyx.org status=403 ua=Mozilla/5.0"))
        assertTrue(lines.last().contains("secfetch=same-origin"))
        assertTrue(lines.last().endsWith("cookies=[mnx_reader_human]"))
        // Proven on the site: no Referer means 403 unauthorized for the very same cookies.
        assertEquals("https://manganyx.org/manga/gantz/383", keyRequest!!.header("Referer"))
    }

    private fun client(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> handler(chain.request()) }
        .build()

    private fun Request.bodyText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()

    private fun jsonResponse(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .header("Content-Type", "application/json; charset=utf-8")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun textResponse(request: Request, code: Int, contentType: String, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .header("Content-Type", contentType)
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()
}
