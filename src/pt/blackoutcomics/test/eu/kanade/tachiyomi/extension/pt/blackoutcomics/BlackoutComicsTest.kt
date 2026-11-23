package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import kotlinx.serialization.json.Json
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
import okio.ByteString.Companion.encodeUtf8
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class BlackoutComicsTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpInjekt() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }

    private val baseUrl = "https://blackoutcomics.com"

    private class MemoryCredentialStore(
        var storedEmail: String = "",
        var storedPassword: String = "",
    ) : BlackoutCredentialStore {
        override fun getEmail() = storedEmail
        override fun getPassword() = storedPassword
    }

    private class InMemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { incoming ->
                list.removeAll { it.name == incoming.name }
                if (incoming.expiresAt > now &&
                    incoming.value.isNotEmpty() &&
                    !incoming.value.equals("deleted", ignoreCase = true)
                ) {
                    list.add(incoming)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty().toList()

        fun clearHost(host: String) {
            store.remove(host)
        }
    }

    private class MemoryCookieStore(
        val jar: InMemoryCookieJar = InMemoryCookieJar(),
        var webViewCookies: String = "",
    ) : BlackoutCookieStore {
        var cacheEvictions = 0

        override fun loadSessionCookies(url: HttpUrl): List<Cookie> = jar.loadForRequest(url)

        override fun syncFromWebView(url: HttpUrl): Boolean {
            if (webViewCookies.isBlank()) return false
            val parsed = parseCookieHeader(url, webViewCookies)
            if (parsed.isEmpty()) return false
            jar.saveFromResponse(url, parsed)
            return parsed.any {
                it.name == BlackoutSessionManager.SESSION_COOKIE &&
                    !it.value.equals("deleted", ignoreCase = true)
            }
        }

        override fun saveCookies(url: HttpUrl, cookies: List<Cookie>) {
            jar.saveFromResponse(url, cookies)
        }

        override fun clearSessionCookies(url: HttpUrl) {
            webViewCookies = ""
            jar.clearHost(url.host)
        }

        override fun evictHttpCache() {
            cacheEvictions++
        }
    }

    private fun buildClient(
        cookieStore: MemoryCookieStore,
        sessionRef: () -> BlackoutSessionManager,
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
            val res = handler(effectiveReq, bodyStr)
            val responseCookies = Cookie.parseAll(effectiveReq.url, res.headers)
            if (responseCookies.isNotEmpty()) {
                cookieStore.jar.saveFromResponse(effectiveReq.url, responseCookies)
            }
            res
        }
        .build()

    private fun buildEphemeralClient(handler: (Request, String) -> Response): (CookieJar) -> OkHttpClient = { jar ->
        OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor { chain ->
                val req = chain.request()
                val cookieHeader = jar.loadForRequest(req.url)
                    .joinToString("; ") { "${it.name}=${it.value}" }
                val effectiveReq = if (cookieHeader.isNotEmpty() && req.header("Cookie") == null) {
                    req.newBuilder().header("Cookie", cookieHeader).build()
                } else {
                    req
                }
                val bodyStr = Buffer().also { effectiveReq.body?.writeTo(it) }.readUtf8()
                val res = handler(effectiveReq, bodyStr)
                val responseCookies = Cookie.parseAll(effectiveReq.url, res.headers)
                if (responseCookies.isNotEmpty()) {
                    jar.saveFromResponse(effectiveReq.url, responseCookies)
                }
                res
            }
            .build()
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

    private fun guestHomeHtml(csrf: String = "csrf_123"): String = """
        <html>
          <head><meta name="csrf-token" content="$csrf"></head>
          <body id="body-blackout" class="lang-por home-page adult-content-locked">
            <form class="form-modal-login" action="$baseUrl/entrar" method="POST"></form>
            <div class="content-lock-alert">Por segurança, as imagens ficam borradas até o login.</div>
          </body>
        </html>
    """.trimIndent()

    private fun authHomeHtml(csrf: String = "csrf_auth"): String = """
        <html>
          <head><meta name="csrf-token" content="$csrf"></head>
          <body id="body-blackout" class="lang-por home-page">
            <a href="$baseUrl/perfil">Minha Conta</a>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun validLoginValidatesServerSessionAndPersistsCookies() {
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        var postCalls = 0

        val handler: (Request, String) -> Response = { req, body ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when {
                req.url.encodedPath == "/" && "blackout-comics-session=auth_sess_1" in cookieHdr ->
                    htmlResponse(req, 200, authHomeHtml())
                req.url.encodedPath == "/" ->
                    htmlResponse(
                        req,
                        200,
                        guestHomeHtml("csrf_abc"),
                        listOf(
                            "XSRF-TOKEN=xsrf_1; Path=/",
                            "blackout-comics-session=guest_sess; Path=/",
                        ),
                    )
                req.url.encodedPath == "/entrar" -> {
                    postCalls++
                    assertTrue("USE_EMAIL=reader%40gmail.com" in body)
                    assertTrue("password=strongPass123" in body)
                    assertTrue("_token=csrf_abc" in body)
                    assertEquals("csrf_abc", req.header("X-CSRF-TOKEN"))
                    assertEquals("XMLHttpRequest", req.header("X-Requested-With"))
                    jsonResponse(
                        req,
                        200,
                        """{"success":"Login realizado!"}""",
                        listOf("blackout-comics-session=auth_sess_1; Path=/; HttpOnly; Secure"),
                    )
                }
                else -> htmlResponse(req, 200, authHomeHtml())
            }
        }

        val authClient = OkHttpClient()
        val manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        val result = manager.login()
        assertTrue(result is BlackoutAuthResult.Success)
        assertTrue(manager.isVerifiedAuthenticated())
        assertTrue(manager.hasSessionCookie())
        assertEquals(1, postCalls)
        assertEquals(1, cookies.cacheEvictions)

        // Do not accept HTTP 200 without real server recognition (adult-content-locked still present).
        val fakeHandler: (Request, String) -> Response = { req, _ ->
            when (req.url.encodedPath) {
                "/" -> htmlResponse(req, 200, guestHomeHtml("csrf_x"), listOf("blackout-comics-session=guest_only; Path=/"))
                "/entrar" -> jsonResponse(req, 200, """{"success":"ok"}""", listOf("blackout-comics-session=unverified; Path=/"))
                else -> htmlResponse(req, 200, guestHomeHtml())
            }
        }
        val fakeManager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = MemoryCookieStore(),
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(fakeHandler),
        )
        val fakeResult = fakeManager.login()
        assertTrue(fakeResult is BlackoutAuthResult.Failure)
        assertFalse(fakeManager.isVerifiedAuthenticated())
    }

    @Test
    fun invalidCredentialsPreventLoopsAndPreservePublicCatalog() {
        val creds = MemoryCredentialStore("wrong@gmail.com", "badpass")
        val cookies = MemoryCookieStore()
        var loginPosts = 0

        val handler: (Request, String) -> Response = { req, _ ->
            when (req.url.encodedPath) {
                "/" -> htmlResponse(req, 200, guestHomeHtml("csrf_1"), listOf("blackout-comics-session=g1; Path=/"))
                "/entrar" -> {
                    loginPosts++
                    jsonResponse(req, 422, """{"error":"Credenciais inválidas."}""")
                }
                "/ranking" -> htmlResponse(
                    req,
                    200,
                    """<html><body id="body-blackout" class="adult-content-locked"><section class="ranking-standard-grid"><a href="$baseUrl/comics/147" class="webtoon-card"><div class="card-title"><span>Alguém Para Ela!</span></div><img src="/projects/x/info/blur.webp"></a></section></body></html>""",
                )
                else -> htmlResponse(req, 200, guestHomeHtml())
            }
        }

        val authClient = OkHttpClient()
        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        val catalogRes = client.newCall(Request.Builder().url("$baseUrl/ranking").build()).execute()
        assertEquals(200, catalogRes.code)
        assertTrue("Alguém Para Ela!" in catalogRes.body.string())
        catalogRes.close()

        // Second request must NOT hammer /entrar with the same invalid credentials.
        client.newCall(Request.Builder().url("$baseUrl/ranking").build()).execute().close()
        assertEquals(1, loginPosts)
        assertFalse(manager.isVerifiedAuthenticated())
    }

    @Test
    fun persistedSessionConfirmedAcrossRestartAndRenewedOnExpiration() {
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(Cookie.Builder().domain("blackoutcomics.com").path("/").name("blackout-comics-session").value("stale_tok").build()),
        )

        var loginCount = 0
        var chapterHits = 0
        val handler: (Request, String) -> Response = { req, _ ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when (req.url.encodedPath) {
                "/" -> {
                    if ("fresh_tok" in cookieHdr) {
                        htmlResponse(req, 200, authHomeHtml())
                    } else {
                        htmlResponse(req, 200, guestHomeHtml("csrf_renew"), listOf("blackout-comics-session=guest_2; Path=/"))
                    }
                }
                "/entrar" -> {
                    loginCount++
                    jsonResponse(
                        req,
                        200,
                        """{"success":"Login realizado!"}""",
                        listOf("blackout-comics-session=fresh_tok; Path=/"),
                    )
                }
                "/comics/147/ler/capitulo-1" -> {
                    chapterHits++
                    if ("fresh_tok" in cookieHdr) {
                        htmlResponse(req, 200, """<html><body id="body-blackout"><div id="reader-payload" data-payload="W10="></div></body></html>""")
                    } else {
                        htmlResponse(req, 403, "403 – Acesso Proibido")
                    }
                }
                else -> htmlResponse(req, 200, authHomeHtml())
            }
        }

        val authClient = OkHttpClient()
        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        // First call detects stale cookie via server verification, renews, and serves chapter cleanly.
        val chapterRes = client.newCall(Request.Builder().url("$baseUrl/comics/147/ler/capitulo-1").build()).execute()
        assertEquals(200, chapterRes.code)
        assertTrue("reader-payload" in chapterRes.body.string())
        chapterRes.close()
        assertEquals(1, loginCount)
        assertEquals(1, chapterHits)

        // Subsequent request reuses the verified session without logging in again.
        client.newCall(Request.Builder().url("$baseUrl/comics/147/ler/capitulo-1").build()).execute().close()
        assertEquals(1, loginCount)
    }

    @Test
    fun credentialUpdatesInvalidateOldSessionAndReauthenticate() {
        val creds = MemoryCredentialStore("first@gmail.com", "pass1")
        val cookies = MemoryCookieStore()
        val accountsUsed = mutableListOf<String>()

        val handler: (Request, String) -> Response = { req, body ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when (req.url.encodedPath) {
                "/" -> {
                    if ("tok_first" in cookieHdr || "tok_second" in cookieHdr) {
                        htmlResponse(req, 200, authHomeHtml())
                    } else {
                        htmlResponse(req, 200, guestHomeHtml("csrf_1"), listOf("blackout-comics-session=tmp; Path=/"))
                    }
                }
                "/entrar" -> {
                    val tok = if ("first" in body) {
                        accountsUsed.add("first")
                        "tok_first"
                    } else {
                        accountsUsed.add("second")
                        "tok_second"
                    }
                    jsonResponse(req, 200, """{"success":"ok"}""", listOf("blackout-comics-session=$tok; Path=/"))
                }
                else -> htmlResponse(req, 200, """<html><body id="body-blackout">cookie=$cookieHdr</body></html>""")
            }
        }

        val authClient = OkHttpClient()
        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        client.newCall(Request.Builder().url("$baseUrl/comics").build()).execute().use { res ->
            assertTrue("tok_first" in res.body.string())
        }

        creds.storedEmail = "second@gmail.com"
        creds.storedPassword = "pass2"
        manager.onCredentialsChanged()

        client.newCall(Request.Builder().url("$baseUrl/comics").build()).execute().use { res ->
            assertTrue("tok_second" in res.body.string())
        }
        assertEquals(listOf("first", "second"), accountsUsed)

        // Removing credentials clears managed session and reverts to unauthenticated public mode.
        creds.storedEmail = ""
        creds.storedPassword = ""
        manager.onCredentialsChanged()

        client.newCall(Request.Builder().url("$baseUrl/comics").build()).execute().use { res ->
            assertFalse("tok_second" in res.body.string())
            assertFalse(manager.hasSessionCookie())
        }
    }

    @Test
    fun publicAndAuthenticatedCatalogAndBlurredVsSharpCovers() {
        val guestDoc = Jsoup.parse(
            """
            <html><body id="body-blackout" class="adult-content-locked">
              <section class="ranking-featured-grid">
                <a href="$baseUrl/comics/247" class="ranking-featured-card">
                  <img src="$baseUrl/projects/abc/info/blurred.webp" alt="Obra 1">
                  <div class="ranking-featured-info"><h2>Obra 1</h2></div>
                </a>
              </section>
              <section class="ranking-standard-grid">
                <a href="$baseUrl/comics/147" class="webtoon-card ranking-standard-card">
                  <div class="card-thumb"><img data-src="$baseUrl/projects/def/info/blurred2.webp" src="/placeholder.webp" alt="Alguém Para Ela!"></div>
                  <div class="card-info"><div class="card-title"><span>Alguém Para Ela!</span></div></div>
                </a>
              </section>
            </body></html>
            """.trimIndent(),
            baseUrl,
        )
        val popular = BlackoutComics.parsePopularCardData(guestDoc)
        assertEquals(2, popular.size)
        assertEquals("/comics/247", popular[0].url)
        assertEquals("Obra 1", popular[0].title)
        assertEquals("$baseUrl/projects/abc/info/blurred.webp", popular[0].thumbnailUrl)
        assertEquals("/comics/147", popular[1].url)
        assertEquals("$baseUrl/projects/def/info/blurred2.webp", popular[1].thumbnailUrl)

        // Srcset picks highest-resolution original cover URL when provided.
        val sharpDoc = Jsoup.parse(
            """
            <div class="webtoon-grid">
              <a href="$baseUrl/comics/147" class="webtoon-card">
                <div class="card-thumb">
                  <img src="/projects/def/info/thumb_400.webp" srcset="/projects/def/info/thumb_400.webp 400w, /projects/def/info/full_1200.webp 1200w" alt="Alguém Para Ela!">
                </div>
                <div class="card-title"><span>Alguém Para Ela!</span></div>
              </a>
            </div>
            """.trimIndent(),
            baseUrl,
        )
        val grid = BlackoutComics.parseGridCardData(sharpDoc)
        assertEquals(1, grid.size)
        assertEquals("$baseUrl/projects/def/info/full_1200.webp", grid[0].thumbnailUrl)

        // Latest pagination check
        val latestDoc = Jsoup.parse("""<html><body><a class="pagerx__link" rel="next" href="$baseUrl/atualizados-recente?page=2">›</a></body></html>""", baseUrl)
        assertTrue(BlackoutComics.hasNextPage(latestDoc))
        val lastPageDoc = Jsoup.parse("""<html><body><div class="webtoon-grid"></div></body></html>""", baseUrl)
        assertFalse(BlackoutComics.hasNextPage(lastPageDoc))
    }

    @Test
    fun parseMangaDetailsAndChapterListPreserveUrlsAndMetadata() {
        val detailHtml = """
            <html><body>
              <img class="project-cover server-cover-image" src="https://blackoutcomics.com/projects/68f50eb151be2/info/sharp.webp" alt="Alguém Para Ela!">
              <h1 class="project-title">Alguém Para Ela!</h1>
              <p class="project-subtitle">Someone stop her!</p>
              <div class="project-quick-info">
                <span class="quick-info-item"><i class="fa-solid fa-pen-nib"></i><strong>RA</strong></span>
                <span class="quick-info-item"><i class="fa-solid fa-palette"></i><strong>Momose</strong></span>
              </div>
              <div class="project-genres">
                <span class="genre-tag">Drama</span>
                <span class="genre-tag">Romance</span>
              </div>
              <div class="project-description"><p>Sinopse da obra.</p></div>
              <div class="detail-item">
                <span class="detail-label">Status</span>
                <span class="detail-value"><span class="status-pill">Em Lançamento</span></span>
              </div>
              <ol class="list-ep" id="tab-capitulos-list">
                <li class="normal_ep">
                  <div class="chapter-link-wrap" onclick="window.location.href='https://blackoutcomics.com/comics/147/ler/capitulo-120'">
                    <div class="cell-num"><span class="num">120</span><div class="text-muted">18.11.26</div></div>
                    <div class="cell-title"><strong>Vizinha Barulhenta</strong><time datetime="2026-11-18">18 Nov, 2026</time></div>
                  </div>
                </li>
                <li class="normal_ep">
                  <div class="chapter-link-wrap">
                    <a href="/comics/147/ler/capitulo-119.5">
                      <div class="cell-num"><span class="num">119.5</span><div class="text-muted">10.11.26</div></div>
                      <div class="cell-title"><strong></strong></div>
                    </a>
                  </div>
                </li>
              </ol>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(detailHtml, "$baseUrl/comics/147")
        val manga = BlackoutComics.parseMangaDetailsData(doc)
        assertEquals("Alguém Para Ela!", manga.title)
        assertEquals("RA", manga.author)
        assertEquals("Momose", manga.artist)
        assertEquals("Drama, Romance", manga.genre)
        assertTrue(manga.description.contains("Sinopse da obra."))
        assertTrue(manga.description.contains("Someone stop her!"))
        assertEquals(SManga.ONGOING, manga.status)
        assertEquals("https://blackoutcomics.com/projects/68f50eb151be2/info/sharp.webp", manga.thumbnailUrl)

        val chapters = BlackoutComics.parseChapterDataList(doc, "/comics/147")
        assertEquals(2, chapters.size)
        assertEquals("/comics/147/ler/capitulo-120", chapters[0].url)
        assertEquals("Capítulo 120 - Vizinha Barulhenta", chapters[0].name)
        assertEquals(120f, chapters[0].chapterNumber)
        assertTrue(chapters[0].dateUpload > 0L)

        assertEquals("/comics/147/ler/capitulo-119.5", chapters[1].url)
        assertEquals("Capítulo 119.5", chapters[1].name)
        assertEquals(119.5f, chapters[1].chapterNumber)
        assertTrue(chapters[1].dateUpload > 0L)
    }

    @Test
    fun extractPageUrlsDecodesReaderPayloadAndFallbacksCleanly() {
        val rawUrls = """["/projects/68f50eb151be2/chapters/120/01.webp","https://blackoutcomics.com/projects/68f50eb151be2/chapters/120/02.webp"]"""
        val b64 = rawUrls.encodeUtf8().base64()
        val html = """<html><body><div id="reader-payload" data-payload="$b64"></div></body></html>"""
        val doc = Jsoup.parse(html, "$baseUrl/comics/147/ler/capitulo-120")

        val pages = BlackoutComics.extractPageUrls(doc, html, baseUrl)
        assertEquals(
            listOf(
                "$baseUrl/projects/68f50eb151be2/chapters/120/01.webp",
                "$baseUrl/projects/68f50eb151be2/chapters/120/02.webp",
            ),
            pages,
        )

        // Fallback to DOM slices if #reader-payload is absent
        val domHtml = """<html><body><div id="pages"><img class="slice" data-src="/projects/1/1.webp"><img class="slice" src="/projects/1/2.webp"></div></body></html>"""
        val domDoc = Jsoup.parse(domHtml, "$baseUrl/comics/147/ler/capitulo-1")
        assertEquals(
            listOf("$baseUrl/projects/1/1.webp", "$baseUrl/projects/1/2.webp"),
            BlackoutComics.extractPageUrls(domDoc, domHtml, baseUrl),
        )
    }

    @Test
    fun imageRequestUsesChapterRefererAndHttp403TriggersSessionRenewalAndReopen() {
        val chapterUrl = "$baseUrl/comics/147/ler/capitulo-1"
        val imageUrl = "$baseUrl/projects/68f50eb151be2/chapters/1/01.webp"
        val page = Page(0, url = chapterUrl, imageUrl = imageUrl)
        val builtReq = Request.Builder()
            .url(page.imageUrl!!)
            .header("Accept-Language", "pt-BR,pt;q=0.9")
            .fetch(Fetch.IMAGE.from(chapterUrl))
            .build()
        assertEquals(Fetch.Kind.IMAGE, builtReq.tag(Fetch.Kind::class.java))
        assertNull(builtReq.header("Sec-Fetch-User"))
        assertNull(builtReq.header("Upgrade-Insecure-Requests"))

        // Verify intermittent HTTP 403 during reading renews expired session and succeeds on retry
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(Cookie.Builder().domain("blackoutcomics.com").path("/").name("blackout-comics-session").value("old_sess").build()),
        )

        var loginCalls = 0
        var imageHits = 0
        val handler: (Request, String) -> Response = { req, _ ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when (req.url.encodedPath) {
                "/" -> if ("new_sess" in cookieHdr) {
                    htmlResponse(req, 200, authHomeHtml())
                } else {
                    htmlResponse(req, 200, guestHomeHtml("csrf_img"), listOf("blackout-comics-session=tmp; Path=/"))
                }
                "/entrar" -> {
                    loginCalls++
                    jsonResponse(req, 200, """{"success":"ok"}""", listOf("blackout-comics-session=new_sess; Path=/"))
                }
                "/projects/68f50eb151be2/chapters/1/01.webp" -> {
                    imageHits++
                    if ("new_sess" in cookieHdr) {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_2)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "image/webp")
                            .body("webpdata".toResponseBody("image/webp".toMediaType()))
                            .build()
                    } else {
                        htmlResponse(req, 403, "403 – Acesso Proibido")
                    }
                }
                else -> htmlResponse(req, 200, authHomeHtml())
            }
        }

        val authClient = OkHttpClient()
        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { authClient },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        // Pre-verify old_sess as initially trusted until server returns 403 mid-reading
        val firstRes = client.newCall(
            Request.Builder().url(imageUrl).header("Referer", chapterUrl).build(),
        ).execute()
        assertEquals(200, firstRes.code)
        assertEquals("webpdata", firstRes.body.string())
        firstRes.close()
        assertEquals(1, loginCalls)
        assertEquals(2, imageHits)

        // Reopening another chapter immediately afterwards reuses renewed session cleanly
        val reopenRes = client.newCall(
            Request.Builder().url(chapterUrl).header("Referer", "$baseUrl/comics/147").build(),
        ).execute()
        assertEquals(200, reopenRes.code)
        reopenRes.close()
        assertEquals(1, loginCalls)
    }

    @Test
    fun filtersContainAllUpdatedGenresAndBuildProperParameters() {
        val genreFilter = GenreFilter()
        assertTrue(genreFilter.values.contains("Tsundere"))
        assertTrue(genreFilter.values.contains("Controle Mental"))
        assertTrue(genreFilter.values.contains("Drama"))

        val filters = FilterList(
            StatusFilter().apply { state = 1 },
            GenreFilter().apply { state = 1 },
            OrderFilter().apply { state = 1 },
        )
        assertEquals("completed", (filters[0] as StatusFilter).toUriPart())
        assertTrue((filters[1] as GenreFilter).toUriPart().isNotEmpty())
        assertEquals("A", (filters[2] as OrderFilter).toUriPart())
    }

    @Test
    fun loginPreservesNonSessionCookiesAndStripsOriginFromDocumentRequests() {
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(
                Cookie.Builder().domain("blackoutcomics.com").path("/").name("cf_clearance").value("cf_ok_1").build(),
                Cookie.Builder().domain("blackoutcomics.com").path("/").name("age_gate_consent").value("accepted").build(),
                Cookie.Builder().domain("blackoutcomics.com").path("/").name("blackout-comics-session").value("old_guest").build(),
            ),
        )

        var sawCfOnGetHome = false
        var sawOldSessionOnGetHome = false
        var sawOriginOnGetHome = false
        var sawOriginOnPostLogin = false

        val handler: (Request, String) -> Response = { req, _ ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when {
                req.url.encodedPath == "/" && "auth_ok" in cookieHdr -> {
                    assertNull(req.header("Origin"))
                    htmlResponse(req, 200, authHomeHtml())
                }
                req.url.encodedPath == "/" -> {
                    sawCfOnGetHome = "cf_clearance=cf_ok_1" in cookieHdr && "age_gate_consent=accepted" in cookieHdr
                    sawOldSessionOnGetHome = "old_guest" in cookieHdr
                    sawOriginOnGetHome = req.header("Origin") != null
                    htmlResponse(
                        req,
                        200,
                        guestHomeHtml("csrf_cf"),
                        listOf("blackout-comics-session=tmp_sess; Path=/"),
                    )
                }
                req.url.encodedPath == "/entrar" -> {
                    sawOriginOnPostLogin = req.header("Origin") == baseUrl
                    jsonResponse(
                        req,
                        200,
                        """{"success":"ok"}""",
                        listOf("blackout-comics-session=auth_ok; Path=/"),
                    )
                }
                else -> htmlResponse(req, 200, authHomeHtml())
            }
        }

        val manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { OkHttpClient() },
            baseHeadersProvider = {
                Headers.headersOf("Referer", "$baseUrl/", "Origin", baseUrl)
            },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        val result = manager.login()
        assertTrue(result is BlackoutAuthResult.Success)
        assertTrue(sawCfOnGetHome)
        assertFalse(sawOldSessionOnGetHome)
        assertFalse(sawOriginOnGetHome)
        assertTrue(sawOriginOnPostLogin)
        assertNull(manager.lastFailureMessage())
    }

    @Test
    fun transientCloudflare403RecordsSpecificErrorAndReaderRecoversWhenCleared() {
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        var blockHomeWithCloudflare = true
        var loginPosts = 0

        val handler: (Request, String) -> Response = { req, _ ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when (req.url.encodedPath) {
                "/" -> if (blockHomeWithCloudflare) {
                    htmlResponse(
                        req,
                        403,
                        """<html><head><title>Attention Required! | Cloudflare</title></head><body><div id="cf-error-details">Sorry, you have been blocked</div></body></html>""",
                    )
                } else if ("auth_recovered" in cookieHdr) {
                    htmlResponse(req, 200, authHomeHtml())
                } else {
                    htmlResponse(req, 200, guestHomeHtml("csrf_rec"), listOf("blackout-comics-session=tmp_r; Path=/"))
                }
                "/entrar" -> {
                    loginPosts++
                    jsonResponse(
                        req,
                        200,
                        """{"success":"ok"}""",
                        listOf("blackout-comics-session=auth_recovered; Path=/"),
                    )
                }
                "/comics/155/ler/capitulo-1" -> if ("auth_recovered" in cookieHdr) {
                    htmlResponse(req, 200, """<html><body id="body-blackout"><div id="reader-payload" data-payload="W10="></div></body></html>""")
                } else {
                    htmlResponse(req, 302, guestHomeHtml())
                }
                else -> htmlResponse(req, 200, guestHomeHtml())
            }
        }

        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { OkHttpClient() },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        // Catalog request hits Cloudflare 403 on login: catalog still loads and records real Cloudflare error.
        client.newCall(Request.Builder().url("$baseUrl/ranking").build()).execute().close()
        assertFalse(manager.isVerifiedAuthenticated())
        assertTrue(manager.lastFailureMessage().orEmpty().contains("Cloudflare", ignoreCase = true))

        // Immediately afterwards, once Cloudflare clears, reader request retries login instead of waiting 60s.
        blockHomeWithCloudflare = false
        val readerRes = client.newCall(Request.Builder().url("$baseUrl/comics/155/ler/capitulo-1").build()).execute()
        assertEquals(200, readerRes.code)
        assertTrue("reader-payload" in readerRes.body.string())
        readerRes.close()
        assertEquals(1, loginPosts)
        assertTrue(manager.isVerifiedAuthenticated())
        assertNull(manager.lastFailureMessage())
    }

    @Test
    fun guestHtmlMarkersAvoidScriptFalsePositives() {
        val loggedInWithModalScriptMention = """
            <html>
              <head><meta name="csrf-token" content="tok123"></head>
              <body id="body-blackout" class="lang-por home-page">
                <script>console.log("modal-login handler initialized")</script>
              </body>
            </html>
        """.trimIndent()
        assertFalse(BlackoutSessionManager.isGuestHtml(loggedInWithModalScriptMention))
        assertTrue(BlackoutSessionManager.isAuthenticatedHtml(loggedInWithModalScriptMention))

        val guestNavbarHtml = """
            <html>
              <body id="body-blackout" class="lang-por">
                <a class="login" id="toggle-login" onclick="popup('modal-login')">Fazer Login</a>
              </body>
            </html>
        """.trimIndent()
        assertTrue(BlackoutSessionManager.isGuestHtml(guestNavbarHtml))
        assertFalse(BlackoutSessionManager.isAuthenticatedHtml(guestNavbarHtml))
    }

    @Test
    fun loginRedirectSetCookieDoesNotSkipRenewalAndHtmlImageNeverReachesDecoder() {
        val creds = MemoryCredentialStore("reader@gmail.com", "strongPass123")
        val cookies = MemoryCookieStore()
        var loginPosts = 0

        val handler: (Request, String) -> Response = { req, _ ->
            val cookieHdr = req.header("Cookie").orEmpty()
            when (req.url.encodedPath) {
                "/" -> if ("auth_real" in cookieHdr) {
                    htmlResponse(req, 200, authHomeHtml())
                } else {
                    htmlResponse(req, 200, guestHomeHtml("csrf_img2"), listOf("blackout-comics-session=guest_tmp; Path=/"))
                }
                "/entrar" -> {
                    loginPosts++
                    jsonResponse(req, 200, """{"success":"ok"}""", listOf("blackout-comics-session=auth_real; Path=/"))
                }
                "/image/delivery/100" -> if ("auth_real" in cookieHdr) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_2)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/webp")
                        .body("RIFFwebp".toResponseBody("image/webp".toMediaType()))
                        .build()
                } else {
                    // Simulate Laravel 302 -> /login -> / returning guest cookie + 200 text/html.
                    val bouncedReq = req.newBuilder().url("$baseUrl/").build()
                    htmlResponse(
                        bouncedReq,
                        200,
                        guestHomeHtml("csrf_bounce"),
                        listOf("blackout-comics-session=guest_rotated; Path=/"),
                    )
                }
                else -> htmlResponse(req, 200, authHomeHtml())
            }
        }

        // Seed an expired/guest session cookie before first image request.
        cookies.jar.saveFromResponse(
            baseUrl.toHttpUrl(),
            listOf(Cookie.Builder().domain("blackoutcomics.com").path("/").name("blackout-comics-session").value("stale_v1").build()),
        )

        lateinit var manager: BlackoutSessionManager
        val client = buildClient(cookies, { manager }, handler)
        manager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = cookies,
            httpClientProvider = { OkHttpClient() },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(handler),
        )

        val imgReq = Request.Builder()
            .url("$baseUrl/image/delivery/100?file=01.webp")
            .header("Accept", "image/*")
            .fetch(Fetch.XHR)
            .build()
        val imgRes = client.newCall(imgReq).execute()
        assertEquals(200, imgRes.code)
        assertEquals("image/webp", imgRes.header("Content-Type"))
        assertEquals("RIFFwebp", imgRes.body.string())
        imgRes.close()
        assertEquals(1, loginPosts)

        // If server ALWAYS returns HTML for an image even after renewal, interceptor must throw IOException rather than hand HTML to decoder.
        val htmlOnlyHandler: (Request, String) -> Response = { req, _ ->
            when (req.url.encodedPath) {
                "/" -> htmlResponse(req, 200, authHomeHtml(), listOf("blackout-comics-session=auth_x; Path=/"))
                "/entrar" -> jsonResponse(req, 200, """{"success":"ok"}""", listOf("blackout-comics-session=auth_x; Path=/"))
                else -> htmlResponse(req, 200, guestHomeHtml())
            }
        }
        lateinit var failManager: BlackoutSessionManager
        val failClient = buildClient(MemoryCookieStore(), { failManager }, htmlOnlyHandler)
        failManager = BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = creds,
            cookieStore = MemoryCookieStore(),
            httpClientProvider = { OkHttpClient() },
            baseHeadersProvider = { Headers.headersOf() },
            ephemeralClientFactory = buildEphemeralClient(htmlOnlyHandler),
        )
        failManager.login()
        var threwIo = false
        try {
            failClient.newCall(imgReq).execute().close()
        } catch (_: java.io.IOException) {
            threwIo = true
        }
        assertTrue(threwIo)
    }
}
