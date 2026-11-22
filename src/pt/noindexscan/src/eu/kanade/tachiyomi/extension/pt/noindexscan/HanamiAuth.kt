package eu.kanade.tachiyomi.extension.pt.noindexscan

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object HanamiAuthBypass

internal data class HanamiAjaxOutcome(
    val isSuccess: Boolean,
    val nonce: String? = null,
    val code: String? = null,
    val message: String? = null,
    val explicitResultFalse: Boolean = false,
)

internal interface HanamiCredentialStore {
    fun getEmail(): String
    fun getPassword(): String
}

internal interface HanamiCookieStore {
    fun loadSessionCookies(url: HttpUrl): List<Cookie>
    fun syncFromWebView(url: HttpUrl): Boolean
    fun saveFromHeaders(url: HttpUrl, setCookieHeaders: List<String>)
    fun clearSessionCookies(url: HttpUrl)
    fun evictHttpCache()
}

internal sealed class HanamiLoginResult {
    data object Success : HanamiLoginResult()
    data class Failure(
        val message: String,
        val invalidCredentials: Boolean = false,
    ) : HanamiLoginResult()
}

/**
 * Gerencia autenticação automática para a Hanami Heaven sem bloquear o catálogo público.
 */
internal class HanamiSessionManager(
    private val baseUrl: String,
    private val credentials: HanamiCredentialStore,
    private val cookieStore: HanamiCookieStore,
    private val httpClientProvider: () -> OkHttpClient,
    private val baseHeadersProvider: () -> Headers,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val baseHttpUrl: HttpUrl by lazy { baseUrl.toHttpUrl() }
    private val ajaxUrl: String get() = "$baseUrl/wp-admin/admin-ajax.php"
    private val wpLoginUrl: String get() = "$baseUrl/wp-login.php"
    private val authLock = Any()

    @Volatile
    private var lastFailedEmail: String = ""

    @Volatile
    private var lastFailedPassword: String = ""

    @Volatile
    private var transientCooldownUntil: TimeMark? = null

    @Volatile
    private var activeSessionEmail: String? = null

    @Volatile
    private var activeSessionPassword: String? = null

    @Volatile
    private var managedSessionActive: Boolean = false

    @Volatile
    private var pendingSessionInvalidation: Boolean = false

    fun hasCredentials(): Boolean = credentials.getEmail().isNotBlank() && credentials.getPassword().isNotEmpty()

    fun hasSession(): Boolean {
        if (hasClientSessionCookie()) return true
        return cookieStore.syncFromWebView(baseHttpUrl) && hasClientSessionCookie()
    }

    fun onCredentialsChanged() {
        lastFailedEmail = ""
        lastFailedPassword = ""
        transientCooldownUntil = null
        pendingSessionInvalidation = true
    }

    fun invalidateSession() = synchronized(authLock) {
        onCredentialsChanged()
        clearManagedSessionLocked()
    }

    fun login(forceAttempt: Boolean = false): HanamiLoginResult = synchronized(authLock) {
        if (pendingSessionInvalidation) {
            clearManagedSessionLocked()
        }

        val email = credentials.getEmail().trim()
        val password = credentials.getPassword()
        if (email.isEmpty() || password.isEmpty()) {
            return HanamiLoginResult.Failure("Informe o e-mail e a senha nas configurações da extensão.")
        }

        if (!forceAttempt && !canAttemptLoginLocked(email, password)) {
            val isCredFail = email == lastFailedEmail && password == lastFailedPassword
            return HanamiLoginResult.Failure(
                if (isCredFail) {
                    "Credenciais inválidas. Confira seu e-mail e senha nas configurações da extensão."
                } else {
                    "Autenticação temporariamente aguardando nova tentativa."
                },
                invalidCredentials = isCredFail,
            )
        }

        val nonceResult = fetchNonce()
        var attempt: LoginAttempt = when (nonceResult) {
            is NonceResult.Ok -> {
                val first = executeAjaxLogin(email, password, nonceResult.nonce)
                if (first is LoginAttempt.ExpiredNonce) {
                    when (val refreshed = fetchNonce()) {
                        is NonceResult.Ok -> executeAjaxLogin(email, password, refreshed.nonce)
                        is NonceResult.Err -> LoginAttempt.Error(refreshed.message, invalidCredentials = false)
                    }
                } else {
                    first
                }
            }
            is NonceResult.Err -> LoginAttempt.Error(nonceResult.message, invalidCredentials = false)
        }

        if (attempt is LoginAttempt.Error && !attempt.invalidCredentials) {
            val fallback = executeWpLoginFallback(email, password)
            if (fallback is LoginAttempt.Ok || fallback.invalidCredentials) {
                attempt = fallback
            }
        }

        return when (attempt) {
            is LoginAttempt.Ok -> {
                if (hasClientSessionCookie() || hasSession()) {
                    lastFailedEmail = ""
                    lastFailedPassword = ""
                    transientCooldownUntil = null
                    activeSessionEmail = email
                    activeSessionPassword = password
                    managedSessionActive = true
                    cookieStore.evictHttpCache()
                    HanamiLoginResult.Success
                } else {
                    transientCooldownUntil = timeSource.markNow() + TRANSIENT_COOLDOWN
                    HanamiLoginResult.Failure("O servidor não retornou o cookie de sessão esperado.")
                }
            }
            is LoginAttempt.Error -> {
                if (attempt.invalidCredentials) {
                    lastFailedEmail = email
                    lastFailedPassword = password
                    transientCooldownUntil = null
                } else {
                    transientCooldownUntil = timeSource.markNow() + TRANSIENT_COOLDOWN
                }
                HanamiLoginResult.Failure(attempt.message, attempt.invalidCredentials)
            }
            is LoginAttempt.ExpiredNonce -> {
                transientCooldownUntil = timeSource.markNow() + TRANSIENT_COOLDOWN
                HanamiLoginResult.Failure("Não foi possível validar o token de segurança do login.")
            }
        }
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(HanamiAuthBypass::class.java) != null ||
            request.url.host != baseHttpUrl.host ||
            isStaticAssetRequest(request)
        ) {
            return chain.proceed(request)
        }

        synchronized(authLock) {
            syncCredentialStateLocked()
            val email = credentials.getEmail().trim()
            val password = credentials.getPassword()
            if (email.isNotEmpty() &&
                password.isNotEmpty() &&
                !hasSession() &&
                canAttemptLoginLocked(email, password)
            ) {
                // Falhas de login nunca devem bloquear o catálogo público.
                login()
            }
        }

        val sessionCookieBefore = currentSessionCookieValue()
        val response = chain.proceed(request)

        if (sessionCookieBefore == null || !isSessionExpiredResponse(request, response)) {
            return response
        }

        val renewed = synchronized(authLock) {
            syncCredentialStateLocked()
            val email = credentials.getEmail().trim()
            val password = credentials.getPassword()
            if (email.isEmpty() || password.isEmpty()) {
                return@synchronized false
            }

            val latestSessionCookie = currentSessionCookieValue()
            if (latestSessionCookie != null && latestSessionCookie != sessionCookieBefore) {
                return@synchronized true
            }

            if (!canAttemptLoginLocked(email, password)) {
                return@synchronized false
            }

            cookieStore.clearSessionCookies(baseHttpUrl)
            managedSessionActive = false
            activeSessionEmail = null
            activeSessionPassword = null

            login() is HanamiLoginResult.Success
        }

        if (!renewed) {
            return response
        }

        response.close()
        val retryRequest = request.newBuilder()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        return chain.proceed(retryRequest)
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
        if (managedSessionActive || activeSessionEmail != null || hasClientSessionCookie()) {
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

    private fun hasClientSessionCookie(): Boolean = currentSessionCookieValue() != null

    private fun currentSessionCookieValue(): String? = cookieStore.loadSessionCookies(baseHttpUrl)
        .firstOrNull {
            it.name.startsWith(WP_LOGGED_IN_PREFIX) &&
                it.value.isNotBlank() &&
                !it.value.equals("deleted", ignoreCase = true)
        }
        ?.value

    private sealed class NonceResult {
        class Ok(val nonce: String) : NonceResult()
        class Err(val message: String) : NonceResult()
    }

    private sealed class LoginAttempt {
        open val invalidCredentials: Boolean = false
        data object Ok : LoginAttempt()
        data object ExpiredNonce : LoginAttempt()
        class Error(val message: String, override val invalidCredentials: Boolean) : LoginAttempt()
    }

    private fun fetchNonce(): NonceResult {
        val body = FormBody.Builder()
            .add("action", ACTION_GET_NONCE)
            .build()
        val request = buildAjaxRequest(body)

        var networkErrorMessage: String? = null
        try {
            httpClientProvider().newCall(request).execute().use { response ->
                val raw = response.body.string()
                val outcome = parseAjaxOutcome(raw)
                val nonce = outcome.nonce?.takeIf { it.isNotBlank() }
                if (response.isSuccessful && outcome.isSuccess && nonce != null) {
                    return NonceResult.Ok(nonce)
                }
            }
        } catch (e: IOException) {
            networkErrorMessage = e.message
        }

        return when (val pageResult = fetchNonceFromLoginPage()) {
            is NonceResult.Ok -> pageResult
            is NonceResult.Err -> NonceResult.Err(
                networkErrorMessage?.let { "Falha de rede ao iniciar autenticação: $it" } ?: pageResult.message,
            )
        }
    }

    private fun fetchNonceFromLoginPage(): NonceResult {
        val headers = baseHeadersProvider().newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        val request = Request.Builder()
            .url("$baseUrl/entrar/")
            .headers(headers)
            .get()
            .tag(HanamiAuthBypass::class.java, HanamiAuthBypass)
            .build()

        return try {
            httpClientProvider().newCall(request).execute().use { response ->
                val html = response.body.string()
                val nonce = NONCE_HTML_REGEX.find(html)?.groupValues?.getOrNull(1)
                if (response.isSuccessful && !nonce.isNullOrBlank()) {
                    NonceResult.Ok(nonce)
                } else {
                    NonceResult.Err("Não foi possível obter o token de autenticação (HTTP ${response.code}).")
                }
            }
        } catch (e: IOException) {
            NonceResult.Err("Falha de rede ao iniciar autenticação: ${e.message ?: "erro de conexão"}")
        }
    }

    private fun executeAjaxLogin(email: String, password: String, nonce: String): LoginAttempt {
        val body = FormBody.Builder()
            .add("action", ACTION_LOGIN)
            .add("redirect_to", "$baseUrl/")
            .add("user_login", email)
            .add("user_pass", password)
            .add("nonce", nonce)
            .build()
        val request = buildAjaxRequest(body)

        return try {
            httpClientProvider().newCall(request).execute().use { response ->
                val raw = response.body.string()
                val outcome = parseAjaxOutcome(raw)

                if (outcome.code == "expired_nonce" || raw.trim() == "-1") {
                    return LoginAttempt.ExpiredNonce
                }

                if (response.isSuccessful && outcome.isSuccess) {
                    saveResponseCookies(response)
                    LoginAttempt.Ok
                } else {
                    val invalidCreds = outcome.code == "invalid_credentials" ||
                        outcome.code == "required" ||
                        response.code == 401
                    LoginAttempt.Error(mapHttpError(response.code, outcome), invalidCreds)
                }
            }
        } catch (e: IOException) {
            LoginAttempt.Error(
                "Falha de rede ao autenticar: ${e.message ?: "erro de conexão"}",
                invalidCredentials = false,
            )
        }
    }

    private fun executeWpLoginFallback(email: String, password: String): LoginAttempt {
        val body = FormBody.Builder()
            .add("log", email)
            .add("pwd", password)
            .add("wp-submit", "Acessar")
            .add("redirect_to", "$baseUrl/")
            .add("testcookie", "1")
            .build()
        val headers = baseHeadersProvider().newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Origin", baseUrl)
            .set("Referer", wpLoginUrl)
            .build()
        val request = Request.Builder()
            .url(wpLoginUrl)
            .headers(headers)
            .post(body)
            .tag(HanamiAuthBypass::class.java, HanamiAuthBypass)
            .build()
        val testCookieJar = object : CookieJar {
            private val sessionCookies = mutableListOf(
                Cookie.Builder()
                    .domain(baseHttpUrl.host)
                    .path("/")
                    .name("wordpress_test_cookie")
                    .value("WP%20Cookie%20check")
                    .build(),
            )

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { incoming ->
                    sessionCookies.removeAll { it.name == incoming.name }
                    if (incoming.value.isNotEmpty() && incoming.expiresAt > 0L) {
                        sessionCookies.add(incoming)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = if (url.host == baseHttpUrl.host) sessionCookies.toList() else emptyList()
        }
        val wpLoginClient = httpClientProvider().newBuilder()
            .cookieJar(testCookieJar)
            .build()

        return try {
            wpLoginClient.newCall(request).execute().use { response ->
                saveResponseCookies(response)
                if (hasClientSessionCookie()) {
                    return LoginAttempt.Ok
                }
                val html = response.body.string()
                val hasLoginError = WP_LOGIN_ERROR_REGEX.containsMatchIn(html)
                LoginAttempt.Error(
                    if (hasLoginError) {
                        "Credenciais inválidas. Confira seu e-mail e senha."
                    } else {
                        "Não foi possível concluir o login padrão (HTTP ${response.code})."
                    },
                    invalidCredentials = hasLoginError,
                )
            }
        } catch (e: IOException) {
            LoginAttempt.Error(
                "Falha de rede no login padrão: ${e.message ?: "erro de conexão"}",
                invalidCredentials = false,
            )
        }
    }

    private fun saveResponseCookies(response: Response) {
        val allSetCookies = mutableListOf<String>()
        var current: Response? = response
        while (current != null) {
            allSetCookies += current.headers("Set-Cookie")
            current = current.priorResponse
        }
        if (allSetCookies.isNotEmpty()) {
            cookieStore.saveFromHeaders(baseHttpUrl, allSetCookies)
        }
    }

    private fun buildAjaxRequest(body: FormBody): Request {
        val headers = baseHeadersProvider().newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/entrar/")
            .build()

        return Request.Builder()
            .url(ajaxUrl)
            .headers(headers)
            .post(body)
            .tag(HanamiAuthBypass::class.java, HanamiAuthBypass)
            .build()
    }

    private fun mapHttpError(statusCode: Int, outcome: HanamiAjaxOutcome): String {
        outcome.message?.takeIf { it.isNotBlank() }?.let { return it }
        if (outcome.explicitResultFalse) {
            return "O servidor recusou a resposta de autenticação (result false)."
        }
        return when (statusCode) {
            401 -> "Credenciais inválidas ou sessão expirada."
            403 -> "Acesso recusado pelo servidor (HTTP 403)."
            429 -> "Muitas tentativas de login. Aguarde alguns minutos."
            503, 504 -> "Servidor temporariamente indisponível (HTTP $statusCode)."
            else -> "Não foi possível autenticar (HTTP $statusCode)."
        }
    }

    companion object {
        const val WP_LOGGED_IN_PREFIX = "wordpress_logged_in_"
        const val ACTION_GET_NONCE = "noindexscan_get_nonce"
        const val ACTION_LOGIN = "noindexscan_login"
        private val TRANSIENT_COOLDOWN = 60.seconds

        private val BODY_TAG_REGEX = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val LOGGED_IN_CLASS_REGEX = Regex("""\blogged-in\b""", RegexOption.IGNORE_CASE)
        private val WP_THEME_CLASS_REGEX = Regex("""\b(?:wp-theme-madara|mch-site|wp-manga-page|mch-catalogue-page)\b""", RegexOption.IGNORE_CASE)
        private val IMAGE_EXT_REGEX = Regex("""\.(?:jpe?g|png|webp|gif|avif|svg|css|js|woff2?)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
        private val NONCE_HTML_REGEX = Regex(""""nonce"\s*:\s*"([a-zA-Z0-9_-]+)"""")
        private val WP_LOGIN_ERROR_REGEX = Regex("""id=["']login_error["']""", RegexOption.IGNORE_CASE)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun parseAjaxOutcome(raw: String): HanamiAjaxOutcome {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "0" || trimmed == "-1") {
                return HanamiAjaxOutcome(
                    isSuccess = false,
                    code = if (trimmed == "-1") "expired_nonce" else null,
                )
            }
            if (trimmed.equals("false", ignoreCase = true) || trimmed.equals("result false", ignoreCase = true)) {
                return HanamiAjaxOutcome(isSuccess = false, explicitResultFalse = true)
            }

            val root = try {
                json.parseToJsonElement(trimmed) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return HanamiAjaxOutcome(isSuccess = false)

            val successFlag = (root["success"] as? JsonPrimitive)?.booleanOrNull
            val resultFlag = (root["result"] as? JsonPrimitive)?.booleanOrNull
            val isSuccess = (successFlag ?: resultFlag) == true
            val explicitResultFalse = resultFlag == false

            val dataElement = root["data"]
            val dataObj = dataElement as? JsonObject
            val dataPrimitiveMsg = (dataElement as? JsonPrimitive)?.contentOrNull

            val nonce = (dataObj?.get("nonce") as? JsonPrimitive)?.contentOrNull
                ?: (root["nonce"] as? JsonPrimitive)?.contentOrNull
            val code = (dataObj?.get("code") as? JsonPrimitive)?.contentOrNull
                ?: (root["code"] as? JsonPrimitive)?.contentOrNull
            val message = (dataObj?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: dataPrimitiveMsg
                ?: (root["message"] as? JsonPrimitive)?.contentOrNull

            return HanamiAjaxOutcome(
                isSuccess = isSuccess,
                nonce = nonce,
                code = code,
                message = message,
                explicitResultFalse = explicitResultFalse,
            )
        }

        internal fun isStaticAssetRequest(request: Request): Boolean {
            val path = request.url.encodedPath
            if (path.startsWith("/wp-content/") || path.startsWith("/wp-includes/")) {
                return true
            }
            if (IMAGE_EXT_REGEX.containsMatchIn(path)) {
                return true
            }
            val accept = request.header("Accept").orEmpty()
            return accept.startsWith("image/", ignoreCase = true)
        }

        internal fun isSessionExpiredResponse(request: Request, response: Response): Boolean {
            if (request.method != "GET") return false
            val finalPath = response.request.url.encodedPath
            if (finalPath.startsWith("/entrar") || finalPath.startsWith("/wp-login.php")) {
                return true
            }
            if (!response.isSuccessful && response.code != 404) {
                return false
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.contains("text/html", ignoreCase = true)) {
                return false
            }
            val htmlPrefix = try {
                response.peekBody(131_072).string()
            } catch (_: IOException) {
                return false
            }
            return isGuestHtml(htmlPrefix)
        }

        internal fun isGuestHtml(htmlPrefix: String): Boolean {
            val bodyTag = BODY_TAG_REGEX.find(htmlPrefix)?.value ?: return false
            if (!WP_THEME_CLASS_REGEX.containsMatchIn(bodyTag)) {
                return false
            }
            return !LOGGED_IN_CLASS_REGEX.containsMatchIn(bodyTag)
        }
    }
}
