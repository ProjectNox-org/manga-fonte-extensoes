package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object BlackoutAuthBypass

internal data class BlackoutLoginOutcome(
    val isAuthenticated: Boolean,
    val invalidCredentials: Boolean = false,
    val requiresManualWebView: Boolean = false,
    val csrfMismatch: Boolean = false,
    val message: String = "",
)

internal interface BlackoutCredentialStore {
    fun getEmail(): String
    fun getPassword(): String
}

internal interface BlackoutCookieStore {
    fun loadSessionCookies(url: HttpUrl): List<Cookie>
    fun syncFromWebView(url: HttpUrl): Boolean
    fun saveCookies(url: HttpUrl, cookies: List<Cookie>)
    fun clearSessionCookies(url: HttpUrl)
    fun evictHttpCache()
}

internal sealed class BlackoutAuthResult {
    data object Success : BlackoutAuthResult()
    data class Failure(
        val message: String,
        val invalidCredentials: Boolean = false,
        val requiresManualWebView: Boolean = false,
    ) : BlackoutAuthResult()
}

/**
 * Gerencia autenticação Laravel (`GET /` CSRF + `POST /entrar`), validação real da sessão no
 * servidor, renovação sob demanda em falhas 401/403/redirect e proteção contra loops.
 */
internal class BlackoutSessionManager(
    private val baseUrl: String,
    private val credentials: BlackoutCredentialStore,
    private val cookieStore: BlackoutCookieStore,
    private val httpClientProvider: () -> OkHttpClient,
    private val baseHeadersProvider: () -> Headers,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val ephemeralClientFactory: (CookieJar) -> OkHttpClient = { jar ->
        httpClientProvider().newBuilder()
            .cookieJar(jar)
            .build()
    },
) {
    private val baseHttpUrl: HttpUrl by lazy { baseUrl.toHttpUrl() }
    private val authLock = Any()

    @Volatile
    private var lastFailedEmail: String = ""

    @Volatile
    private var lastFailedPassword: String = ""

    @Volatile
    private var transientCooldownUntil: TimeMark? = null

    @Volatile
    private var verifiedWithServer: Boolean = false

    @Volatile
    private var managedSessionActive: Boolean = false

    @Volatile
    private var activeSessionEmail: String? = null

    @Volatile
    private var activeSessionPassword: String? = null

    @Volatile
    private var authenticatedSessionCookie: String? = null

    @Volatile
    private var pendingSessionInvalidation: Boolean = false

    @Volatile
    private var lastAuthFailure: BlackoutAuthResult.Failure? = null

    fun hasCredentials(): Boolean = credentials.getEmail().isNotBlank() && credentials.getPassword().isNotEmpty()

    fun lastFailureMessage(): String? = lastAuthFailure?.message

    fun hasSessionCookie(): Boolean {
        if (currentSessionCookieValue() != null) return true
        return cookieStore.syncFromWebView(baseHttpUrl) && currentSessionCookieValue() != null
    }

    fun isVerifiedAuthenticated(): Boolean = verifiedWithServer && hasSessionCookie()

    fun onCredentialsChanged() {
        lastFailedEmail = ""
        lastFailedPassword = ""
        transientCooldownUntil = null
        lastAuthFailure = null
        verifiedWithServer = false
        authenticatedSessionCookie = null
        pendingSessionInvalidation = true
    }

    fun ensureAuthenticatedForReader(): BlackoutAuthResult {
        if (isVerifiedAuthenticated()) return BlackoutAuthResult.Success
        val isPermanentFailure = synchronized(authLock) {
            syncCredentialStateLocked()
            val email = credentials.getEmail().trim()
            val password = credentials.getPassword()
            email == lastFailedEmail && password == lastFailedPassword
        }
        if (isPermanentFailure) {
            return lastAuthFailure ?: BlackoutAuthResult.Failure(
                "Credenciais inválidas ou ação adicional exigida. Confira seu e-mail e senha ou abra a fonte na WebView.",
                invalidCredentials = true,
            )
        }
        return login(forceAttempt = true)
    }

    fun login(forceAttempt: Boolean = false): BlackoutAuthResult = synchronized(authLock) {
        if (pendingSessionInvalidation) {
            clearManagedSessionLocked()
        }

        val email = credentials.getEmail().trim()
        val password = credentials.getPassword()
        if (email.isEmpty() || password.isEmpty()) {
            val missing = BlackoutAuthResult.Failure("Informe o e-mail e a senha nas configurações da extensão.")
            lastAuthFailure = missing
            return missing
        }

        val isSameFailedCreds = email == lastFailedEmail && password == lastFailedPassword
        if (isSameFailedCreds && !forceAttempt) {
            return lastAuthFailure ?: BlackoutAuthResult.Failure(
                "Credenciais inválidas. Confira seu e-mail e senha nas configurações da extensão.",
                invalidCredentials = true,
            )
        }
        if (!forceAttempt && !canAttemptLoginLocked(email, password)) {
            return lastAuthFailure ?: BlackoutAuthResult.Failure(
                "Autenticação temporariamente aguardando nova tentativa.",
            )
        }

        cookieStore.syncFromWebView(baseHttpUrl)
        val nonSessionSeed = cookieStore.loadSessionCookies(baseHttpUrl).filterNot {
            it.name == SESSION_COOKIE || it.name == XSRF_COOKIE
        }
        val attemptJar = AttemptCookieJar(baseHttpUrl.host) {
            cookieStore.loadSessionCookies(baseHttpUrl).filterNot {
                it.name == SESSION_COOKIE || it.name == XSRF_COOKIE
            }
        }.apply {
            seed(nonSessionSeed)
        }
        val authClient = ephemeralClientFactory(attemptJar)

        var outcome = executeLoginOnce(authClient, attemptJar, email, password)
        if (outcome.csrfMismatch) {
            attemptJar.clear()
            attemptJar.seed(nonSessionSeed)
            outcome = executeLoginOnce(authClient, attemptJar, email, password)
        }

        if (!outcome.isAuthenticated) {
            verifiedWithServer = false
            if (outcome.invalidCredentials || outcome.requiresManualWebView) {
                lastFailedEmail = email
                lastFailedPassword = password
                transientCooldownUntil = null
            } else {
                transientCooldownUntil = timeSource.markNow() + TRANSIENT_COOLDOWN
            }
            val failure = BlackoutAuthResult.Failure(
                outcome.message.ifBlank { "Não foi possível autenticar na Blackout Comics." },
                invalidCredentials = outcome.invalidCredentials,
                requiresManualWebView = outcome.requiresManualWebView,
            )
            lastAuthFailure = failure
            return failure
        }

        // Persiste cookies confirmados pelo servidor e descarta HTML cacheado como visitante.
        val freshCookies = attemptJar.snapshot()
        if (freshCookies.isNotEmpty()) {
            cookieStore.saveCookies(baseHttpUrl, freshCookies)
        }
        if (currentSessionCookieValue() == null) {
            verifiedWithServer = false
            transientCooldownUntil = timeSource.markNow() + TRANSIENT_COOLDOWN
            val failure = BlackoutAuthResult.Failure("O servidor não retornou o cookie de sessão esperado.")
            lastAuthFailure = failure
            return failure
        }

        lastFailedEmail = ""
        lastFailedPassword = ""
        transientCooldownUntil = null
        lastAuthFailure = null
        verifiedWithServer = true
        managedSessionActive = true
        activeSessionEmail = email
        activeSessionPassword = password
        authenticatedSessionCookie = currentSessionCookieValue()
        cookieStore.evictHttpCache()
        return BlackoutAuthResult.Success
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(BlackoutAuthBypass::class.java) != null ||
            request.url.host != baseHttpUrl.host
        ) {
            return chain.proceed(request)
        }

        val isAsset = isStaticOrMediaRequest(request)
        val isReaderRoute = request.url.encodedPath.contains("/ler/") || isImageDeliveryRequest(request)
        var lastFailure: BlackoutAuthResult.Failure? = null

        synchronized(authLock) {
            syncCredentialStateLocked()
            val email = credentials.getEmail().trim()
            val password = credentials.getPassword()

            if (!isAsset && email.isNotEmpty() && password.isNotEmpty()) {
                val hasCookie = hasSessionCookie()
                val permanentCredFailure = email == lastFailedEmail && password == lastFailedPassword
                val canTry = !permanentCredFailure && (isReaderRoute || canAttemptLoginLocked(email, password))
                if ((!hasCookie || !verifiedWithServer) && canTry) {
                    if (!hasCookie || !confirmExistingCookieWithServer()) {
                        val res = login(forceAttempt = isReaderRoute)
                        if (res is BlackoutAuthResult.Failure) {
                            lastFailure = res
                        }
                    }
                }
            }
        }

        val sessionCookieBefore = currentSessionCookieValue()
        val response = chain.proceed(request)

        if (!shouldRetryAfterResponse(request, response, sessionCookieBefore != null)) {
            if (response.isSuccessful && sessionCookieBefore != null && isVerifiedHtmlResponse(response)) {
                verifiedWithServer = true
                authenticatedSessionCookie = currentSessionCookieValue()
            }
            return guardImageResponse(request, response, lastFailure)
        }

        val renewalResult = synchronized(authLock) {
            syncCredentialStateLocked()
            val email = credentials.getEmail().trim()
            val password = credentials.getPassword()
            if (email.isEmpty() || password.isEmpty()) {
                return@synchronized BlackoutAuthResult.Failure(
                    lastFailure?.message ?: "Configure seu e-mail e senha nas configurações da extensão.",
                )
            }

            val currentCookie = currentSessionCookieValue()
            if (verifiedWithServer &&
                authenticatedSessionCookie != null &&
                currentCookie == authenticatedSessionCookie &&
                currentCookie != sessionCookieBefore
            ) {
                return@synchronized BlackoutAuthResult.Success
            }

            if (!isReaderRoute && !canAttemptLoginLocked(email, password)) {
                return@synchronized BlackoutAuthResult.Failure(
                    lastFailure?.message ?: "Credenciais inválidas ou aguardando nova tentativa.",
                    invalidCredentials = email == lastFailedEmail && password == lastFailedPassword,
                )
            }
            if (email == lastFailedEmail && password == lastFailedPassword) {
                return@synchronized BlackoutAuthResult.Failure(
                    lastAuthFailure?.message ?: "Credenciais inválidas ou ação adicional exigida.",
                    invalidCredentials = true,
                )
            }

            cookieStore.clearSessionCookies(baseHttpUrl)
            verifiedWithServer = false
            authenticatedSessionCookie = null
            managedSessionActive = false
            activeSessionEmail = null
            activeSessionPassword = null

            login(forceAttempt = isReaderRoute)
        }

        if (renewalResult !is BlackoutAuthResult.Success) {
            if (isProtectedImageRequest(request)) {
                response.close()
                throw IOException((renewalResult as BlackoutAuthResult.Failure).message)
            }
            // Preserva respostas públicas (catálogo/detalhes/capas) mesmo quando o login falha.
            return response
        }

        response.close()
        val retryRequest = request.newBuilder()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val retryResponse = chain.proceed(retryRequest)
        return guardImageResponse(retryRequest, retryResponse, null)
    }

    private fun guardImageResponse(
        request: Request,
        response: Response,
        lastFailure: BlackoutAuthResult.Failure?,
    ): Response {
        if (!isProtectedImageRequest(request)) return response
        val finalPath = response.request.url.encodedPath
        val contentType = response.header("Content-Type").orEmpty()
        val isHtmlOrLoginRedirect = finalPath == "/login" ||
            finalPath == "/" ||
            contentType.contains("text/html", ignoreCase = true) ||
            contentType.contains("application/json", ignoreCase = true)
        if (!response.isSuccessful || isHtmlOrLoginRedirect) {
            val code = response.code
            response.close()
            throw IOException(
                lastFailure?.message
                    ?: lastAuthFailure?.message
                    ?: "Falha ao carregar imagem do capítulo (HTTP $code).",
            )
        }
        return response
    }

    private fun confirmExistingCookieWithServer(): Boolean {
        val currentCookies = cookieStore.loadSessionCookies(baseHttpUrl)
        if (currentCookies.none { it.name == SESSION_COOKIE && it.value.isNotBlank() }) {
            return false
        }

        val checkJar = AttemptCookieJar(baseHttpUrl.host).apply { seed(currentCookies) }
        val checkClient = ephemeralClientFactory(checkJar)

        return try {
            val req = buildDocumentRequest("$baseUrl/", "$baseUrl/")
            checkClient.newCall(req).execute().use { res ->
                val html = res.body.string()
                if (res.isSuccessful && isAuthenticatedHtml(html)) {
                    val refreshed = checkJar.snapshot()
                    if (refreshed.isNotEmpty()) cookieStore.saveCookies(baseHttpUrl, refreshed)
                    verifiedWithServer = true
                    authenticatedSessionCookie = currentSessionCookieValue()
                    true
                } else {
                    verifiedWithServer = false
                    authenticatedSessionCookie = null
                    false
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun executeLoginOnce(
        authClient: OkHttpClient,
        attemptJar: AttemptCookieJar,
        email: String,
        password: String,
    ): BlackoutLoginOutcome {
        val csrfToken: String
        try {
            val homeReq = buildDocumentRequest("$baseUrl/", "$baseUrl/")
            authClient.newCall(homeReq).execute().use { homeRes ->
                val html = homeRes.body.string()
                if (!homeRes.isSuccessful) {
                    return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        message = if (isCloudflareChallenge(html)) {
                            "Proteção Cloudflare bloqueou a autenticação (HTTP ${homeRes.code}). Abra a fonte na WebView e tente novamente."
                        } else {
                            mapHttpStatusMessage(homeRes.code)
                        },
                    )
                }
                if (isAuthenticatedHtml(html) && attemptJar.hasSessionCookie()) {
                    return BlackoutLoginOutcome(isAuthenticated = true)
                }
                csrfToken = extractCsrfToken(html) ?: extractXsrfFromJar(attemptJar)
                    ?: return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        message = if (isCloudflareChallenge(html)) {
                            "Proteção Cloudflare impediu o login automático. Abra a fonte na WebView e tente novamente."
                        } else {
                            "Não foi possível obter o token CSRF de autenticação."
                        },
                    )
            }
        } catch (e: IOException) {
            return BlackoutLoginOutcome(
                isAuthenticated = false,
                message = "Falha de rede ao iniciar autenticação: ${e.message ?: "erro de conexão"}",
            )
        }

        val formBody = FormBody.Builder()
            .add("_token", csrfToken)
            .add("USE_EMAIL", email)
            .add("password", password)
            .build()

        val loginHeaders = baseHeadersProvider().newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Origin", baseUrl)
            .build()

        val loginReq = Request.Builder()
            .url("$baseUrl/entrar")
            .headers(loginHeaders)
            .post(formBody)
            .fetch(Fetch.XHR.from("$baseUrl/"))
            .tag(BlackoutAuthBypass::class.java, BlackoutAuthBypass)
            .build()

        val postOutcome = try {
            authClient.newCall(loginReq).execute().use { res ->
                val raw = res.body.string()
                val parsed = parseLoginResponse(res.code, res.request.url.encodedPath, raw)
                if (!parsed.isAuthenticated) {
                    return parsed
                }
                parsed
            }
        } catch (e: IOException) {
            return BlackoutLoginOutcome(
                isAuthenticated = false,
                message = "Falha de rede ao autenticar: ${e.message ?: "erro de conexão"}",
            )
        }

        if (!attemptJar.hasSessionCookie()) {
            return BlackoutLoginOutcome(
                isAuthenticated = false,
                message = "O servidor não retornou o cookie de sessão após o login.",
            )
        }

        // Validação real no servidor: confirma que a página principal não vem mais bloqueada como visitante.
        return try {
            val verifyReq = buildDocumentRequest("$baseUrl/", "$baseUrl/")
            authClient.newCall(verifyReq).execute().use { verifyRes ->
                val html = verifyRes.body.string()
                if (verifyRes.isSuccessful && isAuthenticatedHtml(html)) {
                    postOutcome
                } else {
                    BlackoutLoginOutcome(
                        isAuthenticated = false,
                        message = "O servidor não reconheceu a sessão após o login.",
                    )
                }
            }
        } catch (e: IOException) {
            BlackoutLoginOutcome(
                isAuthenticated = false,
                message = "Falha de rede ao validar sessão: ${e.message ?: "erro de conexão"}",
            )
        }
    }

    private fun buildDocumentRequest(url: String, referer: String): Request {
        val headers = baseHeadersProvider().newBuilder()
            .removeAll("Origin")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()
        return Request.Builder()
            .url(url)
            .headers(headers)
            .get()
            .fetch(Fetch.DOCUMENT.from(referer))
            .cacheControl(CacheControl.FORCE_NETWORK)
            .tag(BlackoutAuthBypass::class.java, BlackoutAuthBypass)
            .build()
    }

    private fun syncCredentialStateLocked() {
        val email = credentials.getEmail().trim()
        val password = credentials.getPassword()
        val hasCreds = email.isNotEmpty() && password.isNotEmpty()

        if (pendingSessionInvalidation) {
            clearManagedSessionLocked()
            return
        }

        if (!hasCreds && managedSessionActive) {
            clearManagedSessionLocked()
            return
        }

        if (hasCreds &&
            activeSessionEmail != null &&
            (email != activeSessionEmail || password != activeSessionPassword)
        ) {
            clearManagedSessionLocked()
            lastFailedEmail = ""
            lastFailedPassword = ""
            transientCooldownUntil = null
        }
    }

    private fun clearManagedSessionLocked() {
        pendingSessionInvalidation = false
        verifiedWithServer = false
        authenticatedSessionCookie = null
        if (managedSessionActive || activeSessionEmail != null || hasSessionCookie()) {
            cookieStore.clearSessionCookies(baseHttpUrl)
            cookieStore.evictHttpCache()
        }
        managedSessionActive = false
        activeSessionEmail = null
        activeSessionPassword = null
    }

    private fun canAttemptLoginLocked(email: String, password: String): Boolean {
        if (email == lastFailedEmail && password == lastFailedPassword) return false
        return transientCooldownUntil?.hasPassedNow() ?: true
    }

    private fun currentSessionCookieValue(): String? = cookieStore.loadSessionCookies(baseHttpUrl)
        .firstOrNull {
            it.name == SESSION_COOKIE &&
                it.value.isNotBlank() &&
                !it.value.equals("deleted", ignoreCase = true)
        }
        ?.value

    private fun shouldRetryAfterResponse(
        request: Request,
        response: Response,
        hadSessionCookie: Boolean,
    ): Boolean {
        if (!hasCredentials()) return false
        if (response.request.url.queryParameter("notif") == "aviso-compra") return false

        val path = request.url.encodedPath
        val isReaderRoute = path.contains("/ler/")
        val isImageDelivery = isImageDeliveryRequest(request)
        val isProjectMedia = path.startsWith("/projects/")

        if (response.code == 401 || response.code == 403) {
            if (hadSessionCookie && (isReaderRoute || isImageDelivery || isProjectMedia || request.method == "GET")) {
                verifiedWithServer = false
                authenticatedSessionCookie = null
                return true
            }
            return false
        }

        val finalPath = response.request.url.encodedPath
        if (path != "/" && path != "/login" && (finalPath == "/login" || finalPath == "/")) {
            verifiedWithServer = false
            authenticatedSessionCookie = null
            return true
        }

        if (isImageDelivery && response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
            verifiedWithServer = false
            authenticatedSessionCookie = null
            return true
        }

        if (!hadSessionCookie || request.method != "GET" || !response.isSuccessful) {
            return false
        }

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return false
        }

        val preview = try {
            response.peekBody(131_072).string()
        } catch (_: IOException) {
            return false
        }
        return isGuestHtml(preview)
    }

    private fun isVerifiedHtmlResponse(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return false
        val preview = try {
            response.peekBody(131_072).string()
        } catch (_: IOException) {
            return false
        }
        return isAuthenticatedHtml(preview)
    }

    private fun extractXsrfFromJar(jar: AttemptCookieJar): String? = jar.snapshot().firstOrNull { it.name == XSRF_COOKIE }?.value?.let {
        java.net.URLDecoder.decode(it, "UTF-8")
    }?.takeIf { it.isNotBlank() }

    private class AttemptCookieJar(
        private val host: String,
        private val fallbackNonSessionCookies: () -> List<Cookie> = { emptyList() },
    ) : CookieJar {
        private val cookies = linkedMapOf<String, Cookie>()

        @Synchronized
        fun seed(initial: List<Cookie>) {
            val now = Clock.System.now().toEpochMilliseconds()
            initial.forEach { cookie ->
                if (cookie.value.isNotEmpty() && cookie.expiresAt > now) {
                    cookies[cookie.name] = cookie
                }
            }
        }

        @Synchronized
        fun clear() {
            cookies.clear()
        }

        @Synchronized
        fun hasSessionCookie(): Boolean = cookies[SESSION_COOKIE]?.value?.takeIf { it.isNotBlank() && !it.equals("deleted", true) } != null

        @Synchronized
        fun snapshot(): List<Cookie> = cookies.values.toList()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = Clock.System.now().toEpochMilliseconds()
            cookies.forEach { incoming ->
                if (incoming.expiresAt <= now ||
                    incoming.value.isEmpty() ||
                    incoming.value.equals("deleted", ignoreCase = true)
                ) {
                    this.cookies.remove(incoming.name)
                } else {
                    this.cookies[incoming.name] = incoming
                }
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (url.host != host && !url.host.endsWith(".$host")) return emptyList()
            val merged = linkedMapOf<String, Cookie>()
            fallbackNonSessionCookies().forEach { merged[it.name] = it }
            cookies.forEach { (name, cookie) -> merged[name] = cookie }
            return merged.values.toList()
        }
    }

    companion object {
        const val SESSION_COOKIE = "blackout-comics-session"
        const val XSRF_COOKIE = "XSRF-TOKEN"
        private val TRANSIENT_COOLDOWN = 5.seconds

        private val CSRF_META_REGEX = Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val CSRF_INPUT_REGEX = Regex("""name=["']_token["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ADULT_LOCKED_CLASS_REGEX = Regex("""<body\b[^>]*\badult-content-locked\b""", RegexOption.IGNORE_CASE)
        private val BODY_BLACKOUT_REGEX = Regex("""<body\b[^>]*id=["']body-blackout["']""", RegexOption.IGNORE_CASE)
        private val FORM_MODAL_LOGIN_REGEX = Regex("""<form\b[^>]*\bform-modal-login\b""", RegexOption.IGNORE_CASE)
        private val TOGGLE_LOGIN_REGEX = Regex("""\bid=["']toggle-login["']""", RegexOption.IGNORE_CASE)
        private val STATIC_ASSET_EXT_REGEX = Regex("""\.(?:jpe?g|png|webp|gif|avif|svg|css|js|woff2?)(?:\?.*)?$""", RegexOption.IGNORE_CASE)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun extractCsrfToken(html: String): String? = CSRF_META_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: CSRF_INPUT_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        internal fun isCloudflareChallenge(html: String): Boolean = html.contains("cf-error-details", ignoreCase = true) ||
            html.contains("_cf_chl_opt", ignoreCase = true) ||
            html.contains("Attention Required! | Cloudflare", ignoreCase = true)

        internal fun isGuestHtml(html: String): Boolean {
            if (ADULT_LOCKED_CLASS_REGEX.containsMatchIn(html)) return true
            if (html.contains("class=\"content-lock-alert\"", ignoreCase = true)) return true
            if (FORM_MODAL_LOGIN_REGEX.containsMatchIn(html)) return true
            if (TOGGLE_LOGIN_REGEX.containsMatchIn(html)) return true
            return html.contains("showLoginModal()", ignoreCase = true)
        }

        internal fun isAuthenticatedHtml(html: String): Boolean {
            if (isGuestHtml(html)) return false
            return BODY_BLACKOUT_REGEX.containsMatchIn(html) ||
                html.contains("csrf-token", ignoreCase = true)
        }

        internal fun isImageDeliveryRequest(request: Request): Boolean = request.url.encodedPath.startsWith("/image/delivery/")

        internal fun isProtectedImageRequest(request: Request): Boolean = isImageDeliveryRequest(request) ||
            request.tag(Fetch.Kind::class.java) == Fetch.Kind.IMAGE ||
            request.header("Accept").orEmpty().startsWith("image/", ignoreCase = true)

        internal fun isStaticOrMediaRequest(request: Request): Boolean {
            val path = request.url.encodedPath
            if (isImageDeliveryRequest(request)) {
                return false
            }
            if (path.startsWith("/build/") || path.startsWith("/media/") || path.startsWith("/projects/")) {
                return true
            }
            if (STATIC_ASSET_EXT_REGEX.containsMatchIn(path)) {
                return true
            }
            return request.header("Accept").orEmpty().startsWith("image/", ignoreCase = true)
        }

        internal fun parseLoginResponse(statusCode: Int, finalPath: String, rawBody: String): BlackoutLoginOutcome {
            if (statusCode == 419) {
                return BlackoutLoginOutcome(
                    isAuthenticated = false,
                    csrfMismatch = true,
                    message = "Token CSRF expirado durante o login.",
                )
            }

            val trimmed = rawBody.trim()
            val root = try {
                if (trimmed.startsWith("{")) json.parseToJsonElement(trimmed) as? JsonObject else null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

            if (root != null) {
                val twoFactor = (root["two_factor_required"] as? JsonPrimitive)?.booleanOrNull == true
                if (twoFactor) {
                    return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        requiresManualWebView = true,
                        message = "Verificação em duas etapas exigida. Faça login na WebView para concluir.",
                    )
                }

                val emailVerify = (root["email_verification_required"] as? JsonPrimitive)?.booleanOrNull == true
                if (emailVerify) {
                    val msg = (root["error"] as? JsonPrimitive)?.contentOrNull
                        ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                        ?: "Conta não ativada. Verifique seu e-mail."
                    return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        requiresManualWebView = true,
                        message = msg,
                    )
                }

                val securityUpdate = (root["security_update_required"] as? JsonPrimitive)?.booleanOrNull == true
                if (securityUpdate) {
                    return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        requiresManualWebView = true,
                        message = "O site exige atualização da senha. Acesse pela WebView.",
                    )
                }

                val errorMsg = extractJsonError(root)
                if (errorMsg != null) {
                    val invalidCreds = statusCode == 401 || statusCode == 422 ||
                        errorMsg.contains("Credenciais", ignoreCase = true) ||
                        errorMsg.contains("senha", ignoreCase = true) ||
                        errorMsg.contains("email", ignoreCase = true)
                    return BlackoutLoginOutcome(
                        isAuthenticated = false,
                        invalidCredentials = invalidCreds,
                        message = errorMsg,
                    )
                }

                val successMsg = (root["success"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                if (statusCode in 200..299 && successMsg != null) {
                    return BlackoutLoginOutcome(isAuthenticated = true)
                }
            }

            if (statusCode in 200..299 && !trimmed.contains("form-modal-login", ignoreCase = true) && finalPath != "/login") {
                return BlackoutLoginOutcome(isAuthenticated = true)
            }

            if (statusCode == 401 || statusCode == 422) {
                return BlackoutLoginOutcome(
                    isAuthenticated = false,
                    invalidCredentials = true,
                    message = "Credenciais inválidas.",
                )
            }

            return BlackoutLoginOutcome(
                isAuthenticated = false,
                message = mapHttpStatusMessage(statusCode),
            )
        }

        private fun extractJsonError(root: JsonObject): String? {
            val directError = (root["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (directError != null) return directError

            val errorsObj = root["errors"] as? JsonObject
            if (errorsObj != null) {
                val firstErr = errorsObj.values.firstNotNullOfOrNull { element ->
                    when (element) {
                        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.contentOrNull
                        is JsonPrimitive -> element.contentOrNull
                        else -> null
                    }
                }
                if (!firstErr.isNullOrBlank()) return firstErr
            }

            val successFalse = (root["success"] as? JsonPrimitive)?.booleanOrNull == false
            val resultFalse = (root["result"] as? JsonPrimitive)?.booleanOrNull == false
            if (successFalse || resultFalse) {
                return (root["message"] as? JsonPrimitive)?.contentOrNull ?: "Falha na autenticação."
            }

            return null
        }

        private fun mapHttpStatusMessage(statusCode: Int): String = when (statusCode) {
            401 -> "Sessão expirada ou não autorizada."
            403 -> "Acesso recusado pelo servidor (HTTP 403)."
            419 -> "Token CSRF expirado."
            422 -> "Credenciais inválidas."
            429 -> "Muitas tentativas. Aguarde alguns minutos."
            503, 504 -> "Servidor temporariamente indisponível (HTTP $statusCode)."
            else -> "Não foi possível autenticar (HTTP $statusCode)."
        }
    }
}
