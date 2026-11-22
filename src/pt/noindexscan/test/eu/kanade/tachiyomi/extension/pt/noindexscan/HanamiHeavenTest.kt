package eu.kanade.tachiyomi.extension.pt.noindexscan

import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HanamiHeavenTest {

    private val baseUrl = "https://hanamiheaven.org"

    private class MemoryCredentialStore(
        var storedEmail: String = "",
        var storedPassword: String = "",
    ) : HanamiCredentialStore {
        override fun getEmail() = storedEmail
        override fun getPassword() = storedPassword
    }

    private class MemoryCookieStore(
        val jar: InMemoryCookieJar = InMemoryCookieJar(),
        var webViewCookies: String = "",
    ) : HanamiCookieStore {
        var cacheEvictions = 0

        override fun loadSessionCookies(url: HttpUrl): List<Cookie> = jar.loadForRequest(url)

        override fun syncFromWebView(url: HttpUrl): Boolean {
            if (webViewCookies.isBlank()) return false
            val parsed = parseCookieHeader(url, webViewCookies)
            if (parsed.isEmpty()) return false
            jar.saveFromResponse(url, parsed)
            return parsed.any {
                it.name.startsWith(HanamiSessionManager.WP_LOGGED_IN_PREFIX) &&
                    !it.value.equals("deleted", ignoreCase = true)
            }
        }

        override fun saveFromHeaders(url: HttpUrl, setCookieHeaders: List<String>) {
            val parsed = setCookieHeaders.mapNotNull { Cookie.parse(url, it) }
            jar.saveFromResponse(url, parsed)
        }

        override fun clearSessionCookies(url: HttpUrl) {
            webViewCookies = ""
            jar.clearPrefix(url.host, "wordpress_")
            jar.clearPrefix(url.host, "wp-settings")
            jar.clearPrefix(url.host, "_lscache_vary")
        }

        override fun evictHttpCache() {
            cacheEvictions++
        }
    }

    private class InMemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { incoming ->
                list.removeAll { it.name == incoming.name }
                if (incoming.expiresAt > 0L &&
                    incoming.value.isNotEmpty() &&
                    !incoming.value.equals("deleted", ignoreCase = true)
                ) {
                    list.add(incoming)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty().toList()

        fun clearPrefix(host: String, prefix: String) {
            store[host]?.removeAll { it.name.startsWith(prefix) }
        }
    }

    private fun buildClient(
        cookieStore: MemoryCookieStore,
        sessionRef: () -> HanamiSessionManager,
        handler: (Request, String) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieStore.jar)
        .addInterceptor { chain -> sessionRef().intercept(chain) }
        .addInterceptor { chain ->
            val req = chain.request()
            val cookieHeader = req.header("Cookie") ?: cookieStore.loadSessionCookies(req.url)
                .joinToString("; ") { "${it.name}=${it.value}" }
            val effectiveReq = if (cookieHeader.isNotEmpty() && req.header("Cookie") == null) {
                req.newBuilder().header("Cookie", cookieHeader).build()
            } else {
                req
            }
            val bodyStr = Buffer().also { effectiveReq.body?.writeTo(it) }.readUtf8()
            handler(effectiveReq, bodyStr)
        }
        .build()

    private fun buildAuthClient(
        handler: (Request, String) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            val req = chain.request()
            val bodyStr = Buffer().also { req.body?.writeTo(it) }.readUtf8()
            handler(req, bodyStr)
        }
        .build()

    private fun jsonResponse(
        request: Request,
        code: Int,
        json: String,
        setCookies: List<String> = emptyList(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .header("Content-Type", "application/json; charset=utf-8")
            .body(json.toResponseBody("application/json".toMediaType()))
        setCookies.forEach { builder.addHeader("Set-Cookie", it) }
        return builder.build()
    }

    private fun textResponse(
        request: Request,
        code: Int,
        body: String,
        setCookies: List<String> = emptyList(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .header("Content-Type", "text/plain; charset=utf-8")
            .body(body.toResponseBody("text/plain".toMediaType()))
        setCookies.forEach { builder.addHeader("Set-Cookie", it) }
        return builder.build()
    }

    private fun htmlResponse(
        request: Request,
        code: Int,
        html: String,
        setCookies: List<String> = emptyList(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .header("Content-Type", "text/html; charset=UTF-8")
            .body(html.toResponseBody("text/html".toMediaType()))
        setCookies.forEach { builder.addHeader("Set-Cookie", it) }
        return builder.build()
    }

    @Test
    fun resultFalseAndFlexibleAjaxResponsesAreInterpretedAndFallbackToWpLoginWorks() {
        val literalFalse = HanamiSessionManager.parseAjaxOutcome("result false")
        assertFalse(literalFalse.isSuccess)
        assertTrue(literalFalse.explicitResultFalse)

        val jsonResultFalse = HanamiSessionManager.parseAjaxOutcome("""{"result":false,"message":"Falha"}""")
        assertFalse(jsonResultFalse.isSuccess)
        assertTrue(jsonResultFalse.explicitResultFalse)
        assertEquals("Falha", jsonResultFalse.message)

        val stringDataErr = HanamiSessionManager.parseAjaxOutcome("""{"success":false,"data":"Erro textual"}""")
        assertFalse(stringDataErr.isSuccess)
        assertEquals("Erro textual", stringDataErr.message)

        val wpZero = HanamiSessionManager.parseAjaxOutcome("0")
        assertFalse(wpZero.isSuccess)

        val creds = MemoryCredentialStore("reader@example.com", "secret1234")
        val cookies = MemoryCookieStore()
        var wpLoginAttempts = 0

        val authHandler: (Request, String) -> Response = { req, body ->
            when {
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_get_nonce" in body ->
                    jsonResponse(req, 200, """{"success":true,"data":{"nonce":"n1"}}""")
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_login" in body ->
                    textResponse(req, 200, "result false")
                req.url.encodedPath == "/wp-login.php" -> {
                    wpLoginAttempts++
                    assertTrue("log=reader%40example.com" in body)
                    assertTrue("pwd=secret1234" in body)
                    htmlResponse(
                        req,
                        200,
                        """<html><body class="logged-in mch-site">OK</body></html>""",
                        listOf("wordpress_logged_in_fb=fallback_tok; Path=/; HttpOnly; Secure"),
                    )
                }
                else -> htmlResponse(req, 200, """<html><body class="logged-in mch-site"></body></html>""")
            }
        }

        val authClient = buildAuthClient(authHandler)
        val manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        val result = manager.login()
        assertTrue(result is HanamiLoginResult.Success)
        assertEquals(1, wpLoginAttempts)
        assertTrue(manager.hasSession())
    }

    @Test
    fun loginFailureNeverBreaksPublicCatalogAndPreventsLoop() {
        val creds = MemoryCredentialStore("reader@example.com", "wrong-or-blocked")
        val cookies = MemoryCookieStore()
        var ajaxLoginCalls = 0
        var wpLoginCalls = 0
        var catalogCalls = 0

        val handler: (Request, String) -> Response = { req, body ->
            when {
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_get_nonce" in body ->
                    jsonResponse(req, 200, """{"success":true,"data":{"nonce":"n1"}}""")
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_login" in body -> {
                    ajaxLoginCalls++
                    jsonResponse(req, 200, """{"result":false}""")
                }
                req.url.encodedPath == "/wp-login.php" -> {
                    wpLoginCalls++
                    htmlResponse(req, 200, """<html><body><div id="login_error">Erro</div></body></html>""")
                }
                else -> {
                    catalogCalls++
                    htmlResponse(
                        req,
                        200,
                        """<html><body class="mch-site mch-catalogue-page"><li class="mch-catalogue-card" data-work-id="7018">Public Work</li></body></html>""",
                    )
                }
            }
        }

        val authClient = buildAuthClient(handler)
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        // Even with failing login ("result false"), catalog loads normally without throwing IOException.
        val firstCatalog = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/?ordem=rating").build()).execute()
        assertEquals(200, firstCatalog.code)
        assertTrue("Public Work" in firstCatalog.body.string())
        firstCatalog.close()

        // Second catalog request does not repeat failed login attempt in a loop.
        val secondCatalog = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, secondCatalog.code)
        secondCatalog.close()

        assertEquals(2, catalogCalls)
        assertEquals(1, ajaxLoginCalls)
        assertEquals(1, wpLoginCalls)
        assertFalse(manager.hasSession())
    }

    @Test
    fun invalidCredentialsPreventRepeatedAttemptsAndPreservePublicCatalog() {
        val creds = MemoryCredentialStore("wrong@example.com", "badpass")
        val cookies = MemoryCookieStore()
        var ajaxLoginCalls = 0
        var wpLoginCalls = 0

        val handler: (Request, String) -> Response = { req, body ->
            when {
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_get_nonce" in body ->
                    jsonResponse(req, 200, """{"success":true,"data":{"nonce":"n1"}}""")
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_login" in body -> {
                    ajaxLoginCalls++
                    jsonResponse(
                        req,
                        401,
                        """{"success":false,"data":{"code":"invalid_credentials","message":"Não foi possível entrar. Confira seus dados ou use o login padrão."}}""",
                    )
                }
                req.url.encodedPath == "/wp-login.php" -> {
                    wpLoginCalls++
                    htmlResponse(req, 200, "<html></html>")
                }
                else -> htmlResponse(req, 200, """<html><body class="mch-site"><li class="mch-catalogue-card" data-work-id="7351">Small Beast</li></body></html>""")
            }
        }

        val authClient = buildAuthClient(handler)
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        val res = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, res.code)
        assertTrue("Small Beast" in res.body.string())
        res.close()

        val passiveSecond = manager.login(forceAttempt = false)
        assertTrue(passiveSecond is HanamiLoginResult.Failure)
        assertTrue((passiveSecond as HanamiLoginResult.Failure).invalidCredentials)
        assertEquals(1, ajaxLoginCalls)
        assertEquals(0, wpLoginCalls)
    }

    @Test
    fun autoLoginAfterFillingCredentialsPersistsSessionAndUnlocksRestrictedWorks() {
        val creds = MemoryCredentialStore("user@example.com", "secret1234")
        val cookies = MemoryCookieStore()
        var nonceCalls = 0
        var loginCalls = 0
        var lastCookieSeen = ""

        val handler: (Request, String) -> Response = { req, body ->
            when {
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_get_nonce" in body -> {
                    nonceCalls++
                    jsonResponse(req, 200, """{"success":true,"data":{"nonce":"nonce-1"}}""")
                }
                req.url.encodedPath == "/wp-admin/admin-ajax.php" && "action=noindexscan_login" in body -> {
                    loginCalls++
                    assertTrue("nonce=nonce-1" in body)
                    assertEquals("XMLHttpRequest", req.header("X-Requested-With"))
                    assertEquals("$baseUrl/entrar/", req.header("Referer"))
                    jsonResponse(
                        req,
                        200,
                        """{"success":true,"data":{"redirect":"$baseUrl/"}}""",
                        listOf("wordpress_logged_in_abc=sess123; Path=/; HttpOnly; Secure"),
                    )
                }
                else -> {
                    lastCookieSeen = req.header("Cookie").orEmpty()
                    if ("wordpress_logged_in_abc=sess123" in lastCookieSeen) {
                        htmlResponse(
                            req,
                            200,
                            """<html><body class="wp-theme-madara mch-site logged-in"><li class="mch-catalogue-card" data-work-id="9001">Stop Smoking</li></body></html>""",
                        )
                    } else {
                        htmlResponse(req, 404, """<html><body class="wp-theme-madara mch-site error404">404</body></html>""")
                    }
                }
            }
        }

        val authClient = buildAuthClient(handler)
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        assertFalse(manager.hasSession())

        val firstRes = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, firstRes.code)
        assertTrue("Stop Smoking" in firstRes.body.string())
        firstRes.close()

        // Second request reuses persisted cookie without repeating login
        val secondRes = client.newCall(Request.Builder().url("$baseUrl/manga/stop-smoking/").build()).execute()
        assertEquals(200, secondRes.code)
        secondRes.close()

        assertEquals(1, nonceCalls)
        assertEquals(1, loginCalls)
        assertTrue("wordpress_logged_in_abc=sess123" in lastCookieSeen)
        assertEquals(1, cookies.cacheEvictions)
    }

    @Test
    fun expiredNonceRefreshesOnceAndSucceeds() {
        val creds = MemoryCredentialStore("user@example.com", "secret1234")
        val cookies = MemoryCookieStore()
        var nonceCount = 0
        var loginCount = 0

        val authClient = buildAuthClient { req, body ->
            when {
                "action=noindexscan_get_nonce" in body -> {
                    nonceCount++
                    jsonResponse(req, 200, """{"success":true,"data":{"nonce":"nonce-$nonceCount"}}""")
                }
                "action=noindexscan_login" in body -> {
                    loginCount++
                    if (loginCount == 1) {
                        jsonResponse(req, 403, """{"success":false,"data":{"code":"expired_nonce","message":"Expired"}}""")
                    } else {
                        assertTrue("nonce=nonce-2" in body)
                        jsonResponse(
                            req,
                            200,
                            """{"success":true,"data":{"redirect":"$baseUrl/"}}""",
                            listOf("wordpress_logged_in_abc=fresh999; Path=/"),
                        )
                    }
                }
                else -> htmlResponse(req, 200, "<html></html>")
            }
        }
        val manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        val result = manager.login()
        assertTrue(result is HanamiLoginResult.Success)
        assertEquals(2, nonceCount)
        assertEquals(2, loginCount)
        assertTrue(manager.hasSession())
    }

    @Test
    fun expiredServerSessionRenewsAutomaticallyAndPreservesPublicOnRenewalFailure() {
        val creds = MemoryCredentialStore("user@example.com", "secret1234")
        val cookies = MemoryCookieStore()
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(
                Cookie.Builder().domain("hanamiheaven.org").path("/").name("wordpress_logged_in_abc").value("stale_tok").build(),
            ),
        )
        var loginShouldSucceed = true
        var loginCalls = 0
        var pageCalls = 0

        val handler: (Request, String) -> Response = { req, body ->
            when {
                "action=noindexscan_get_nonce" in body -> jsonResponse(req, 200, """{"success":true,"data":{"nonce":"n-renew"}}""")
                "action=noindexscan_login" in body -> {
                    loginCalls++
                    if (loginShouldSucceed) {
                        jsonResponse(
                            req,
                            200,
                            """{"success":true,"data":{"redirect":"$baseUrl/"}}""",
                            listOf("wordpress_logged_in_abc=renewed_tok; Path=/"),
                        )
                    } else {
                        jsonResponse(req, 503, """{"success":false,"data":{"message":"Temporariamente indisponível"}}""")
                    }
                }
                req.url.encodedPath == "/wp-login.php" -> htmlResponse(req, 503, "<html>Unavailable</html>")
                else -> {
                    pageCalls++
                    val cookieHdr = req.header("Cookie").orEmpty()
                    if ("renewed_tok" in cookieHdr) {
                        htmlResponse(req, 200, """<html><body class="wp-theme-madara mch-site logged-in"><h1 id="mch-work-title">Stop Smoking</h1></body></html>""")
                    } else {
                        htmlResponse(req, 200, """<html><body class="wp-theme-madara mch-site mch-catalogue-page"><li class="mch-catalogue-card" data-work-id="7018">Public Fallback</li></body></html>""")
                    }
                }
            }
        }

        val authClient = buildAuthClient(handler)
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        val renewedRes = client.newCall(Request.Builder().url("$baseUrl/manga/stop-smoking/").build()).execute()
        assertEquals(200, renewedRes.code)
        assertTrue("Stop Smoking" in renewedRes.body.string())
        renewedRes.close()
        assertEquals(1, loginCalls)
        assertEquals(2, pageCalls)

        // Now force expiration while renewal fails: public response is preserved cleanly
        loginShouldSucceed = false
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(
                Cookie.Builder().domain("hanamiheaven.org").path("/").name("wordpress_logged_in_abc").value("stale_2").build(),
            ),
        )
        val fallbackRes = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, fallbackRes.code)
        assertTrue("Public Fallback" in fallbackRes.body.string())
        fallbackRes.close()
    }

    @Test
    fun changingOrRemovingCredentialsInvalidatesPreviousSessionCleanly() {
        val creds = MemoryCredentialStore("first@example.com", "pass1")
        val cookies = MemoryCookieStore()
        var loggedInUsers = mutableListOf<String>()

        val handler: (Request, String) -> Response = { req, body ->
            when {
                "action=noindexscan_get_nonce" in body -> jsonResponse(req, 200, """{"success":true,"data":{"nonce":"n"}}""")
                "action=noindexscan_login" in body -> {
                    val tok = if ("first" in body) {
                        loggedInUsers.add("first")
                        "tok_first"
                    } else {
                        loggedInUsers.add("second")
                        "tok_second"
                    }
                    jsonResponse(
                        req,
                        200,
                        """{"success":true,"data":{"redirect":"$baseUrl/"}}""",
                        listOf("wordpress_logged_in_abc=$tok; Path=/"),
                    )
                }
                else -> htmlResponse(
                    req,
                    200,
                    """<html><body class="mch-site logged-in">cookie=${req.header("Cookie").orEmpty()}</body></html>""",
                )
            }
        }

        val authClient = buildAuthClient(handler)
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute().use { res ->
            assertTrue("tok_first" in res.body.string())
        }

        // Changing credentials invalidates first session and logs in with second account automatically
        creds.storedEmail = "second@example.com"
        creds.storedPassword = "pass2"
        manager.onCredentialsChanged()

        client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute().use { res ->
            assertTrue("tok_second" in res.body.string())
        }
        assertEquals(listOf("first", "second"), loggedInUsers)
        assertTrue(manager.hasSession())

        // Removing credentials clears managed session and reverts to unauthenticated public browsing
        creds.storedEmail = ""
        creds.storedPassword = ""
        manager.onCredentialsChanged()

        client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute().use { res ->
            val body = res.body.string()
            assertFalse("tok_second" in body)
            assertFalse(manager.hasSession())
        }
    }

    @Test
    fun publicCatalogWorksWithoutCredentialsAndNetworkErrorsDoNotBreakCatalog() {
        val creds = MemoryCredentialStore()
        val cookies = MemoryCookieStore()
        var authHits = 0

        val authClient = buildAuthClient { _, _ ->
            authHits++
            throw IOException("timeout")
        }
        lateinit var manager: HanamiSessionManager
        val client = buildClient(cookies, { manager }) { req, _ ->
            htmlResponse(
                req,
                200,
                """<html><body class="wp-theme-madara mch-site mch-catalogue-page"><li class="mch-catalogue-card" data-work-id="7351">Public Work</li></body></html>""",
            )
        }
        manager = HanamiSessionManager(baseUrl, creds, cookies, { authClient }, { Headers.headersOf() })

        val publicRes = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, publicRes.code)
        assertTrue("Public Work" in publicRes.body.string())
        publicRes.close()
        assertEquals(0, authHits)

        // Adding credentials while auth endpoint has a network outage must STILL let catalog succeed
        creds.storedEmail = "user@example.com"
        creds.storedPassword = "pass"
        manager.onCredentialsChanged()

        val degradedRes = client.newCall(Request.Builder().url("$baseUrl/todas-as-obras/").build()).execute()
        assertEquals(200, degradedRes.code)
        assertTrue("Public Work" in degradedRes.body.string())
        degradedRes.close()
    }

    @Test
    fun isGuestHtmlAvoidsFalsePositivesAndStaticAssetsBypassAuth() {
        assertFalse(HanamiSessionManager.isGuestHtml("""<html><body class="cloudflare-error">Blocked</body></html>"""))
        assertFalse(HanamiSessionManager.isGuestHtml("""<html><body>Plain page</body></html>"""))
        assertFalse(HanamiSessionManager.isGuestHtml("""<html><body class="wp-theme-madara mch-site logged-in"></body></html>"""))
        assertTrue(HanamiSessionManager.isGuestHtml("""<html><body class="wp-theme-madara mch-site mch-catalogue-page"></body></html>"""))

        val imgReq = Request.Builder().url("$baseUrl/wp-content/uploads/2026/01/page1.webp").build()
        assertTrue(HanamiSessionManager.isStaticAssetRequest(imgReq))
        val docReq = Request.Builder().url("$baseUrl/todas-as-obras/").build()
        assertFalse(HanamiSessionManager.isStaticAssetRequest(docReq))
    }

    @Test
    fun noindexscanHtmlSelectorsAndFiltersCompatibilityRegressionCheck() {
        val guestHtml = """
            <ul class="mch-catalogue-grid">
              <li class="mch-catalogue-card" data-work-id="7351">
                <h3 class="mch-catalogue-card__title"><a href="https://hanamiheaven.org/manga/small-beast/">Small Beast</a></h3>
              </li>
              <li class="mch-catalogue-card" data-work-id="8888">
                <span class="mch-catalogue-card__meta"><span>Novel</span></span>
                <h3 class="mch-catalogue-card__title"><a href="https://hanamiheaven.org/manga/some-novel/">Some Novel</a></h3>
              </li>
            </ul>
        """.trimIndent()
        val authHtml = """
            <ul class="mch-catalogue-grid">
              <li class="mch-catalogue-card" data-work-id="7351">
                <h3 class="mch-catalogue-card__title"><a href="https://hanamiheaven.org/manga/small-beast/">Small Beast</a></h3>
              </li>
              <li class="mch-catalogue-card" data-work-id="9001">
                <h3 class="mch-catalogue-card__title"><a href="https://hanamiheaven.org/manga/stop-smoking/">Stop Smoking</a></h3>
              </li>
            </ul>
        """.trimIndent()

        val selector = "li.mch-catalogue-card:not(:has(.mch-catalogue-card__meta span:contains(Novel)))"
        val guestCards = Jsoup.parse(guestHtml, baseUrl).select(selector)
        val authCards = Jsoup.parse(authHtml, baseUrl).select(selector)

        assertEquals(1, guestCards.size)
        assertEquals("7351", guestCards[0].attr("data-work-id"))
        assertEquals(2, authCards.size)
        assertEquals("9001", authCards[1].attr("data-work-id"))

        // Verify URL filter compatibility in noindexscan module
        val sortFilter = SelectFilter("Ordenar por", "ordem", SORT_OPTIONS).apply { state = 3 }
        val yearFilter = YearFilter("Ano").apply { state = "2024" }
        val genreFilter = GenreFilter("Gêneros", listOf(GenreRoute("Ação", "acao", ""))).apply {
            state[0].state = Filter.TriState.STATE_INCLUDE
        }
        val builder = "$baseUrl/todas-as-obras/".toHttpUrl().newBuilder()
        listOf(sortFilter, yearFilter, genreFilter).forEach { it.addTo(builder) }
        val built = builder.build()
        assertEquals("rating", built.queryParameter("ordem"))
        assertEquals("2024", built.queryParameter("ano"))
        assertEquals("acao", built.queryParameter("genero[]"))
    }
}
