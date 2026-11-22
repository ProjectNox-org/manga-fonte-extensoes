package eu.kanade.tachiyomi.multisrc.aurora

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Issue #180 follow-up: where the seconds of a chapter open actually go. */
class GatePerformanceTest {

    private val chapterUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha/114"
    private val nextChapterUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha/115"
    private val entryUrl = "https://mugiwarasoficial.org/manga/tate-no-yuusha"
    private val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
    private val imageUrl = "https://cdn.example/chapter/page?token=a&n=1"
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val payload = "AQAAUOQAAQIDBAUGB5yGwMmTg4dR5RnAFpwcv8iftD41ZjPYAiHJOWVBZ_Z0Ouykdb7_C_UhKkC7"
    private val flight = "5:[\"$\",\"\$L20\",null,{\"pages\":[{\"id\":1,\"url\":\"$payload\"}],\"capId\":\"114\"}]\n"
    private val gateScreen = "1:I[5441,[],\"ReadingGateScreen\"]\n"

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
    fun `a warmed chapter open makes no request at all`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = client { req ->
            calls.incrementAndGet()
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val coordinator = coordinator(client)

        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        val afterFirstOpen = calls.get()
        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        assertEquals(afterFirstOpen, calls.get())
        assertEquals(mapOf("cache" to Duration.ZERO), coordinator.lastPhases)
    }

    @Test
    fun `a gated chapter is probed once and not re-checked when nobody unlocked before`() = runBlocking {
        val chapterHits = AtomicInteger(0)
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> {
                    chapterHits.incrementAndGet()
                    textResponse(req, 200, "text/x-component", gateScreen)
                }
                "/api/gate/start" -> jsonResponse(req, 200, gateOk(0, "direct"))
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        assertEquals(listOf(imageUrl), coordinator(client).getPageUrls(chapterUrl, entryUrl))
        // One probe before the gate; the old unconditional re-check would make it two.
        assertEquals(1, chapterHits.get())
    }

    @Test
    fun `the gate link is fetched while the countdown runs and before the callback`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val client = client { req ->
            when {
                req.url.host == "affiliate.example" -> {
                    order.add("link")
                    textResponse(req, 200, "text/html", "ok")
                }
                req.url.encodedPath == "/manga/tate-no-yuusha/114" ->
                    textResponse(req, 200, "text/x-component", gateScreen)
                req.url.encodedPath == "/api/gate/start" ->
                    jsonResponse(req, 200, gateOk(15, "direct", gateUrl = "https://affiliate.example/out"))
                req.url.encodedPath == "/gate/callback" -> {
                    order.add("callback")
                    textResponse(req, 200, "text/x-component", flight)
                }
                req.url.encodedPath == "/api/atfield/key" ->
                    jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val waits = mutableListOf<Duration>()
        val coordinator = coordinator(client, wait = { waits.add(it); kotlinx.coroutines.yield() })
        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))

