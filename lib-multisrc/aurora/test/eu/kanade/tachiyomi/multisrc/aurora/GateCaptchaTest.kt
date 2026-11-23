package eu.kanade.tachiyomi.multisrc.aurora

import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Issue #180: the app (not the Cloudflare edge) answers `captcha_required` on the gate start. */
class GateCaptchaTest {

    private val chapterUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha/114"
    private val nextChapterUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha/115"
    private val entryUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha"
    private val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
    private val imageUrl = "https://cdn.example/chapter/page?token=a&n=1"
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val payload = "AQAAUOQAAQIDBAUGB5yGwMmTg4dR5RnAFpwcv8iftD41ZjPYAiHJOWVBZ_Z0Ouykdb7_C_UhKkC7"
    private val flight = "5:[\"$\",\"\$L20\",null,{\"pages\":[{\"id\":1,\"url\":\"$payload\"}],\"capId\":\"114\"}]\n"
    private val captchaJson = """{"error":{"code":"captcha_required","message":"Verificacao humana necessaria"}}"""

    private val syncExecutor: suspend (OkHttpClient, Request, Boolean) -> Response = { client, req, _ ->
        client.newCall(req).execute()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun registerJson() {
            runCatching { Injekt.addSingleton(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `solves the human check once and retries the gate start with the token`() = runBlocking {
        val solves = AtomicInteger(0)
        val solvedUrls = mutableListOf<String>()
        val startBodies = mutableListOf<String>()
        var startCalls = 0
        val unlocked = AtomicBoolean(false)

        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> if (unlocked.get()) {
                    textResponse(req, 200, "text/x-component", flight)
                } else {
                    textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                }
                "/api/gate/start" -> {
                    startCalls++
                    startBodies.add(req.bodyText())
                    if (startCalls == 1) {
                        jsonResponse(req, 403, captchaJson)
                    } else {
                        jsonResponse(req, 200, gateOk(minWaitSeconds = 15, method = "direct"))
                    }
                }
                "/gate/callback" -> {
                    assertEquals(gateToken, req.url.queryParameter("token"))
                    unlocked.set(true)
                    textResponse(req, 200, "text/x-component", flight)
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val pages = coordinator(client) {
            solves.incrementAndGet()
            solvedUrls.add(it)
            "turnstile-token-1"
        }.getPageUrls(chapterUrl, entryUrl)

        assertEquals(listOf(imageUrl), pages)
        assertEquals(1, solves.get())
        assertEquals(listOf(chapterUrl), solvedUrls)
        assertEquals(listOf("""{"returnTo":"/manga/tate-no-yuusha/114"}""", """{"returnTo":"/manga/tate-no-yuusha/114","captchaToken":"turnstile-token-1"}"""), startBodies)
    }

    @Test
    fun `rejected captcha token fails with the application reason instead of looping`() = runBlocking {
        val solves = AtomicInteger(0)
        var startCalls = 0
        val failedJson = """{"error":{"code":"captcha_failed","message":"Verificacao invalida"}}"""

        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> {
                    startCalls++
                    jsonResponse(req, 403, if (startCalls == 1) captchaJson else failedJson)
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator(client) { solves.incrementAndGet(); "stale" }.getPageUrls(chapterUrl, entryUrl) }
        }
        assertTrue(ex.message!!.contains("HTTP 403 captcha-required application/json /api/gate/start"))
        assertEquals(2, startCalls)
        assertEquals(1, solves.get())
    }

    @Test
    fun `captcha without a configured solver keeps the application rejection distinguishable`() = runBlocking {
        var startCalls = 0
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> {
                    startCalls++
                    jsonResponse(req, 403, captchaJson)
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator(client, solver = null).getPageUrls(chapterUrl, entryUrl) }
        }
        assertTrue(ex.message!!.contains("HTTP 403 captcha-required application/json"))
        assertFalse(ex.message!!.contains("cloudflare-challenge"))
        assertEquals(1, startCalls)
    }

    @Test
    fun `edge challenge and application captcha are told apart`() {
        val edgeBody = "<html><head><title>Just a moment...</title><script src=\"/cdn-cgi/challenge-platform/x\"></script></head></html>"
        assertTrue(looksLikeChallenge(edgeBody))
        assertFalse(looksLikeChallenge(captchaJson))
        assertEquals("captcha_required", gateErrorReason(captchaJson))
    }

    @Test
    fun `concurrent chapter opens share a single human check`() = runBlocking {
        val solves = AtomicInteger(0)
        val unlocked = AtomicBoolean(false)
        var startCalls = 0

        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> if (unlocked.get()) {
                    textResponse(req, 200, "text/x-component", flight)
                } else {
                    textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                }
                "/api/gate/start" -> {
                    startCalls++
                    if (req.bodyText().contains("captchaToken")) {
                        jsonResponse(req, 200, gateOk(minWaitSeconds = 10, method = "direct"))
                    } else {
                        jsonResponse(req, 403, captchaJson)
                    }
                }
                "/gate/callback" -> {
                    unlocked.set(true)
                    textResponse(req, 200, "text/x-component", flight)
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val shared = coordinator(client) { solves.incrementAndGet(); "concurrent-token" }

        val results = listOf(
            async { shared.getPageUrls(chapterUrl, entryUrl) },
            async { shared.getPageUrls(chapterUrl, entryUrl) },
        ).awaitAll()

        assertEquals(listOf(listOf(imageUrl), listOf(imageUrl)), results)
        assertEquals(1, solves.get())
        assertEquals(2, startCalls)
    }

    @Test
    fun `authorized session opens the next chapter without another human check`() = runBlocking {
        val solves = AtomicInteger(0)
        val startBodies = mutableListOf<String>()
        val fetches = mutableMapOf<String, Int>()
        val captchaPassed = AtomicBoolean(false)

        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114", "/manga/tate-no-yuusha/115" -> {
                    // The coordinator probes the chapter and re-checks it before unlocking.
                    if (fetches.merge(req.url.encodedPath, 1, Int::plus)!! <= 2) {
                        textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                    } else {
                        textResponse(req, 200, "text/x-component", flight)
                    }
                }
                "/api/gate/start" -> {
                    val body = req.bodyText()
                    startBodies.add(body)
                    if (body.contains("captchaToken")) {
                        captchaPassed.set(true)
                        jsonResponse(req, 200, gateOk(10, "direct", returnToOf(body)))
                    } else if (captchaPassed.get()) {
                        jsonResponse(req, 200, gateOk(10, "direct", returnToOf(body)))
                    } else {
                        jsonResponse(req, 403, captchaJson)
                    }
                }
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val shared = coordinator(client) { solves.incrementAndGet(); "session-token" }
        assertEquals(listOf(imageUrl), shared.getPageUrls(chapterUrl, entryUrl))
        assertEquals(listOf(imageUrl), shared.getPageUrls(nextChapterUrl, entryUrl))
        assertEquals(1, solves.get())
        assertTrue(startBodies.last().contains("/manga/tate-no-yuusha/115"))
        assertFalse(startBodies.last().contains("captchaToken"))
    }

    @Test
    fun `partner gate method skips the local countdown`() = runBlocking {
        val waits = mutableListOf<Duration>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> jsonResponse(req, 200, gateOk(minWaitSeconds = 0, method = "bcm"))
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        assertEquals(listOf(imageUrl), coordinator(client, wait = { waits.add(it) }).getPageUrls(chapterUrl, entryUrl))
        assertTrue("waits=$waits", waits.all { it == Duration.ZERO })
    }

    @Test
    fun `absent minimum wait falls back to the site default`() = runBlocking {
        val waits = mutableListOf<Duration>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> jsonResponse(req, 200, """{"data":{"token":"$gateToken","method":"direct"}}""")
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        assertEquals(listOf(imageUrl), coordinator(client, wait = { waits.add(it) }).getPageUrls(chapterUrl, entryUrl))
        assertEquals(1, waits.size)
        assertTrue(waits.single() > 14.seconds && waits.single() <= 15.seconds)
    }

    private fun gateOk(
        minWaitSeconds: Int,
        method: String,
        returnTo: String = "/manga/tate-no-yuusha/114",
    ) = """{"data":{"token":"$gateToken","returnTo":"$returnTo","minWaitSeconds":$minWaitSeconds,"method":"$method"}}"""

    private fun returnToOf(body: String): String = body.substringAfter("\"returnTo\":\"").substringBefore("\"")

    private fun coordinator(
        client: OkHttpClient,
        wait: suspend (Duration) -> Unit = {},
        // Last so a trailing lambda is the captcha solver rather than the wait.
        solver: (suspend (String) -> String)? = { "unused" },
    ): AuroraReaderCoordinator = AuroraReaderCoordinator(
        sourceNameProvider = { "Mugiwaras Oficial" },
        clientProvider = { client },
        noRedirectClientProvider = { client.newBuilder().followRedirects(false).build() },
        baseHeadersProvider = { Headers.headersOf("User-Agent", "Mozilla/5.0 Chrome/131.0.0.0") },
        baseUrlProvider = { "https://mugiwarasoficial.org" },
        cookieFlusher = {},
        wait = wait,
        callExecutor = syncExecutor,
        captchaSolver = solver,
    )

    private fun client(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
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
