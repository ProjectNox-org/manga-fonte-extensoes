package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import android.webkit.CookieManager
import keiyoushi.utils.TURNSTILE_AREA
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Solves Cloudflare challenges in a hidden WebView while stripping Android WebView's default
 * `X-Requested-With` header for `3xyaoi.com`, which otherwise triggers Cloudflare's hard WAF block
 * ("Sorry, you have been blocked").
 */
class ChallengeInterceptor : Interceptor {

    private val lock = Any()

    @Volatile
    private var passes = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val passesBefore = passes
        val response = chain.proceed(request)
        if (!response.isChallenge()) return response
        response.close()
        synchronized(lock) {
            if (passes == passesBefore) {
                pass(chain, request)
                passes++
            }
        }
        return chain.proceed(request)
    }

    private fun pass(chain: Interceptor.Chain, request: Request) {
        val origin = "${request.url.scheme}://${request.url.host}"
        try {
            val previous = clearance(origin)
            val started = TimeSource.Monotonic.markNow()
            var shown = false
            runWebViewBlocking<Unit>(chain.call(), TIMEOUT) {
                userAgent = request.header("User-Agent").orEmpty()
                setOriginMatchedHeader("X-Requested-With", "", setOf(origin))
                poll(POLL_INTERVAL) {
                    val current = clearance(origin)
                    if (current != null && current != previous) {
                        resolve(Unit)
                    } else if (!shown && started.elapsedNow() > SHOW_AFTER) {
                        shown = showToUser(TURNSTILE_AREA)
                    }
                }
                loadUrl(request.url.toString())
            }
        } catch (e: UnsupportedOperationException) {
            throw IOException("Atualize o Android System WebView para passar pela verificação do Cloudflare.", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("Abra no WebView para passar pela verificação do Cloudflare.", e)
        }
    }

    private fun clearance(origin: String): String? = CookieManager.getInstance().getCookie(origin)
        ?.split("; ")
        ?.firstOrNull { it.startsWith("cf_clearance=") }

    private fun Response.isChallenge(): Boolean {
        if (code != 403) return false
        if (header("cf-mitigated") == "challenge") return true
        val snippet = try {
            peekBody(4096).string()
        } catch (_: IOException) {
            ""
        }
        return snippet.contains("_cf_chl_opt") || snippet.contains("cf-turnstile")
    }

    companion object {
        private val TIMEOUT = 60.seconds
        private val POLL_INTERVAL = 250.milliseconds
        private val SHOW_AFTER = 6.seconds
    }
}
