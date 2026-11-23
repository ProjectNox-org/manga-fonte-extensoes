package eu.kanade.tachiyomi.multisrc.aurora

import android.webkit.CookieManager
import keiyoushi.network.Fetch
import keiyoushi.network.call
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val GATE_START_ROUTE = "/api/gate/start"
private const val GATE_UNLOCK_ROUTE = "/api/reader/unlock"
private const val MAX_UNLOCK_WAIT_SECONDS = 15
private val CHALLENGE_RETRY_DELAY = 1500.milliseconds
private val challengeMarkers = listOf("Just a moment", "cf_chl", "challenge-platform", "__cf_chl_opt")
private val errorReasonRegex = Regex("\"(?:error|message|code)\"\\s*:\\s*\"([A-Za-z0-9_.:-]{1,40})\"")
private val tooFastSecondsRegex = Regex("\"(?:minWaitSeconds|retryAfter)\"\\s*:\\s*(\\d+)")

/** The app's Cloudflare interceptor answers these; the retry covers a clearance cookie that landed after the request. */
internal fun Response.isChallenge(): Boolean = header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ||
    ((code == 403 || code == 503) && peekBody(4096).string().let(::looksLikeChallenge))

internal fun looksLikeChallenge(body: String): Boolean = challengeMarkers.any { it in body }

/** `error.code` of an application JSON rejection, e.g. `captcha_required`; never a Cloudflare edge challenge. */
internal fun gateErrorReason(body: String): String? = errorReasonRegex.find(body)?.groupValues?.get(1)

/**
 * Mihon truncates source messages, so the HTTP status leads, the chapter query string is dropped and the body is
 * never echoed: only a short error/message field survives, and gate tokens cannot leak into a screenshot.
 */
internal fun gateFailure(
    stage: String,
    route: String,
    code: Int,
    contentType: String?,
    body: String,
    chapterPath: String,
    trace: String? = null,
): Nothing {
    val reason = gateErrorReason(body)
    val kind = when {
        looksLikeChallenge(body) -> "cloudflare-challenge"
        reason == "captcha_required" || reason == "captcha_failed" -> "captcha-required"
        code == 429 -> "rate-limited"
        code == 404 || code == 405 -> "route-missing"
        code in 500..599 -> "server-error"
        else -> reason ?: "no-reason"
    }
    val media = contentType?.substringBefore(';')?.takeIf { it.isNotBlank() } ?: "no-type"
    // Mihon truncates the tail of a source message, so an unrecognised body leads: it is the only part that can name
    // the cause. Long token-like runs are redacted and the body is never echoed for the known reasons above.
    val excerpt = if (kind == "no-reason") {
        body.replace(Regex("[A-Za-z0-9_.:-]{24,}"), "<redacted>").replace(Regex("\\s+"), " ").trim().take(120)
    } else {
        ""
    }
    val context = if (trace.isNullOrBlank()) "" else " [$trace]"
    val detail = "Aurora gate $stage: HTTP $code $kind $media $route$context $chapterPath"
    error(if (excerpt.isEmpty()) detail else "$excerpt — $detail")
}

@Serializable
internal class GateRequest(val returnTo: String)

/** Second attempt after a `captcha_required`; the token comes from the site's Turnstile widget. */
@Serializable
internal class GateCaptchaRequest(val returnTo: String, val captchaToken: String)

/** The reader unlock route names the same Turnstile token `token`. */
@Serializable
internal class GateUnlockRequest(val returnTo: String, val token: String)

@Serializable
internal class GateResponse(val data: GateData)