        assertEquals(listOf("link", "callback"), order)
        assertTrue(waits.single() > 14.seconds && waits.single() <= 15.seconds)
    }

    @Test
    fun `the countdown matches the declared minimum`() = runBlocking {
        val waits = mutableListOf<Duration>()
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", gateScreen)
                "/api/gate/start" -> jsonResponse(req, 200, gateOk(20, "direct"))
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val coordinator = coordinator(client, wait = { waits.add(it) })
        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        assertTrue(waits.single() > 19.seconds && waits.single() <= 20.seconds)
    }

    @Test
    fun `consecutive chapters reuse the gate session and solve the captcha once`() = runBlocking {
        val solves = AtomicInteger(0)
        val startCalls = AtomicInteger(0)
        val captchaPassed = AtomicBoolean(false)
        val unlocked = Collections.synchronizedSet(mutableSetOf<String>())
        var currentChapter = "/manga/tate-no-yuusha/114"

        val client = client { req ->
            val path = req.url.encodedPath
            when {
                path == "/manga/tate-no-yuusha/114" || path == "/manga/tate-no-yuusha/115" ->
                    if (path in unlocked) {
                        textResponse(req, 200, "text/x-component", flight)
                    } else {
                        textResponse(req, 200, "text/x-component", gateScreen)
                    }
                path == "/api/gate/start" -> {
                    startCalls.incrementAndGet()
                    val body = req.bodyText()
                    currentChapter = returnToOf(body)
                    if (body.contains("captchaToken") || captchaPassed.get()) {
                        captchaPassed.set(true)
                        jsonResponse(req, 200, gateOk(0, "direct", returnTo = returnToOf(body)))
                    } else {
                        jsonResponse(req, 403, """{"error":{"code":"captcha_required"}}""")
                    }
                }
                path == "/gate/callback" -> {
                    unlocked.add(currentChapter)
                    textResponse(req, 200, "text/x-component", flight)
                }
                path == "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val coordinator = coordinator(client, solver = { solves.incrementAndGet(); "token" })

        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        assertEquals(listOf(imageUrl), coordinator.getPageUrls(nextChapterUrl, entryUrl))
        assertEquals(1, solves.get())
        // First chapter: captcha probe + retry. Second: one probe, no captcha.
        assertEquals(3, startCalls.get())
    }

    @Test
    fun `concurrent opens of one chapter run a single gate`() = runBlocking {
        val starts = AtomicInteger(0)
        val unlocked = AtomicBoolean(false)
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" ->
                    if (unlocked.get()) {
                        textResponse(req, 200, "text/x-component", flight)
                    } else {
                        textResponse(req, 200, "text/x-component", gateScreen)
                    }
                "/api/gate/start" -> {
                    starts.incrementAndGet()
                    jsonResponse(req, 200, gateOk(0, "direct"))
                }
                "/gate/callback" -> {
                    unlocked.set(true)
                    textResponse(req, 200, "text/x-component", flight)
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val coordinator = coordinator(client)

        val results = listOf(
            async { coordinator.getPageUrls(chapterUrl, entryUrl) },
            async { coordinator.getPageUrls(chapterUrl, entryUrl) },
        ).awaitAll()

        assertEquals(listOf(listOf(imageUrl), listOf(imageUrl)), results)
        assertEquals(1, starts.get())
    }

    @Test
    fun `phase clock records the pipeline without leaking the url`() = runBlocking {
        val client = client { req ->
            when (req.url.encodedPath) {
                "/manga/tate-no-yuusha/114" -> textResponse(req, 200, "text/x-component", gateScreen)
                "/api/gate/start" -> jsonResponse(req, 200, gateOk(0, "direct"))
                "/gate/callback" -> textResponse(req, 200, "text/x-component", flight)
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val coordinator = coordinator(client)
        coordinator.getPageUrls(chapterUrl, entryUrl)

        val phases = coordinator.lastPhases
        assertTrue(phases.keys.containsAll(listOf("probe", "gate-start", "gate-start-ok", "gate-issued", "gate-callback", "unlocked", "decoded")))
        assertTrue(phases.values.all { !it.isNegative() })
        assertTrue(phases.getValue("decoded") >= phases.getValue("probe"))
        assertFalse(phases.toString().contains("token"))
        assertFalse(phases.toString().contains("mugiwarasoficial"))
    }

    private fun gateOk(
        minWaitSeconds: Int,
        method: String,
        gateUrl: String? = null,
        returnTo: String = "/manga/tate-no-yuusha/114",
    ): String {
        val gate = gateUrl?.let { ""","gateUrl":"$it"""" }.orEmpty()
        return """{"data":{"token":"$gateToken","returnTo":"$returnTo","minWaitSeconds":$minWaitSeconds,"method":"$method"$gate}}"""
    }

    private fun returnToOf(body: String): String = body.substringAfter("\"returnTo\":\"").substringBefore("\"")

    private fun coordinator(
        client: OkHttpClient,
        wait: suspend (Duration) -> Unit = {},
        // Last so a trailing lambda is the captcha solver rather than the wait.
        solver: (suspend (String) -> String)? = { "unused" },
    ): AuroraReaderCoordinator {
        var clockMs = 0L
        return AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { client },
            noRedirectClientProvider = { client.newBuilder().followRedirects(false).build() },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Mozilla/5.0 Chrome/131.0.0.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            elapsedSinceProvider = { started -> (clockMs - started).milliseconds },
            markProvider = { clockMs },
            cookieFlusher = {},
            wait = wait,
            callExecutor = syncExecutor,
            captchaSolver = solver,
        )
    }

    private fun client(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
        .build()

    private fun Request.bodyText(): String = okio.Buffer().also { body?.writeTo(it) }.readUtf8()

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
