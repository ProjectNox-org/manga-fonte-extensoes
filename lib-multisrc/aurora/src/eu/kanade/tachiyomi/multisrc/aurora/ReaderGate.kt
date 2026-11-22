package eu.kanade.tachiyomi.multisrc.aurora

import android.webkit.CookieManager
import keiyoushi.network.Fetch
import keiyoushi.network.call
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
internal class GateRequest(val returnTo: String)

@Serializable
internal class GateResponse(val data: GateData)

@Serializable
internal class GateData(
    val token: String,
    val returnTo: String? = null,
    val minWaitSeconds: Int = 0,
    val gateUrl: String? = null,
) {
    fun callback(chapterUrl: String): HttpUrl {
        val chapter = chapterUrl.toHttpUrl()
        require((returnTo == null || returnTo == chapter.encodedPath) && temporaryToken.matches(token)) {
            "Aurora reader: invalid chapter gate"
        }
        return chapter.newBuilder().encodedPath("/gate/callback").query(null).fragment(null)
            .addQueryParameter("token", token).build()
    }

    fun validatedGateUrl(): HttpUrl? = gateUrl
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.toHttpUrlOrNull()
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }

    companion object {
        private val temporaryToken = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}

internal class GateTiming {
    @Volatile
    private var minimumSeconds = 8

    suspend fun complete(
        serverMinimum: Int,
        elapsed: () -> Duration,
        wait: suspend (Duration) -> Unit = { delay(it) },
        request: suspend () -> Response,
    ): Response {
        // The direct gate reports 8s; the partner variant reports 0 but accepted 10s live.
        val initial = maxOf(minimumSeconds, serverMinimum.takeIf { it > 0 } ?: 10)
        wait((initial.seconds - elapsed()).coerceAtLeast(Duration.ZERO))
        val response = request()
        if (response.request.url.queryParameter("gate_error") != "too_fast") return response

        response.close()
        // Remember the refusal for this source instance instead of probing every chapter.
        minimumSeconds = 15
        wait((maxOf(15, initial + 5).seconds - elapsed()).coerceAtLeast(Duration.ZERO))
        return request()
    }
}

internal fun buildGateStartHeaders(headers: Headers, baseUrl: String, chapterUrl: String): Headers = headers.newBuilder()
    .set("Origin", baseUrl)
    .set("Referer", chapterUrl)
    .set("Sec-Fetch-Mode", "cors")
    .set("Sec-Fetch-Dest", "empty")
    .set("Sec-Fetch-Site", "same-origin")
    .build()

internal fun buildGateNavigationHeaders(headers: Headers, chapterUrl: String): Headers = headers.newBuilder()
    .set("Referer", chapterUrl)
    .set("Sec-Fetch-Mode", "navigate")
    .set("Sec-Fetch-Dest", "document")
    .set("Sec-Fetch-Site", "same-origin")
    .build()

internal suspend fun unlockReader(
    client: OkHttpClient,
    chapterUrl: String,
    headers: Headers,
    timing: GateTiming,
    noRedirectClient: OkHttpClient = client.newBuilder().followRedirects(false).build(),
    execute: suspend (OkHttpClient, Request, Boolean) -> Response = { c, req, ensureSuccess ->
        c.call(req, ensureSuccess)
    },
    wait: suspend (Duration) -> Unit = { delay(it) },
): Response {
    val chapter = chapterUrl.toHttpUrl()
    val baseUrl = "${chapter.scheme}://${chapter.host}"

    val gateHeaders = buildGateStartHeaders(headers, baseUrl, chapterUrl)
    val startRequest = Request.Builder()
        .url("$baseUrl/api/gate/start")
        .headers(gateHeaders)
        .post(GateRequest(chapter.encodedPath).toJsonRequestBody())
        .fetch(Fetch.XHR.from(chapterUrl))
        .build()

    val gateResponse = execute(client, startRequest, false)
    if (!gateResponse.isSuccessful) {
        val errorBody = gateResponse.use { it.body.string() }
        if (gateResponse.code == 400 && "gate_disabled" in errorBody) {
            val unlockRequest = Request.Builder()
                .url("$baseUrl/api/reader/unlock")
                .headers(gateHeaders)
                .post(GateRequest(chapter.encodedPath).toJsonRequestBody())
                .fetch(Fetch.XHR.from(chapterUrl))
                .build()
            execute(client, unlockRequest, false).close()
            val docRequest = Request.Builder()
                .url(chapterUrl)
                .headers(headers)
                .fetch(Fetch.DOCUMENT.from(chapterUrl))
                .build()
            return execute(client, docRequest, false)
        }
        error("Aurora reader gate start: $chapterUrl status=${gateResponse.code} error=${errorBody.take(100)}")
    }

    val gate = gateResponse.parseAs<GateResponse>().data
    return completeGate(gate, chapterUrl, client, noRedirectClient, headers, timing, wait, execute)
}