@Serializable
internal class GateData(
    val token: String,
    val returnTo: String? = null,
    val minWaitSeconds: Int? = null,
    val gateUrl: String? = null,
    val method: String? = null,
) {
    /** The partner gates run their own countdown server-side, so the client does not wait for them. */
    val partnerMethod: Boolean get() = method == "bcm" || method == "redenovax"

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

/**
 * Absolute monotonic offsets of one chapter open. Kept on the coordinator so a failure line and the perf tests can
 * show where the time went; it never records URLs' query strings, tokens or cookies.
 */
internal class PhaseClock {
    private val start = TimeSource.Monotonic.markNow()
    private val marks = LinkedHashMap<String, Duration>()

    fun mark(stage: String) {
        marks[stage] = start.elapsedNow()
    }

    fun snapshot(): Map<String, Duration> = marks.toMap()

    override fun toString(): String = marks.entries.joinToString(" ") { "${it.key}=${it.value.inWholeMilliseconds}ms" }
}

internal class GateTiming(
    /** Seconds to spend when the gate declares neither a usable minimum nor a trusted zero. */
    private val fallbackSeconds: Int = 15,
    /** Set false for sites that declare `0` on a partner method but still refuse an early callback. */
    private val trustPartnerZero: Boolean = true,
) {
    /** Smallest countdown that the server actually accepted for this source instance. */
    @Volatile
    private var learnedSeconds = 0

    suspend fun complete(
        serverMinimum: Int? = null,
        partner: Boolean = false,
        elapsed: () -> Duration,
        wait: suspend (Duration) -> Unit = { delay(it) },
        // Runs while the countdown burns, so the gate link costs no extra wall time.
        beforeRequest: suspend () -> Unit = {},
        request: suspend () -> Response,
    ): Response = coroutineScope {
        var target = resolve(serverMinimum, partner)
        val pre = launch { beforeRequest() }
        wait((target - elapsed()).coerceAtLeast(Duration.ZERO))
        pre.join()

        var response = request()
        var escalation = 0
        while (response.request.url.queryParameter("gate_error") == "too_fast" && escalation < MAX_ESCALATIONS) {
            response.close()
            escalation++
            target = escalate(target)
            wait((target - elapsed()).coerceAtLeast(Duration.ZERO))
            response = request()
        }
        if (response.request.url.queryParameter("gate_error") != "too_fast") {
            // The token survives too_fast, so the accepted value is the real floor for the next chapters.
            learnedSeconds = target.inWholeSeconds.toInt()
        }
        response
    }

    private fun resolve(serverMinimum: Int?, partner: Boolean): Duration = when {
        serverMinimum != null && serverMinimum > 0 -> serverMinimum.seconds
        !trustPartnerZero -> maxOf(fallbackSeconds, learnedSeconds).seconds
        partner -> Duration.ZERO
        // A declared zero is honoured until this source sees the server refuse one.
        serverMinimum == 0 -> learnedSeconds.seconds
        else -> maxOf(fallbackSeconds, learnedSeconds).seconds
    }

    private fun escalate(current: Duration): Duration = maxOf(current.inWholeSeconds.toInt() + ESCALATION_STEP, learnedSeconds).seconds

    private companion object {
        const val ESCALATION_STEP = 4
        const val MAX_ESCALATIONS = 2
    }
}

internal fun buildGateStartHeaders(headers: Headers, baseUrl: String, chapterUrl: String): Headers = headers.newBuilder()
    .set("Origin", baseUrl)
    .set("Referer", chapterUrl)
    .set("Sec-Fetch-Mode", "cors")
    .set("Sec-Fetch-Dest", "empty")
    .set("Sec-Fetch-Site", "same-origin")
    .build()

/** Cookie names only: enough to audit the session, never a value. */
// Frozen on purpose: the endpoint rejects requests whose User-Agent is not a browser.
private const val KEY_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

internal fun cookieNames(client: OkHttpClient, url: String): String = runCatching {
    client.cookieJar.loadForRequest(url.toHttpUrl()).map { it.name }.sorted().joinToString(",")
}.getOrElse { "?" }

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
    // Off by default: only sites that verify the Turnstile token on the unlock route opt in.
    captchaUnlocksReader: Boolean = false,
    diagnostic: (String) -> Unit = {},
    captchaSolver: (suspend (String) -> String)? = null,
    clock: PhaseClock = PhaseClock(),
    // Monotonic "now" shared with the coordinator so a fake clock can drive the countdown in tests.
    now: (() -> Duration)? = null,
    execute: suspend (OkHttpClient, Request, Boolean) -> Response = { c, req, ensureSuccess ->
        c.call(req, ensureSuccess)
    },
    wait: suspend (Duration) -> Unit = { delay(it) },
): Response {
    val chapter = chapterUrl.toHttpUrl()
    val baseUrl = "${chapter.scheme}://${chapter.host}"
    val startMark = TimeSource.Monotonic.markNow()
    fun currentTime(): Duration = now?.invoke() ?: startMark.elapsedNow()

    val gateHeaders = buildGateStartHeaders(headers, baseUrl, chapterUrl)
    fun startRequest(captchaToken: String?): Request {
        val body = if (captchaToken == null) {
            GateRequest(chapter.encodedPath).toJsonRequestBody()
        } else {
            GateCaptchaRequest(chapter.encodedPath, captchaToken).toJsonRequestBody()
        }
        return Request.Builder()
            .url("$baseUrl$GATE_START_ROUTE")
            .headers(gateHeaders)
            .post(body)
            .fetch(Fetch.XHR.from(chapterUrl))
            .build()
    }
    fun unlockRequest(captchaToken: String?): Request {
        val body = if (captchaToken == null) {
            GateRequest(chapter.encodedPath).toJsonRequestBody()
        } else {
            GateUnlockRequest(chapter.encodedPath, captchaToken).toJsonRequestBody()
        }
        return Request.Builder()
            .url("$baseUrl$GATE_UNLOCK_ROUTE")
            .headers(gateHeaders)
            .post(body)
            .fetch(Fetch.XHR.from(chapterUrl))
            .build()
    }
    val chapterRequest = Request.Builder()
        .url(chapterUrl)
        .headers(headers)
        .fetch(Fetch.DOCUMENT.from(chapterUrl))
        .build()

    var captchaToken: String? = null
    var solvedCaptcha = false
    while (true) {
        val request = startRequest(captchaToken)
        // The server times the wait from its receipt of this request, so the countdown can start here instead of a
        // round trip later.
        val attemptStart = currentTime()
        clock.mark("gate-start")
        var gateResponse = execute(client, request, false)
        if (gateResponse.isChallenge()) {
            gateResponse.close()
            wait(CHALLENGE_RETRY_DELAY)
            gateResponse = execute(client, request, false)
        }
        if (gateResponse.isSuccessful) {
            clock.mark("gate-start-ok")
            val gate = gateResponse.parseAs<GateResponse>().data
            return completeGate(
                gate = gate,
                chapterUrl = chapterUrl,
                client = client,
                gateClient = noRedirectClient,
                headers = headers,
                timing = timing,
                wait = wait,
                clock = clock,
                elapsed = { currentTime() - attemptStart },
                execute = execute,
                diagnostic = diagnostic,
            )
        }

        val status = gateResponse.code
        diagnostic("start status=$status host=${chapter.host} cookies=[${cookieNames(client, "$baseUrl/")}]")
        val contentType = gateResponse.header("Content-Type")
        val errorBody = gateResponse.use { it.body.string() }
        if (status == 400 && "gate_disabled" in errorBody) {
            execute(client, unlockRequest(captchaToken), false).close()
            return execute(client, chapterRequest, false)
        }

        // The app, not the edge, asks for a human check; the page answers it with its own Turnstile widget and
        // retries the same POST with captchaToken. Retry once so a rejected/expired token cannot loop.
        val reason = gateErrorReason(errorBody)
        val needsCaptcha = reason == "captcha_required" || reason == "captcha_failed"
        if (needsCaptcha && captchaSolver != null && !solvedCaptcha) {
            clock.mark("captcha-required")
            solvedCaptcha = true
            // Turnstile tokens are host-bound: mint on the host that answers the gate, not on a redirecting mirror.
            val solverUrl = if (captchaUnlocksReader) canonicalReaderUrl(client, chapterUrl, headers, execute) else chapterUrl
            captchaToken = captchaSolver.invoke(solverUrl)
            clock.mark("captcha-solved")
            // Retry the start with the token: on these sites that 200 is what issues the session the reader key
            // needs, so spending the token on the unlock first leaves the images unauthorized.
            continue
        }
        // Some sites accept the Turnstile token only on the unlock route, not on the gate start retry.
        if (needsCaptcha && captchaToken != null && captchaUnlocksReader) {
            val unlock = execute(client, unlockRequest(captchaToken), false)
            if (unlock.isSuccessful) {
                unlock.close()
                return execute(client, chapterRequest, false)
            }
            var unlockStatus = unlock.code
            var unlockType = unlock.header("Content-Type")
            var unlockBody = unlock.use { it.body.string() }
            val waitSeconds = tooFastSecondsRegex.find(unlockBody)?.groupValues?.get(1)?.toIntOrNull()
            if ("too_fast" in unlockBody && waitSeconds != null) {
                wait(minOf(waitSeconds, MAX_UNLOCK_WAIT_SECONDS).seconds)
                val retry = execute(client, unlockRequest(captchaToken), false)
                if (retry.isSuccessful) {
                    retry.close()
                    return execute(client, chapterRequest, false)
                }
                unlockStatus = retry.code
                unlockType = retry.header("Content-Type")
                unlockBody = retry.use { it.body.string() }
            }
            // The gate start that sent us here already refused this same token, so name it: the unlock line alone
            // would look like the only rejection.
            gateFailure(
                "unlock",
                GATE_UNLOCK_ROUTE,
                unlockStatus,
                unlockType,
                unlockBody,
                chapter.encodedPath,
                trace = "start=$status/${reason ?: "no-reason"}",
            )
        }
        gateFailure("start", GATE_START_ROUTE, status, contentType, errorBody, chapter.encodedPath)
    }
}

