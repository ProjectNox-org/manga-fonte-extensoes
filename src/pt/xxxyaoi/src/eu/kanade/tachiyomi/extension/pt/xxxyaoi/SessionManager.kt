package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class SessionState {
    VALID,
    POSSIBLY_EXPIRED,
    CHALLENGE,
    RECOVERED,
    PERSISTENT_BLOCK,
}

internal enum class ChallengeClassification {
    CLEAN,
    INTERACTIVE_CHALLENGE,
    CHALLENGE_REDIRECT,
    WAF_BLOCK,
}

internal class CloudflareSessionException(
    val state: SessionState,
    val diagnosis: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun interface ChallengeSolver {
    fun solve(call: Call, request: Request, classification: ChallengeClassification): Boolean
}

internal object CloudflareClassifier {

    private const val MAX_PEEK_BYTES = 16_384L

    private val ACTIVE_CHALLENGE_TITLE_REGEX = Regex("""<title>\s*(?:Just a moment|Um momento)""", RegexOption.IGNORE_CASE)
    private val PASSIVE_JSD_REGEX = Regex("""/cdn-cgi/challenge-platform/scripts/jsd/""", RegexOption.IGNORE_CASE)
    private val ACTIVE_PLATFORM_REGEX = Regex("""/cdn-cgi/challenge-platform/(?:h/|orchestrate/)""", RegexOption.IGNORE_CASE)
    private val SITE_STRUCTURE_MARKERS = listOf(
        "id=\"xyaoi-cards-container\"",
        "id='xyaoi-cards-container'",
        "xyaoi-cards-grid",
        "xyaoi_library_filter",
        "wp-manga",
        "admin-ajax.php",
        "reading-content",
        "3xyaoi-leitor/player.php",
    )

    fun classifyResponse(response: Response): ChallengeClassification {
        val code = response.code
        val cfMitigated = response.header("cf-mitigated")?.trim()?.lowercase()
        if (cfMitigated == "challenge") {
            return ChallengeClassification.INTERACTIVE_CHALLENGE
        }

        if (code in 300..399) {
            val location = response.header("Location")?.lowercase().orEmpty()
            if ("/cdn-cgi/challenge-platform" in location || "/cdn-cgi/l/chk" in location) {
                return ChallengeClassification.CHALLENGE_REDIRECT
            }
            return ChallengeClassification.CLEAN
        }

        if (code != 200 && code != 403 && code != 429 && code != 503) {
            return ChallengeClassification.CLEAN
        }

        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
        val isHtmlLike = contentType.isEmpty() ||
            contentType.contains("text/html") ||
            contentType.contains("application/xhtml")
        if (!isHtmlLike) {
            return ChallengeClassification.CLEAN
        }

        val snippet = try {
            response.peekBody(MAX_PEEK_BYTES).string()
        } catch (_: IOException) {
            ""
        }
        return classifyHtml(code, snippet)
    }

    fun hasRealSiteStructure(html: String): Boolean {
        val lower = html.lowercase()
        return SITE_STRUCTURE_MARKERS.any { marker -> marker in lower }
    }

    fun classifyHtml(statusCode: Int, html: String): ChallengeClassification {
        if (html.isBlank()) return ChallengeClassification.CLEAN

        val lower = html.lowercase()
        val isWafBlock = (
            "attention required! | cloudflare" in lower ||
                "sorry, you have been blocked" in lower ||
                "class=\"cf-error-details\"" in lower ||
                "error 1020" in lower
            ) && !hasRealSiteStructure(lower)
        if (isWafBlock) {
            return ChallengeClassification.WAF_BLOCK
        }

        val sitePresent = hasRealSiteStructure(lower)
        val hasTitleChallenge = ACTIVE_CHALLENGE_TITLE_REGEX.containsMatchIn(html)
        val hasCfOpt = "_cf_chl_opt" in lower || "challenge-running" in lower || "id=\"challenge-form\"" in lower || "id='challenge-form'" in lower
        val hasActivePlatform = ACTIVE_PLATFORM_REGEX.containsMatchIn(lower)
        val hasTurnstile = "cf-turnstile" in lower
        val onlyPassiveJsd = PASSIVE_JSD_REGEX.containsMatchIn(lower) && !hasActivePlatform

        return when {
            hasTitleChallenge || hasCfOpt || hasActivePlatform -> ChallengeClassification.INTERACTIVE_CHALLENGE
            hasTurnstile && !sitePresent -> ChallengeClassification.INTERACTIVE_CHALLENGE
            statusCode in listOf(403, 503) && !sitePresent && onlyPassiveJsd -> ChallengeClassification.INTERACTIVE_CHALLENGE
            else -> ChallengeClassification.CLEAN
        }
    }
}

/**
 * Coordinates single-flight Cloudflare challenge recovery and session state tracking (States A–E)
 * so concurrent requests do not open redundant WebViews or retry indefinitely on persistent blocks.
 */
internal class SessionManager(
    private val solver: ChallengeSolver,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val generation = AtomicLong(0L)

    @Volatile
    var state: SessionState = SessionState.VALID
        private set

    @Volatile
    private var lastFailureAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var lastDiagnosis: String = "ok"

    fun markPossiblyExpired() {
        if (state == SessionState.VALID || state == SessionState.RECOVERED) {
            state = SessionState.POSSIBLY_EXPIRED
        }
    }

    fun resetForManualRetry() {
        lastFailureAtMs = Long.MIN_VALUE
        if (state == SessionState.PERSISTENT_BLOCK) {
            state = SessionState.POSSIBLY_EXPIRED
        }
    }

    fun currentGeneration(): Long = generation.get()

    fun interceptWithRecovery(
        call: Call,
        request: Request,
        proceed: (Request) -> Response,
    ): Response {
        val observedGen = generation.get()
        val initialResponse = proceed(request)
        val initialClass = CloudflareClassifier.classifyResponse(initialResponse)

        if (initialClass == ChallengeClassification.CLEAN) {
            val fromCacheOnly = initialResponse.cacheResponse != null && initialResponse.networkResponse == null
            if (initialResponse.isSuccessful && !fromCacheOnly) {
                state = SessionState.VALID
                lastDiagnosis = "ok"
            }
            return initialResponse
        }

        initialResponse.close()

        val recoveredByThisOrPeer = lock.withLock {
            val currentGen = generation.get()
            if (currentGen != observedGen) {
                if (state == SessionState.PERSISTENT_BLOCK && isWithinCooldown()) {
                    throw CloudflareSessionException(
                        state = SessionState.PERSISTENT_BLOCK,
                        diagnosis = lastDiagnosis,
                        message = persistentMessage(lastDiagnosis),
                    )
                }
                return@withLock true
            }

            if (state == SessionState.PERSISTENT_BLOCK && isWithinCooldown()) {
                throw CloudflareSessionException(
                    state = SessionState.PERSISTENT_BLOCK,
                    diagnosis = lastDiagnosis,
                    message = persistentMessage(lastDiagnosis),
                )
            }

            state = SessionState.CHALLENGE
            val solved = try {
                solver.solve(call, request, initialClass)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                recordPersistentFailure(if (initialClass == ChallengeClassification.WAF_BLOCK) "waf-blocked" else "challenge-failed")
                throw e
            } catch (e: Throwable) {
                recordPersistentFailure("challenge-error")
                throw CloudflareSessionException(
                    state = SessionState.PERSISTENT_BLOCK,
                    diagnosis = "challenge-error",
                    message = "XXX Yaoi: falha ao iniciar WebView para o desafio Cloudflare. Abra pelo ícone de globo.",
                    cause = e,
                )
            }

            if (!solved) {
                val reason = if (initialClass == ChallengeClassification.WAF_BLOCK) "waf-blocked" else "challenge-unsolved"
                recordPersistentFailure(reason)
                throw CloudflareSessionException(
                    state = SessionState.PERSISTENT_BLOCK,
                    diagnosis = reason,
                    message = persistentMessage(reason),
                )
            }

            state = SessionState.RECOVERED
            lastFailureAtMs = Long.MIN_VALUE
            generation.incrementAndGet()
            true
        }

        if (!recoveredByThisOrPeer) {
            throw CloudflareSessionException(
                state = SessionState.PERSISTENT_BLOCK,
                diagnosis = lastDiagnosis,
                message = persistentMessage(lastDiagnosis),
            )
        }

        val replayed = proceed(request)
        val replayedClass = CloudflareClassifier.classifyResponse(replayed)
        if (replayedClass != ChallengeClassification.CLEAN) {
            replayed.close()
            val diagnosis = if (replayedClass == ChallengeClassification.WAF_BLOCK) "waf-blocked" else "challenge-persists"
            lock.withLock {
                recordPersistentFailure(diagnosis)
            }
            throw CloudflareSessionException(
                state = SessionState.PERSISTENT_BLOCK,
                diagnosis = diagnosis,
                message = persistentMessage(diagnosis),
            )
        }

        if (replayed.isSuccessful) {
            state = SessionState.VALID
            lastDiagnosis = "ok"
        }
        return replayed
    }

    private fun isWithinCooldown(): Boolean {
        val failedAt = lastFailureAtMs
        if (failedAt == Long.MIN_VALUE) return false
        return (clockMs() - failedAt) in 0 until cooldownMs
    }

    private fun recordPersistentFailure(diagnosis: String) {
        state = SessionState.PERSISTENT_BLOCK
        lastDiagnosis = diagnosis
        lastFailureAtMs = clockMs()
        generation.incrementAndGet()
    }

    private fun persistentMessage(diagnosis: String): String = when (diagnosis) {
        "waf-blocked" -> "XXX Yaoi: bloqueio persistente do Cloudflare (WAF). Abra a extensão pelo ícone de globo no WebView para verificar."
        "challenge-persists" -> "XXX Yaoi: verificação Cloudflare não concluída na repetição automática. Abra pelo ícone de globo no WebView."
        else -> "XXX Yaoi: sessão Cloudflare expirada ou bloqueada ($diagnosis). Abra pelo ícone de globo para resolver o desafio."
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
