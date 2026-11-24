package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import android.webkit.CookieManager
import keiyoushi.utils.TURNSTILE_AREA
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Coordinates OkHttp interception with [SessionManager] and WebView-based Cloudflare challenge resolution
 * while preserving the proactive `X-Requested-With` stripping needed on `3xyaoi.com`.
 */
class ChallengeInterceptor internal constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    constructor() : this(createDefaultSessionManager())

    override fun intercept(chain: Interceptor.Chain): Response = sessionManager.interceptWithRecovery(chain.call(), chain.request(), chain::proceed)

    companion object {
        internal fun createDefaultSessionManager(): SessionManager = SessionManager(
            solver = WebViewChallengeSolver,
        )
    }
}

internal object WebViewChallengeSolver : ChallengeSolver {
    private val TIMEOUT = 60.seconds
    private val POLL_INTERVAL = 250.milliseconds
    private val SHOW_AFTER = 6.seconds

    override fun solve(call: Call, request: Request, classification: ChallengeClassification): Boolean {
        val origin = "${request.url.scheme}://${request.url.host}"
        WebViewOriginHeader.ensure(origin)
        val targetUrl = challengeNavigationUrl(request, origin)

        try {
            val previousClearance = clearance(origin)
            val started = TimeSource.Monotonic.markNow()
            var shown = false
            val pageSettledClean = AtomicBoolean(false)
            val checkInFlight = AtomicBoolean(false)

            runWebViewBlocking<Unit>(call, TIMEOUT) {
                userAgent = request.header("User-Agent").orEmpty()
                setOriginMatchedHeader(
                    "X-Requested-With",
                    "",
                    WebViewOriginHeader.originRulesFor(origin).ifEmpty { setOf(origin) },
                )

                onPageFinished { finishedUrl ->
                    if (!finishedUrl.startsWith(origin)) return@onPageFinished
                    if (!checkInFlight.compareAndSet(false, true)) return@onPageFinished
                    evaluateJs(
                        """
                        (function() {
                          var t = document.title || '';
                          var h = document.documentElement ? document.documentElement.innerHTML : '';
                          if (t.indexOf('Just a moment') !== -1 || t.indexOf('Attention Required') !== -1) return 'challenge';
                          if (h.indexOf('_cf_chl_opt') !== -1 || h.indexOf('Sorry, you have been blocked') !== -1) return 'blocked';
                          if (document.getElementById('xyaoi-cards-container') || h.indexOf('xyaoi_library_filter') !== -1 || h.indexOf('wp-manga') !== -1 || document.querySelector('.reading-content, .xyaoi-reader-topbar, nav.xyaoi-nav-container')) return 'clean';
                          return 'unknown';
                        })()
                        """.trimIndent(),
                    ) { result ->
                        checkInFlight.set(false)
                        if (result == "\"clean\"" || result == "clean") {
                            pageSettledClean.set(true)
                        }
                    }
                }

                poll(POLL_INTERVAL) {
                    val currentClearance = clearance(origin)
                    val clearanceChanged = currentClearance != null && currentClearance != previousClearance
                    if (clearanceChanged || pageSettledClean.get()) {
                        resolve(Unit)
                    } else if (!shown && started.elapsedNow() > SHOW_AFTER) {
                        shown = showToUser(TURNSTILE_AREA)
                    }
                }
                loadUrl(targetUrl)
            }
            CookieManager.getInstance().flush()
            return true
        } catch (e: UnsupportedOperationException) {
            throw IOException("Atualize o Android System WebView para passar pela verificação do Cloudflare.", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Abra no WebView para passar pela verificação do Cloudflare.", e)
        }
    }

    internal fun challengeNavigationUrl(request: Request, origin: String): String {
        val url = request.url
        val path = url.encodedPath.lowercase()
        val isSubresource = path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".avif") ||
            path.endsWith(".gif") ||
            path.endsWith("img.php") ||
            path.endsWith("admin-ajax.php") ||
            path.endsWith(".js")
        if (!isSubresource) return url.toString()
        val referer = request.header("Referer")?.trim()
        if (!referer.isNullOrEmpty() && !referer.endsWith("img.php")) {
            if (referer.startsWith(origin)) return referer
            if (referer.startsWith('/')) return "$origin$referer"
        }
        return "$origin/biblioteca/"
    }

    private fun clearance(origin: String): String? = CookieManager.getInstance().getCookie(origin)
        ?.split(';')
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("cf_clearance=") }
}

internal fun Response.isCloudflareChallenge(): Boolean = CloudflareClassifier.classifyResponse(this) != ChallengeClassification.CLEAN