/** Follows the mirror redirect once so the captcha is solved on the origin the app later talks to. */
private suspend fun canonicalReaderUrl(
    client: OkHttpClient,
    chapterUrl: String,
    headers: Headers,
    execute: suspend (OkHttpClient, Request, Boolean) -> Response,
): String = runCatching {
    val request = Request.Builder()
        .url(chapterUrl)
        .headers(headers)
        .fetch(Fetch.DOCUMENT.from(chapterUrl))
        .build()
    execute(client, request, false).use { it.request.url.toString() }
}.getOrDefault(chapterUrl)

internal suspend fun completeGate(
    gate: GateData,
    chapterUrl: String,
    client: OkHttpClient,
    gateClient: OkHttpClient,
    headers: Headers,
    timing: GateTiming,
    wait: suspend (Duration) -> Unit = { delay(it) },
    clock: PhaseClock = PhaseClock(),
    elapsed: (() -> Duration)? = null,
    execute: suspend (OkHttpClient, Request, Boolean) -> Response = { c, req, ensureSuccess ->
        c.call(req, ensureSuccess)
    },
    diagnostic: (String) -> Unit = {},
): Response {
    val issued = TimeSource.Monotonic.markNow()
    val elapsedSinceIssue: () -> Duration = elapsed ?: { issued.elapsedNow() }
    clock.mark("gate-issued")
    val callback = gate.callback(chapterUrl)
    val navigationHeaders = buildGateNavigationHeaders(headers, chapterUrl)

    val callbackRequest = Request.Builder()
        .url(callback)
        .headers(navigationHeaders)
        .fetch(Fetch.DOCUMENT.from(chapterUrl))
        .build()

    // The link is visited without following the affiliate redirects; the server only checks the initial hit.
    // Starting it inside the countdown means a slow affiliate hop cannot add time on top of the wait.
    val visitLink: suspend () -> Unit = {
        gate.validatedGateUrl()?.let { gateTarget ->
            val gateVisit = Request.Builder()
                .url(gateTarget)
                .headers(navigationHeaders)
                .fetch(Fetch.DOCUMENT.from(chapterUrl))
                .build()
            runCatching { execute(gateClient, gateVisit, false).close() }
            clock.mark("gate-link")
        }
    }

    val callbackResponse = timing.complete(
        gate.minWaitSeconds,
        gate.partnerMethod,
        elapsedSinceIssue,
        wait,
        visitLink,
    ) {
        execute(client, callbackRequest, false)
    }
    clock.mark("gate-callback")
    val gateError = callbackResponse.request.url.queryParameter("gate_error")
    diagnostic(
        "callback status=${callbackResponse.code} host=${callbackResponse.request.url.host} " +
            "error=${gateError ?: "-"} cookies=[${cookieNames(client, callbackResponse.request.url.toString())}]",
    )
    if (gateError != null) {
        callbackResponse.close()
        error("Aurora gate callback: gate_error=${gateError.take(40)} ${chapterUrl.toHttpUrl().encodedPath}")
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
    private val captchaUnlocksReader: Boolean = false,
    private val diagnostic: (String) -> Unit = {},
    private val captchaSolver: (suspend (String) -> String)? = null,
) {
    private val gateMutex = Mutex()
    private val keyMutex = Mutex()

    /** Bumped after every successful unlock; a waiter compares it to know whether a re-check is worth a round trip. */
    private val unlockEpoch = AtomicInteger()

    @Volatile
    internal var lastPhases: Map<String, Duration> = emptyMap()
        private set

    @Volatile
    private var cachedKey: KeyDto? = null

    /** Host that actually served the chapter, so follow-up XHRs stop going to the redirecting mirror. */
    @Volatile
    private var canonicalOrigin: String? = null

    private val chapterCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedChapter>(MAX_CACHED_CHAPTERS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChapter>?): Boolean = size > MAX_CACHED_CHAPTERS
        },
    )

    suspend fun getPageUrls(chapterUrl: String, entryUrl: String): List<String> {
        val startEpoch = unlockEpoch.get()
        val clock = PhaseClock()
        chapterCache[chapterUrl]
            ?.takeIf { elapsedSinceProvider(it.cachedAtMark) < sessionTtl && it.urls.isNotEmpty() }
            ?.let {
                lastPhases = mapOf("cache" to Duration.ZERO)
                return it.urls
            }

        val counts = linkedMapOf<String, Int>()
        var status = 0
        fun read(response: Response): ReaderPayload = response.use {
            status = it.code
            // The mirror redirects to the canonical host, and only that host answers the key route.
            if (captchaUnlocksReader) canonicalOrigin = "${it.request.url.scheme}://${it.request.url.host}"
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
        clock.mark("probe")
        if (payload.gated) {
            payload = gateMutex.withLock {
                // Another coroutine may have unlocked this chapter or refreshed the session while waiting.
                chapterCache[chapterUrl]
                    ?.takeIf { elapsedSinceProvider(it.cachedAtMark) < sessionTtl && it.urls.isNotEmpty() }
                    ?.let { return@withLock ReaderPayload(it.urls, gated = false, format = "cache") }

                // Only another coroutine's unlock can have changed the chapter while this one waited for the mutex.
                if (unlockEpoch.get() != startEpoch) {
                    val recheck = fetchChapter(rsc = true)
                    clock.mark("recheck")
                    if (!recheck.gated && recheck.urls.isNotEmpty()) {
                        return@withLock recheck
                    }
                }

                val response = unlockReader(
                    client = clientProvider(),
                    chapterUrl = chapterUrl,
                    headers = readerHeaders(entryUrl, rsc = false),
                    timing = gateTiming,
                    noRedirectClient = noRedirectClientProvider(),
                    captchaUnlocksReader = captchaUnlocksReader,
                    diagnostic = diagnostic,
                    captchaSolver = captchaSolver,
                    clock = clock,
                    now = { elapsedSinceProvider(0L) },
                    execute = callExecutor,
                    wait = wait,
                )
                unlockEpoch.incrementAndGet()
                clock.mark("unlocked")
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
        if (payload.urls.isEmpty()) readerFailure(sourceNameProvider(), chapterUrl, status, counts, clock.toString())

        val urls = try {
            decodePages(payload.urls, chapterUrl) { params -> getKey(params, chapterUrl) }
        } catch (e: IllegalArgumentException) {
            cachedKey = null
            readerFailure(sourceNameProvider(), chapterUrl, status, counts + ("decrypt" to 0), clock.toString())
        }
        if (urls.isEmpty()) readerFailure(sourceNameProvider(), chapterUrl, status, counts + ("urls" to 0), clock.toString())
        clock.mark("decoded")
        lastPhases = clock.snapshot()

        chapterCache[chapterUrl] = CachedChapter(urls = urls, cachedAtMark = markProvider())
        return urls
    }

    internal suspend fun getKey(params: Pair<Int, Long>, chapterUrl: String): String {
        val (v, e) = params
        cachedKey?.takeIf { it.v == v && it.e == e }?.let { return it.k }

        return keyMutex.withLock {
            cachedKey?.takeIf { it.v == v && it.e == e }?.let { return@withLock it.k }
            val base = if (captchaUnlocksReader) canonicalOrigin ?: baseUrlProvider() else baseUrlProvider()
            val builder = Request.Builder()
                .url("$base/api/atfield/key?v=$v&e=$e")
                .headers(baseHeadersProvider())
            // Proven on the site for these sites only: the key answers 403 unauthorized without a browser User-Agent,
            // the chapter Referer and the fetch headers, and 200 with them, for the very same cookies.
            if (captchaUnlocksReader) {
                builder
                    .header("User-Agent", KEY_USER_AGENT)
                    .header("Referer", chapterUrl)
                    .header("Accept", "*/*")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
            }
            val request = builder.build()
            val response = callExecutor(clientProvider(), request, false)
            val keyCookies = cookieNames(clientProvider(), request.url.toString())
            diagnostic(
                "key host=${request.url.host} status=${response.code} ua=${request.header("User-Agent")?.take(24)} " +
                    "secfetch=${request.header("Sec-Fetch-Site")} cookies=[$keyCookies]",
            )
            if (!response.isSuccessful) {
                val errorBody = response.use { it.body.string() }
                // Body first: Mihon truncates the tail of a source message.
                error(
                    "${errorBody.take(80)} — Aurora reader key ${request.url.host}${request.url.encodedPath} " +
                        "status=${response.code} cookies=[$keyCookies] chapter=${chapterUrl.toHttpUrl().encodedPath}",
                )
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