internal suspend fun completeGate(
    gate: GateData,
    chapterUrl: String,
    client: OkHttpClient,
    gateClient: OkHttpClient,
    headers: Headers,
    timing: GateTiming,
    wait: suspend (Duration) -> Unit = { delay(it) },
    execute: suspend (OkHttpClient, Request, Boolean) -> Response = { c, req, ensureSuccess ->
        c.call(req, ensureSuccess)
    },
): Response {
    val issued = TimeSource.Monotonic.markNow()
    val callback = gate.callback(chapterUrl)
    val navigationHeaders = buildGateNavigationHeaders(headers, chapterUrl)

    // Visit gateUrl without following external affiliate redirects; server only checks the initial hit.
    gate.validatedGateUrl()?.let { gateTarget ->
        val gateVisit = Request.Builder()
            .url(gateTarget)
            .headers(navigationHeaders)
            .fetch(Fetch.DOCUMENT.from(chapterUrl))
            .build()
        runCatching { execute(gateClient, gateVisit, false).close() }
    }

    val callbackRequest = Request.Builder()
        .url(callback)
        .headers(navigationHeaders)
        .fetch(Fetch.DOCUMENT.from(chapterUrl))
        .build()

    val callbackResponse = timing.complete(gate.minWaitSeconds, issued::elapsedNow, wait) {
        execute(client, callbackRequest, false)
    }
    val gateError = callbackResponse.request.url.queryParameter("gate_error")
    if (gateError != null) {
        callbackResponse.close()
        error("Aurora reader gate callback: $chapterUrl gate_error=$gateError")
    }
    return callbackResponse
}

