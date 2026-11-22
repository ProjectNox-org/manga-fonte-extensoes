package eu.kanade.tachiyomi.multisrc.aurora

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReaderCoordinatorTest {

    private val chapterUrl = "https://mugiwarasoficial.org/manga/one-piece/54"
    private val entryUrl = "https://mugiwarasoficial.org/manga/one-piece"
    private val imageUrl = "https://cdn.example/chapter/page?token=a&n=1"
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val payload = "AQAAUOQAAQIDBAUGB5yGwMmTg4dR5RnAFpwcv8iftD41ZjPYAiHJOWVBZ_Z0Ouykdb7_C_UhKkC7"
    private val rotatedKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    private val rotatedPayload = "Av____8AAQIDBAUGB_Y-7qLHJAOdCveehZmix0x5nhv3hdGaVxSOfxJw8UTRN2DPz5-hvC9rlBQs"
    private val flight = "5:[\"$\",\"\$L20\",null,{\"pages\":[{\"id\":1,\"url\":\"$payload\"}],\"capId\":\"54\"}]\n"
    private val rotatedFlight = "5:[\"$\",\"\$L20\",null,{\"pages\":[{\"id\":1,\"url\":\"$rotatedPayload\"}],\"capId\":\"54\"}]\n"

    private val syncExecutor: suspend (OkHttpClient, Request, Boolean) -> Response = { client, req, ensureSuccess ->
        val resp = client.newCall(req).execute()
        if (ensureSuccess && !resp.isSuccessful) {
            resp.close()
            throw HttpException(resp.code)
        }
        resp
    }

    @Test
    fun `unlocks gated chapter by hitting gateUrl without following redirects waiting and decrypting pages`() = runBlocking {
        val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
        val unlocked = AtomicBoolean(false)
        val gateUrlHit = AtomicInteger(0)
        val redirectTargetHit = AtomicInteger(0)
        val keyCalls = AtomicInteger(0)
        val waits = mutableListOf<Duration>()

        val client = createClient { req ->
            val path = req.url.encodedPath
            when {
                req.url.host == "affiliate.example" -> {
                    gateUrlHit.incrementAndGet()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://external-ad.example/landing")
                        .body("".toResponseBody())
                        .build()
                }
                req.url.host == "external-ad.example" -> {
                    redirectTargetHit.incrementAndGet()
                    textResponse(req, 200, "text/html", "should not be followed")
                }
                path == "/manga/one-piece/54" && !unlocked.get() -> {
                    textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                }
                path == "/api/gate/start" -> {
                    assertEquals("POST", req.method)
                    assertEquals("https://mugiwarasoficial.org", req.header("Origin"))
                    assertEquals(chapterUrl, req.header("Referer"))
                    jsonResponse(
                        req,
                        200,
                        """{"data":{"token":"$gateToken","returnTo":"/manga/one-piece/54","minWaitSeconds":10,"gateUrl":"https://affiliate.example/out"}}""",
                    )
                }
                path == "/gate/callback" -> {
                    assertEquals(gateToken, req.url.queryParameter("token"))
                    assertEquals(chapterUrl, req.header("Referer"))
                    unlocked.set(true)
                    textResponse(req, 200, "text/x-component", flight)
                }
                path == "/api/atfield/key" -> {
                    keyCalls.incrementAndGet()
                    assertEquals("1", req.url.queryParameter("v"))
                    assertEquals("20708", req.url.queryParameter("e"))
                    jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val noRedirectClient = client.newBuilder().followRedirects(false).build()

        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { client },
            noRedirectClientProvider = { noRedirectClient },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Mozilla/5.0 Chrome/131.0.0.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            cookieFlusher = {},
            wait = { waits += it },
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageUrls(chapterUrl, entryUrl)
        assertEquals(listOf(imageUrl), pages)
        assertEquals(1, gateUrlHit.get())
        assertEquals(0, redirectTargetHit.get())
        assertEquals(1, keyCalls.get())
        assertEquals(1, waits.size)
        assertTrue(waits.first() >= 9.seconds && waits.first() <= 10.seconds)
    }

    @Test
    fun `falls back to rsc refetch when callback returns empty html and supports gate_disabled unlock`() = runBlocking {
        val unlocked = AtomicBoolean(false)
        val unlockEndpointCalls = AtomicInteger(0)

        val client = createClient { req ->
            when (req.url.encodedPath) {
                "/manga/one-piece/54" -> {
                    if (!unlocked.get()) {
                        textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReaderUnlockClient\"]\n")
                    } else if (req.header("rsc") == "1") {
                        textResponse(req, 200, "text/x-component", flight)
                    } else {
                        textResponse(req, 200, "text/html", "<html><body>unlocked shell</body></html>")
                    }
                }
                "/api/gate/start" -> jsonResponse(req, 400, """{"error":"gate_disabled"}""")
                "/api/reader/unlock" -> {
                    unlockEndpointCalls.incrementAndGet()
                    unlocked.set(true)
                    jsonResponse(req, 200, """{"ok":true}""")
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { client },
            noRedirectClientProvider = { client.newBuilder().followRedirects(false).build() },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Mozilla/5.0 Chrome/131.0.0.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            cookieFlusher = {},
            wait = {},
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageUrls(chapterUrl, entryUrl)
        assertEquals(listOf(imageUrl), pages)
        assertEquals(1, unlockEndpointCalls.get())
    }

    @Test
    fun `handles gate_error cleanly and rotates key when v or e changes`() = runBlocking {
        val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
        val keyCalls = AtomicInteger(0)
        var clockMs = 1_000L

        // 1) Persistent gate_error=expired fails clearly with diagnostic message
        val errorClient = createClient { req ->
            when (req.url.encodedPath) {
                "/manga/one-piece/54" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> jsonResponse(req, 200, """{"data":{"token":"$gateToken","returnTo":"/manga/one-piece/54","minWaitSeconds":0}}""")
                "/gate/callback" -> {
                    val errUrl = "$chapterUrl?gate_error=expired"
                    textResponse(req.newBuilder().url(errUrl).build(), 200, "text/html", "expired")
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val failingCoordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { errorClient },
            noRedirectClientProvider = { errorClient },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            cookieFlusher = {},
            wait = {},
            callExecutor = syncExecutor,
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { failingCoordinator.getPageUrls(chapterUrl, entryUrl) }
        }
        assertTrue(ex.message!!.contains("gate_error=expired"))

        // 2) Key rotation: same (v, e) reuses cached key; changed (v, e) fetches rotated key
        var currentFlight = flight
        val keyClient = createClient { req ->
            when (req.url.encodedPath) {
                "/manga/one-piece/54", "/manga/one-piece/55" -> textResponse(req, 200, "text/x-component", currentFlight)
                "/api/atfield/key" -> {
                    keyCalls.incrementAndGet()
                    val v = req.url.queryParameter("v")!!.toInt()
                    val e = req.url.queryParameter("e")!!.toLong()
                    val k = if (v == 1) key else rotatedKey
                    jsonResponse(req, 200, """{"v":$v,"e":$e,"k":"$k"}""")
                }
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }
        val keyCoordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { keyClient },
            noRedirectClientProvider = { keyClient },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            elapsedSinceProvider = { started -> (clockMs - started).milliseconds },
            markProvider = { clockMs },
            cookieFlusher = {},
            wait = {},
            callExecutor = syncExecutor,
        )

        assertEquals(listOf(imageUrl), keyCoordinator.getPageUrls(chapterUrl, entryUrl))
        // Different chapter with same (v=1, e=20708) reuses key without extra /api/atfield/key call
        assertEquals(listOf(imageUrl), keyCoordinator.getPageUrls("https://mugiwarasoficial.org/manga/one-piece/55", entryUrl))
        assertEquals(1, keyCalls.get())

        // After TTL expires and server rotates to (v=2, e=4294967295), key is refetched
        clockMs += 300_000L
        currentFlight = rotatedFlight
        assertEquals(listOf(imageUrl), keyCoordinator.getPageUrls(chapterUrl, entryUrl))
        assertEquals(2, keyCalls.get())
    }

    @Test
    fun `reuses chapter session within TTL and deduplicates concurrent gate unlocks`() = runBlocking {
        val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
        val unlocked = AtomicBoolean(false)
        val gateStarts = AtomicInteger(0)
        val chapterFetches = AtomicInteger(0)
        var nowMs = 10_000L

        val client = createClient { req ->
            when (req.url.encodedPath) {
                "/manga/one-piece/54" -> {
                    chapterFetches.incrementAndGet()
                    if (!unlocked.get()) {
                        textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                    } else {
                        textResponse(req, 200, "text/x-component", flight)
                    }
                }
                "/api/gate/start" -> {
                    gateStarts.incrementAndGet()
                    jsonResponse(req, 200, """{"data":{"token":"$gateToken","returnTo":"/manga/one-piece/54","minWaitSeconds":0}}""")
                }
                "/gate/callback" -> {
                    unlocked.set(true)
                    textResponse(req, 200, "text/x-component", flight)
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { client },
            noRedirectClientProvider = { client },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            elapsedSinceProvider = { started -> (nowMs - started).milliseconds },
            markProvider = { nowMs },
            cookieFlusher = {},
            wait = { delay(20L) },
            callExecutor = syncExecutor,
        )

        val results = listOf(
            async { coordinator.getPageUrls(chapterUrl, entryUrl) },
            async { coordinator.getPageUrls(chapterUrl, entryUrl) },
        ).awaitAll()

        assertEquals(listOf(imageUrl), results[0])
        assertEquals(listOf(imageUrl), results[1])
        assertEquals(1, gateStarts.get())

        // Re-opening within TTL serves cached URLs without any HTTP calls
        val fetchesBeforeReuse = chapterFetches.get()
        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        assertEquals(fetchesBeforeReuse, chapterFetches.get())
        assertEquals(1, gateStarts.get())
    }

    @Test
    fun `parses single digit chapter dates and alternateName MarkdownDescription`() {
        val mangaJson = """
            {
              "name": "One Piece",
              "image": "https://cdn.example/cover.webp",
              "url": "https://mugiwarasoficial.org/manga/one-piece",
              "alternateName": "Wan Pisu",
              "description": "Piratas em busca do tesouro.",
              "author": {"name": "Eiichiro Oda"},
              "genre": ["Ação", "Aventura"]
            }
        """.trimIndent()
        val manga = mangaJson.parseAs<MangaDto>().toSManga(::fakeManga)
        assertEquals("One Piece", manga.title)
        assertTrue(manga.description!!.contains("Piratas em busca do tesouro."))
        assertTrue(manga.description!!.contains("Wan Pisu"))

        val chapter = ChapterDto(
            id = "cap-5",
            number = "5",
            releaseDate = "5 de nov de 2026",
        ).toSChapter(manga, "one-piece", ::fakeChapter)
        assertTrue(chapter.date_upload > 0L)
        assertEquals("Capítulo 5", chapter.name)

        val dottedChapter = ChapterDto(
            id = "cap-15",
            number = "15",
            releaseDate = "15 de nov. de 2026",
        ).toSChapter(manga, "one-piece", ::fakeChapter)
        assertTrue(dottedChapter.date_upload > chapter.date_upload)
    }

    @Test
    fun `recovers from too_fast in full coordinator flow and remembers increased wait`() = runBlocking {
        val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
        val callbackAttempts = AtomicInteger(0)
        val waits = mutableListOf<Duration>()

        val client = createClient { req ->
            when (req.url.encodedPath) {
                "/manga/one-piece/54", "/manga/one-piece/55" -> textResponse(req, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                "/api/gate/start" -> jsonResponse(req, 200, """{"data":{"token":"$gateToken","minWaitSeconds":0}}""")
                "/gate/callback" -> {
                    val n = callbackAttempts.incrementAndGet()
                    if (n == 1) {
                        textResponse(req.newBuilder().url("$chapterUrl?gate_error=too_fast").build(), 200, "text/html", "too fast")
                    } else {
                        textResponse(req, 200, "text/x-component", flight)
                    }
                }
                "/api/atfield/key" -> jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                else -> textResponse(req, 404, "text/plain", "not found")
            }
        }

        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Mugiwaras Oficial" },
            clientProvider = { client },
            noRedirectClientProvider = { client },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://mugiwarasoficial.org" },
            cookieFlusher = {},
            wait = { waits += it },
            callExecutor = syncExecutor,
        )

        assertEquals(listOf(imageUrl), coordinator.getPageUrls(chapterUrl, entryUrl))
        assertEquals(2, callbackAttempts.get())
        assertEquals(2, waits.size)
        assertTrue(waits[0] >= 9.seconds && waits[0] <= 10.seconds)
        assertTrue(waits[1] >= 14.seconds && waits[1] <= 15.seconds)

        waits.clear()
        assertEquals(listOf(imageUrl), coordinator.getPageUrls("https://mugiwarasoficial.org/manga/one-piece/55", entryUrl))
        assertEquals(3, callbackAttempts.get())
        assertTrue(waits.single() >= 14.seconds && waits.single() <= 15.seconds)
    }

    @Test
    fun `preserves compatibility with domain redirect origin interceptor and external gateUrl`() = runBlocking {
        val gateToken = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
        val unlocked = AtomicBoolean(false)
        val seenGateOrigin = mutableListOf<String?>()
        val seenExternalHosts = mutableListOf<String>()
        val originInterceptor = TestOriginHelper { "https://old.example" }

        val client = OkHttpClient.Builder()
            .addInterceptor(originInterceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                when {
                    req.url.host == "affiliate.example" -> {
                        seenExternalHosts += req.url.host
                        textResponse(req, 200, "text/html", "ok")
                    }
                    req.url.host == "old.example" -> {
                        val redirected = req.newBuilder().url("https://new.example${req.url.encodedPath}").build()
                        textResponse(redirected, 200, "text/x-component", "1:I[5441,[],\"ReadingGateScreen\"]\n")
                            .newBuilder()
                            .priorResponse(
                                Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(301)
                                    .message("Moved")
                                    .header("Location", "https://new.example${req.url.encodedPath}")
                                    .build(),
                            )
                            .build()
                    }
                    req.url.encodedPath == "/manga/one-piece/54" -> {
                        val body = if (unlocked.get()) flight else "1:I[5441,[],\"ReadingGateScreen\"]\n"
                        textResponse(req, 200, "text/x-component", body)
                    }
                    req.url.encodedPath == "/api/gate/start" -> {
                        seenGateOrigin += req.header("Origin")
                        assertEquals("POST", req.method)
                        assertEquals("new.example", req.url.host)
                        jsonResponse(
                            req,
                            200,
                            """{"data":{"token":"$gateToken","returnTo":"/manga/one-piece/54","minWaitSeconds":0,"gateUrl":"https://affiliate.example/go"}}""",
                        )
                    }
                    req.url.encodedPath == "/gate/callback" -> {
                        assertEquals("new.example", req.url.host)
                        unlocked.set(true)
                        textResponse(req, 200, "text/x-component", flight)
                    }
                    req.url.encodedPath == "/api/atfield/key" -> {
                        assertEquals("new.example", req.url.host)
                        jsonResponse(req, 200, """{"v":1,"e":20708,"k":"$key"}""")
                    }
                    else -> textResponse(req, 404, "text/plain", "not found")
                }
            }
            .build()

        val coordinator = AuroraReaderCoordinator(
            sourceNameProvider = { "Manga NXY" },
            clientProvider = { client },
            noRedirectClientProvider = { client.newBuilder().followRedirects(false).build() },
            baseHeadersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://old.example" },
            cookieFlusher = {},
            wait = {},
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageUrls("https://old.example/manga/one-piece/54", "https://old.example/manga/one-piece")
        assertEquals(listOf(imageUrl), pages)
        assertEquals(listOf("https://new.example"), seenGateOrigin)
        assertEquals(listOf("affiliate.example"), seenExternalHosts)
    }

    private fun createClient(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> handler(chain.request()) }
        .build()

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

    private fun fakeManga(): SManga = createPropertyBagProxy(SManga::class.java)

    private fun fakeChapter(): SChapter = createPropertyBagProxy(SChapter::class.java).apply {
        memo = buildJsonObject {}
    }

    private fun <T> createPropertyBagProxy(iface: Class<T>): T {
        val values = HashMap<String, Any?>()
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("set") && args != null && args.size == 1 -> {
                    values[name.removePrefix("set").lowercase()] = args[0]
                    null
                }
                name.startsWith("get") && (args == null || args.isEmpty()) -> {
                    val key = name.removePrefix("get").lowercase()
                    values[key] ?: defaultPrimitive(method.returnType)
                }
                else -> defaultPrimitive(method.returnType)
            }
        } as T
    }

    private fun defaultPrimitive(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Boolean::class.javaPrimitiveType -> false
        else -> null
    }

    private class TestOriginHelper(private val baseUrl: () -> String) : Interceptor {
        private val origins = ConcurrentHashMap<HttpUrl, HttpUrl>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val base = baseUrl().toHttpUrl().origin()
            val target = origins[base] ?: base
            val original = chain.request()
            if (original.url.origin() != base && original.url.origin() != target) return chain.proceed(original)

            val request = original.newBuilder().url(original.url.onOrigin(target))
            original.header("Referer")?.toHttpUrlOrNull()?.let { referer ->
                if (referer.origin() == base || referer.origin() == target) {
                    request.header("Referer", referer.onOrigin(target).toString())
                }
            }
            if (original.method == "POST" && (original.url.encodedPath == "/api/gate/start" || original.url.encodedPath == "/api/reader/unlock")) {
                request.header("Origin", target.toString().removeSuffix("/"))
            }
            val response = chain.proceed(request.build())
            val finalUrl = response.request.url
            val contentType = response.header("Content-Type").orEmpty()
            if (original.method == "GET" && response.isSuccessful &&
                finalUrl.encodedPath == original.url.encodedPath &&
                ("text/html" in contentType || "text/x-component" in contentType) &&
                (target.scheme != "https" || finalUrl.scheme == "https")
            ) {
                origins[base] = finalUrl.origin()
            }
            return response
        }

        private fun HttpUrl.origin(): HttpUrl = newBuilder().encodedPath("/").query(null).fragment(null).build()

        private fun HttpUrl.onOrigin(origin: HttpUrl): HttpUrl = newBuilder()
            .scheme(origin.scheme).host(origin.host).port(origin.port).build()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