internal class AuroraReaderCoordinator(
    private val sourceNameProvider: () -> String,
    private val clientProvider: () -> OkHttpClient,
    private val noRedirectClientProvider: () -> OkHttpClient,
    private val baseHeadersProvider: () -> Headers,
    private val baseUrlProvider: () -> String,
    private val gateTiming: GateTiming = GateTiming(),
    private val sessionTtl: Duration = DEFAULT_SESSION_TTL,
    private val elapsedSinceProvider: (Long) -> Duration = { startedMark ->
        (TimeSource.Monotonic.markNow() - originMark).inWholeMilliseconds.minus(startedMark).milliseconds
    },
    private val markProvider: () -> Long = { originMark.elapsedNow().inWholeMilliseconds },
    private val cookieFlusher: () -> Unit = { runCatching { CookieManager.getInstance().flush() } },
    private val wait: suspend (Duration) -> Unit = { delay(it) },
    private val callExecutor: suspend (OkHttpClient, Request, Boolean) -> Response = { client, request, ensureSuccess ->
        client.call(request, ensureSuccess)
    },
) {
    private val gateMutex = Mutex()
    private val keyMutex = Mutex()

    @Volatile
    private var cachedKey: KeyDto? = null

    private val chapterCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedChapter>(MAX_CACHED_CHAPTERS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChapter>?): Boolean = size > MAX_CACHED_CHAPTERS
        },
    )

    suspend fun getPageUrls(chapterUrl: String, entryUrl: String): List<String> {
        chapterCache[chapterUrl]
            ?.takeIf { elapsedSinceProvider(it.cachedAtMark) < sessionTtl && it.urls.isNotEmpty() }
            ?.let { return it.urls }

        val counts = linkedMapOf<String, Int>()
        var status = 0
        fun read(response: Response): ReaderPayload = response.use {
            status = it.code
            extractReader(it.body.string(), it.header("Content-Type").orEmpty(), chapterUrl).also { payload ->
                counts[payload.format] = payload.urls.size
            }
        }

        suspend fun fetchChapter(rsc: Boolean): ReaderPayload {
            val fetch = if (rsc) Fetch.XHR.from(entryUrl) else Fetch.DOCUMENT.from(entryUrl)
            val request = Request.Builder()
                .url(chapterUrl)
                .headers(readerHeaders(entryUrl, rsc))
                .fetch(fetch)
                .build()
            return read(callExecutor(clientProvider(), request, false))
        }

        var payload = fetchChapter(rsc = true)
        if (payload.gated) {
            payload = gateMutex.withLock {
                // Another coroutine may have unlocked this chapter or refreshed the session while waiting.
                chapterCache[chapterUrl]
                    ?.takeIf { elapsedSinceProvider(it.cachedAtMark) < sessionTtl && it.urls.isNotEmpty() }
                    ?.let { return@withLock ReaderPayload(it.urls, gated = false, format = "cache") }

                val recheck = fetchChapter(rsc = true)
                if (!recheck.gated && recheck.urls.isNotEmpty()) {
                    return@withLock recheck
                }

                val response = unlockReader(
                    client = clientProvider(),
                    chapterUrl = chapterUrl,
                    headers = readerHeaders(entryUrl, rsc = false),
                    timing = gateTiming,
                    noRedirectClient = noRedirectClientProvider(),
                    execute = callExecutor,
                    wait = wait,
                )
                cookieFlusher()
                counts["gate"] = 1
                var unlocked = read(response)
                if (unlocked.urls.isEmpty()) {
                    unlocked = fetchChapter(rsc = true)
                }
                unlocked
            }
        }
        if (payload.urls.isEmpty() && !payload.gated && status in 200..299) {
            payload = fetchChapter(rsc = false)
        }
        if (payload.urls.isEmpty()) readerFailure(sourceNameProvider(), chapterUrl, status, counts)

        val urls = try {
            decodePages(payload.urls, chapterUrl) { params -> getKey(params, chapterUrl) }
        } catch (e: IllegalArgumentException) {
            cachedKey = null
            readerFailure(sourceNameProvider(), chapterUrl, status, counts + ("decrypt" to 0))
        }
        if (urls.isEmpty()) readerFailure(sourceNameProvider(), chapterUrl, status, counts + ("urls" to 0))

        chapterCache[chapterUrl] = CachedChapter(urls = urls, cachedAtMark = markProvider())
        return urls
    }

    internal suspend fun getKey(params: Pair<Int, Long>, chapterUrl: String): String {
        val (v, e) = params
        cachedKey?.takeIf { it.v == v && it.e == e }?.let { return it.k }

        return keyMutex.withLock {
            cachedKey?.takeIf { it.v == v && it.e == e }?.let { return@withLock it.k }
            val request = Request.Builder()
                .url("${baseUrlProvider()}/api/atfield/key?v=$v&e=$e")
                .headers(baseHeadersProvider())
                .fetch(Fetch.XHR.from(chapterUrl))
                .build()
            val response = callExecutor(clientProvider(), request, false)
            if (!response.isSuccessful) {
                val errorBody = response.use { it.body.string() }
                error("Aurora reader key: $chapterUrl status=${response.code} error=${errorBody.take(100)}")
            }
            val dto = response.use { it.parseAs<KeyDto>() }
            KeyDto(k = dto.k, v = dto.v ?: v, e = dto.e ?: e).also { cachedKey = it }.k
        }
    }

    private fun readerHeaders(entryUrl: String, rsc: Boolean): Headers {
        val builder = baseHeadersProvider().newBuilder()
        if (rsc) {
            builder
                .set("rsc", "1")
                .set("next-url", entryUrl.toHttpUrl().encodedPath)
                .set("Accept", "*/*")
        }
        return builder.build()
    }

    private class CachedChapter(val urls: List<String>, val cachedAtMark: Long)

    companion object {
        private const val MAX_CACHED_CHAPTERS = 8
        private val DEFAULT_SESSION_TTL = 2.minutes
        private val originMark = TimeSource.Monotonic.markNow()
    }
}
