package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@Source
abstract class BlackoutComics :
    KeiSource(),
    ConfigurableSource {

    override val imageFetch: Fetch = Fetch.IMAGE

    private val preferences by getPreferencesLazy()

    private val authClient: OkHttpClient by lazy {
        client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private val sessionManager by lazy {
        BlackoutSessionManager(
            baseUrl = baseUrl,
            credentials = object : BlackoutCredentialStore {
                override fun getEmail(): String = preferences.getString(PREF_EMAIL, "").orEmpty()
                override fun getPassword(): String = preferences.getString(PREF_PASSWORD, "").orEmpty()
            },
            cookieStore = AndroidCookieStore(),
            httpClientProvider = { authClient },
            baseHeadersProvider = { headers },
        )
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_EMAIL || key == PREF_PASSWORD) {
            sessionManager.onCredentialsChanged()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        removeAll("Origin")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3, 1.seconds)
        addCookie {
            val now = Clock.System.now()
            val consentAt = now.toEpochMilliseconds()
            val expiresAt = (now + 6.days).toEpochMilliseconds()
            listOf("age_gate_consent" to "%7B%22consentAt%22%3A$consentAt%2C%22expiresAt%22%3A$expiresAt%7D")
        }
        addInterceptor(sessionManager::intercept)
    }

    override fun getMangaUrl(manga: SManga): String {
        val path = if (manga.url.startsWith("/")) manga.url else "/${manga.url}"
        return if (manga.url.startsWith("http")) manga.url else "$baseUrl$path"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val path = if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"
        return if (chapter.url.startsWith("http")) chapter.url else "$baseUrl$path"
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/ranking", Fetch.DOCUMENT.from("$baseUrl/")).asJsoup()
        val mangas = parsePopularCards(document)
        return MangasPage(mangas, false)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/atualizados-recente".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url, Fetch.DOCUMENT.from("$baseUrl/")).asJsoup()
        val mangas = parseGridCards(document)
        val hasNextPage = hasNextPage(document)
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("comics")
            if (query.isNotBlank()) {
                addQueryParameter("src", query.trim())
            }
            filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("status", it)
            }
            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("gen", it)
            }
            filters.firstInstanceOrNull<OrderFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("order", it)
            }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT.from("$baseUrl/comics")).asJsoup()
        val mangas = parseGridCards(document)
        val hasNextPage = hasNextPage(document)
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Manga Details & Chapters =============

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "comics") return null
        val id = segments[1]
        if (id.toLongOrNull() == null) return null

        val mangaPath = "/comics/$id"
        val document = client.get("$baseUrl$mangaPath", Fetch.DOCUMENT.from("$baseUrl/")).asJsoup()
        return parseMangaDetails(document).apply {
            this.url = mangaPath
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT.from("$baseUrl/")).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ============================== Pages & Images =======================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaPath = chapter.url.substringBefore("/ler")
        val mangaUrl = if (mangaPath.startsWith("http")) mangaPath else "$baseUrl$mangaPath"
        val chapterUrl = getChapterUrl(chapter)

        var response = client.get(
            chapterUrl,
            Fetch.DOCUMENT.from(mangaUrl),
            cacheControl = CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        )
        var finalUrl = response.request.url
        var finalPath = finalUrl.encodedPath
        var statusCode = response.code
        var html = response.body.string()

        if (finalUrl.queryParameter("notif") == "aviso-compra") {
            val price = finalUrl.queryParameter("price")
            throw IOException(
                if (!price.isNullOrBlank()) {
                    "Capítulo bloqueado/pago no site ($price moedas). Desbloqueie pela WebView."
                } else {
                    "Capítulo bloqueado/pago no site. Desbloqueie pela WebView."
                },
            )
        }

        val initialGuestRedirect = response.isSuccessful &&
            (finalPath == "/login" || finalPath == "/" || BlackoutSessionManager.isGuestHtml(html))
        if (initialGuestRedirect && sessionManager.hasCredentials()) {
            when (val authResult = sessionManager.ensureAuthenticatedForReader()) {
                is BlackoutAuthResult.Failure -> throw IOException(authResult.message)
                BlackoutAuthResult.Success -> {
                    response = client.get(
                        chapterUrl,
                        Fetch.DOCUMENT.from(mangaUrl),
                        cacheControl = CacheControl.FORCE_NETWORK,
                        ensureSuccess = false,
                    )
                    finalUrl = response.request.url
                    finalPath = finalUrl.encodedPath
                    statusCode = response.code
                    html = response.body.string()
                }
            }
        }

        if (finalUrl.queryParameter("notif") == "aviso-compra") {
            val price = finalUrl.queryParameter("price")
            throw IOException(
                if (!price.isNullOrBlank()) {
                    "Capítulo bloqueado/pago no site ($price moedas). Desbloqueie pela WebView."
                } else {
                    "Capítulo bloqueado/pago no site. Desbloqueie pela WebView."
                },
            )
        }

        if (statusCode == 401 || statusCode == 403 || html.contains("403 – Acesso Proibido", ignoreCase = true)) {
            val authDetail = sessionManager.lastFailureMessage()
            throw IOException(
                authDetail
                    ?: "Acesso negado ao capítulo (HTTP $statusCode). Verifique sua conta nas configurações ou abra na WebView.",
            )
        }
        if (statusCode == 404) {
            throw IOException("Capítulo não encontrado (HTTP 404).")
        }
        if (!response.isSuccessful) {
            throw IOException("Falha ao abrir capítulo (HTTP $statusCode).")
        }

        val document = org.jsoup.Jsoup.parse(html, chapterUrl)
        val urls = extractPageUrls(document, html, baseUrl)

        if (urls.isEmpty()) {
            if (finalPath == "/login" || finalPath == "/" || BlackoutSessionManager.isGuestHtml(html)) {
                throw IOException(
                    if (sessionManager.hasCredentials()) {
                        sessionManager.lastFailureMessage()
                            ?: "O servidor recusou o acesso ao capítulo para a sessão atual. Abra a fonte na WebView para verificar."
                    } else {
                        "Login necessário para ler este capítulo. Configure seu e-mail e senha nas configurações da extensão."
                    },
                )
            }
            throw IOException("Nenhuma página encontrada neste capítulo.")
        }

        return urls.mapIndexed { index, imageUrl ->
            Page(index, url = chapterUrl, imageUrl = imageUrl)
        }
    }

    override fun getImageRequest(page: Page): Request {
        val cleanHeaders = headers.newBuilder()
            .removeAll("Referer")
            .removeAll("Origin")
            .set("Accept", "image/*")
            .build()
        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(cleanHeaders)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .fetch(Fetch.XHR)
            .build()
    }

    // ============================== Preferences ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        val warning = "⚠️ Os dados inseridos nesta seção serão usados somente para realizar o login automático na fonte."
        val message = "Insira %s para acessar obras e capas completas da sua conta."

        EditTextPreference(context).apply {
            key = PREF_EMAIL
            title = "E-mail"
            summary = "E-mail de acesso"
            dialogMessage = buildString {
                appendLine(message.format("seu e-mail"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            setOnPreferenceChangeListener { _, _ ->
                sessionManager.onCredentialsChanged()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Senha"
            summary = "Senha de acesso"
            dialogMessage = buildString {
                appendLine(message.format("sua senha"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                attachPasswordVisibilityToggle(editText)
            }
            setOnPreferenceChangeListener { _, _ ->
                sessionManager.onCredentialsChanged()
                true
            }
        }.let(screen::addPreference)
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        GenreFilter(),
        OrderFilter(),
    )

    private inner class AndroidCookieStore : BlackoutCookieStore {
        private fun getWebCookieManager(): CookieManager? = try {
            CookieManager.getInstance()
        } catch (_: Throwable) {
            null
        }

        override fun loadSessionCookies(url: HttpUrl): List<Cookie> = client.cookieJar.loadForRequest(url)

        override fun syncFromWebView(url: HttpUrl): Boolean {
            val cookieManager = getWebCookieManager() ?: return false
            val cookieHeader = cookieManager.getCookie(url.toString())
                ?: cookieManager.getCookie("${url.scheme}://${url.host}/")
                ?: return false
            if (cookieHeader.isBlank()) return false

            val parsed = parseCookieHeader(url, cookieHeader)
            if (parsed.isEmpty()) return false
            client.cookieJar.saveFromResponse(url, parsed)
            cookieManager.flush()
            return parsed.any {
                it.name == BlackoutSessionManager.SESSION_COOKIE &&
                    !it.value.equals("deleted", ignoreCase = true)
            }
        }

        override fun saveCookies(url: HttpUrl, cookies: List<Cookie>) {
            val cookieManager = getWebCookieManager()
            client.cookieJar.saveFromResponse(url, cookies)
            cookies.forEach { cookie ->
                cookieManager?.setCookie(url.toString(), cookie.toString())
            }
            cookieManager?.flush()
        }

        override fun clearSessionCookies(url: HttpUrl) {
            val cookieManager = getWebCookieManager()
            val existing = client.cookieJar.loadForRequest(url)
            val webNames = cookieManager?.getCookie(url.toString())
                ?.split(';')
                ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            val namesToExpire = (existing.map { it.name } + webNames)
                .distinct()
                .filter { it == BlackoutSessionManager.SESSION_COOKIE || it == BlackoutSessionManager.XSRF_COOKIE }

            val expired = namesToExpire.map { name ->
                Cookie.Builder()
                    .domain(url.host)
                    .path("/")
                    .name(name)
                    .value("")
                    .expiresAt(0L)
                    .build()
            }
            if (expired.isNotEmpty()) {
                client.cookieJar.saveFromResponse(url, expired)
            }
            namesToExpire.forEach { name ->
                cookieManager?.setCookie(url.toString(), "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                cookieManager?.setCookie(url.toString(), "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=.${url.host}; Path=/")
            }
            cookieManager?.flush()
        }

        override fun evictHttpCache() {
            client.cache?.evictAll()
        }
    }

    companion object {
        internal data class ParsedMangaCard(
            val url: String,
            val title: String,
            val thumbnailUrl: String?,
        )

        internal data class ParsedMangaDetails(
            val title: String,
            val author: String?,
            val artist: String?,
            val genre: String?,
            val description: String,
            val thumbnailUrl: String?,
            val status: Int,
        )

        internal data class ParsedChapter(
            val url: String,
            val name: String,
            val chapterNumber: Float,
            val dateUpload: Long,
        )

        private val CHAPTER_URL_REGEX = Regex("""/comics/\d+/ler/[a-zA-Z0-9_-]+|https?://[^'"]+/comics/\d+/ler/[a-zA-Z0-9_-]+""")
        private val PAGE_LIST_SCRIPT_REGEX = Regex("""\bS\s*=\s*(\[[\s\S]*?])""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        private val BR_SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yy")

        private const val PREF_EMAIL = "blackout_email"
        private const val PREF_PASSWORD = "blackout_password"

        internal fun hasNextPage(document: Document): Boolean = document.selectFirst("a.pagerx__link[rel=next], a[rel=next], li.page-item a[rel=next]") != null

        internal fun parsePopularCardData(document: Document): List<ParsedMangaCard> {
            val cards = document.select(
                "section.ranking-featured-grid a.ranking-featured-card, " +
                    "section.ranking-standard-grid a.webtoon-card, " +
                    "div.ranking-grid a.webtoon-card, " +
                    "div.ranking-grid a.ranking-item, " +
                    "a.ranking-item",
            )
            return cards.mapNotNull(::cardToData)
                .distinctBy { it.url }
        }

        internal fun parsePopularCards(document: Document): List<SManga> = parsePopularCardData(document).map(::toSManga)

        internal fun parseGridCardData(document: Document): List<ParsedMangaCard> = document.select("div.webtoon-grid a.webtoon-card, a.webtoon-card")
            .mapNotNull(::cardToData)
            .distinctBy { it.url }

        internal fun parseGridCards(document: Document): List<SManga> = parseGridCardData(document).map(::toSManga)

        private fun toSManga(card: ParsedMangaCard): SManga = SManga.create().apply {
            url = card.url
            title = card.title
            thumbnail_url = card.thumbnailUrl
        }

        private fun cardToData(element: Element): ParsedMangaCard? {
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            if (href.isBlank() || href.contains("\${")) return null

            val title = element.selectFirst("div.card-title span, div.ranking-featured-info h2, h2")
                ?.text()
                ?.takeIf { it.isNotBlank() }
                ?: element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: return null

            val cover = extractBestImageUrl(element.selectFirst("img"))
            return ParsedMangaCard(
                url = toRelativeUrl(href),
                title = title,
                thumbnailUrl = cover,
            )
        }

        internal fun extractBestImageUrl(img: Element?): String? {
            if (img == null) return null

            val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                val bestSrc = srcset.split(',')
                    .mapNotNull { candidate ->
                        val parts = candidate.trim().split(WHITESPACE_REGEX)
                        val rawUrl = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val weight = parts.getOrNull(1)?.removeSuffix("w")?.removeSuffix("x")?.toFloatOrNull() ?: 1f
                        rawUrl to weight
                    }
                    .maxByOrNull { it.second }
                    ?.first
                if (!bestSrc.isNullOrBlank() && !bestSrc.contains("\${")) {
                    return resolveUrl(img, bestSrc)
                }
            }

            val candidates = listOf("data-src", "data-original", "src")
            for (attr in candidates) {
                val abs = img.absUrl(attr)
                if (abs.isNotBlank() && !abs.contains("\${") && !abs.startsWith("data:")) {
                    return abs
                }
                val raw = img.attr(attr).trim()
                if (raw.isNotBlank() && !raw.contains("\${") && !raw.startsWith("data:")) {
                    return resolveUrl(img, raw)
                }
            }
            return null
        }

        private fun toRelativeUrl(rawUrl: String): String {
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                return if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"
            }
            val parsed = rawUrl.toHttpUrl()
            return buildString {
                append(parsed.encodedPath)
                parsed.encodedQuery?.let { append('?').append(it) }
            }
        }

        private fun resolveUrl(element: Element, rawUrl: String): String {
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
            val baseUri = element.baseUri().takeIf { it.startsWith("http") } ?: return rawUrl
            return baseUri.toHttpUrl().resolve(rawUrl)?.toString() ?: rawUrl
        }

        internal fun parseMangaDetailsData(document: Document): ParsedMangaDetails {
            val title = document.selectFirst("h1.project-title")?.text()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" |")?.trim()
                ?: throw IllegalStateException("Título da obra não encontrado.")

            val author = document.selectFirst("div.project-quick-info span.quick-info-item:has(.fa-pen-nib) strong")?.text()
            val artist = document.selectFirst("div.project-quick-info span.quick-info-item:has(.fa-palette) strong")?.text()

            val genre = document.select("div.project-genres span.genre-tag")
                .joinToString { it.text() }
                .takeIf { it.isNotEmpty() }

            val description = buildString {
                document.selectFirst("div.project-description p")?.text()?.let { append(it) }
                document.selectFirst("p.project-subtitle")?.text()?.takeIf { it.isNotEmpty() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Título alternativo: ").append(it)
                }
            }

            val thumbnailUrl = extractBestImageUrl(document.selectFirst("img.project-cover"))
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")

            val statusText = document.selectFirst("div.detail-item:has(span.detail-label:contains(Status)) span.status-pill, span.status-pill")
                ?.text()
                .orEmpty()
            val status = when {
                statusText.contains("Completo", ignoreCase = true) -> SManga.COMPLETED
                statusText.contains("Em Lançamento", ignoreCase = true) ||
                    statusText.contains("Lançamento", ignoreCase = true) ||
                    statusText.contains("Andamento", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Hiato", ignoreCase = true) -> SManga.ON_HIATUS
                statusText.contains("Cancelado", ignoreCase = true) -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            return ParsedMangaDetails(
                title = title,
                author = author,
                artist = artist,
                genre = genre,
                description = description,
                thumbnailUrl = thumbnailUrl,
                status = status,
            )
        }

        internal fun parseMangaDetails(document: Document): SManga {
            val parsed = parseMangaDetailsData(document)
            return SManga.create().apply {
                title = parsed.title
                author = parsed.author
                artist = parsed.artist
                genre = parsed.genre
                description = parsed.description
                thumbnail_url = parsed.thumbnailUrl
                status = parsed.status
            }
        }

        internal fun parseChapterDataList(document: Document, mangaUrl: String): List<ParsedChapter> {
            val chapterElements = document.select("ol#tab-capitulos-list li, ol.list-ep li")
                .ifEmpty { document.select("div.chapter-link-wrap") }
            val normalizedMangaPath = if (mangaUrl.startsWith("/")) mangaUrl else "/$mangaUrl"

            if (chapterElements.isNotEmpty()) {
                val chapters = chapterElements.mapNotNull { item ->
                    val linkWrap = if (item.hasClass("chapter-link-wrap")) item else item.selectFirst("div.chapter-link-wrap") ?: item
                    val onclick = linkWrap.attr("onclick")
                    val capNumText = linkWrap.selectFirst("div.cell-num span.num, span.num")?.text()
                        ?.replace("Capítulo", "", ignoreCase = true)
                        ?.replace("Cap", "", ignoreCase = true)
                        ?.trim()
                    val capNum = capNumText?.toFloatOrNull() ?: -1f

                    val chapterUrl = linkWrap.selectFirst("a[href]")?.attr("href")?.takeIf { "/ler/" in it }
                        ?: CHAPTER_URL_REGEX.find(onclick)?.value
                        ?: if (capNum >= 0f) {
                            "$normalizedMangaPath/ler/capitulo-${capNumText ?: capNum.toInt()}"
                        } else {
                            null
                        }
                        ?: return@mapNotNull null

                    val titleText = linkWrap.selectFirst("div.cell-title strong")?.text()?.takeIf { it.isNotBlank() }
                    val timeAttr = linkWrap.selectFirst("time[datetime]")?.attr("datetime")
                    val shortDate = linkWrap.selectFirst("div.cell-num div.text-muted")?.text()

                    val name = buildString {
                        if (!capNumText.isNullOrEmpty()) {
                            append("Capítulo ").append(capNumText)
                        } else {
                            append("Capítulo")
                        }
                        if (!titleText.isNullOrEmpty()) {
                            append(" - ").append(titleText)
                        }
                    }
                    val dateUpload = ISO_DATE_FORMATTER.tryParseDate(timeAttr)
                        .takeIf { it > 0L }
                        ?: BR_SHORT_DATE_FORMATTER.tryParseDate(shortDate)

                    ParsedChapter(
                        url = toRelativeUrl(chapterUrl),
                        name = name,
                        chapterNumber = capNum,
                        dateUpload = dateUpload,
                    )
                }.distinctBy { it.url }
                if (chapters.isNotEmpty()) return chapters
            }

            return document.select("div.chapters-modal-item").mapNotNull { item ->
                val numText = item.selectFirst("span.chapters-modal-num")?.text().orEmpty()
                val capNumText = numText.replace("Capítulo", "", ignoreCase = true)
                    .replace("Cap", "", ignoreCase = true)
                    .trim()
                val capNum = capNumText.toFloatOrNull() ?: -1f
                if (capNumText.isEmpty() && capNum < 0f) return@mapNotNull null
                val chapterUrl = "$normalizedMangaPath/ler/capitulo-${capNumText.ifEmpty { capNum.toInt().toString() }}"

                ParsedChapter(
                    url = chapterUrl,
                    name = numText.ifEmpty { "Capítulo $capNumText" },
                    chapterNumber = capNum,
                    dateUpload = 0L,
                )
            }
        }

        internal fun parseChapterList(document: Document, manga: SManga): List<SChapter> = parseChapterDataList(document, manga.url).map { parsed ->
            SChapter.create().apply {
                url = parsed.url
                name = parsed.name
                chapter_number = parsed.chapterNumber
                date_upload = parsed.dateUpload
            }
        }

        internal fun extractPageUrls(document: Document, html: String, baseUrl: String): List<String> {
            val encodedPayload = document.selectFirst("#reader-payload")?.attr("data-payload")?.trim()
            if (!encodedPayload.isNullOrEmpty()) {
                val decoded = encodedPayload.decodeBase64()?.utf8()
                    ?: encodedPayload.replace('-', '+').replace('_', '/').decodeBase64()?.utf8()
                if (!decoded.isNullOrBlank()) {
                    val list = decoded.parseAs<List<String>>()
                    return list.mapNotNull { normalizePageUrl(it, baseUrl) }
                }
            }

            val domPages = document.select("#pages img.slice, #viewer-img img")
                .mapNotNull { extractBestImageUrl(it) }
                .mapNotNull { normalizePageUrl(it, baseUrl) }
            if (domPages.isNotEmpty()) return domPages

            for (script in document.select("script:not([src])")) {
                val match = PAGE_LIST_SCRIPT_REGEX.find(script.html()) ?: continue
                val urls = match.groupValues[1].parseAs<List<String>>()
                val normalized = urls.mapNotNull { normalizePageUrl(it, baseUrl) }
                if (normalized.isNotEmpty()) return normalized
            }

            return emptyList()
        }

        private fun normalizePageUrl(raw: String, baseUrl: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("data:")) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "${baseUrl.trimEnd('/')}$path"
        }
    }
}

internal fun attachPasswordVisibilityToggle(editText: EditText) {
    editText.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    editText.transformationMethod = PasswordTransformationMethod.getInstance()
    editText.setSelection(editText.text?.length ?: 0)

    val parent = editText.parent as? ViewGroup ?: return
    if (parent.tag == PASSWORD_CONTAINER_TAG) return

    val context = editText.context
    val density = context.resources.displayMetrics.density
    val iconSize = (40 * density).toInt()
    val iconPad = (8 * density).toInt()
    val eye = BlackoutEyeDrawable(
        density = density,
        color = (editText.currentTextColor and 0x00FFFFFF) or 0x99000000.toInt(),
    )

    val index = parent.indexOfChild(editText)
    val originalParams = editText.layoutParams
    parent.removeViewAt(index)

    val container = FrameLayout(context).apply {
        tag = PASSWORD_CONTAINER_TAG
        layoutParams = originalParams
    }

    editText.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    editText.setPaddingRelative(
        editText.paddingStart,
        editText.paddingTop,
        maxOf(editText.paddingEnd, iconSize + iconPad),
        editText.paddingBottom,
    )
    container.addView(editText)

    val toggle = ImageView(context).apply {
        setImageDrawable(eye)
        contentDescription = "Mostrar senha"
        isClickable = true
        isFocusable = true
        setPadding(iconPad, iconPad, iconPad, iconPad)
        setOnClickListener {
            val showPassword = !eye.isPasswordVisible
            eye.isPasswordVisible = showPassword
            contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha"
            applyPasswordVisibility(editText, showPassword)
        }
    }
    val toggleParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        marginEnd = (4 * density).toInt()
    }
    container.addView(toggle, toggleParams)
    parent.addView(container, index)
}

internal fun applyPasswordVisibility(editText: EditText, visible: Boolean) {
    val start = editText.selectionStart.coerceAtLeast(0)
    val end = editText.selectionEnd.coerceAtLeast(0)
    editText.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
    editText.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
    val len = editText.text?.length ?: 0
    editText.setSelection(start.coerceIn(0, len), end.coerceIn(0, len))
}

private const val PASSWORD_CONTAINER_TAG = "blackout_password_container"

private class BlackoutEyeDrawable(
    private val density: Float,
    private val color: Int,
) : Drawable() {
    var isPasswordVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@BlackoutEyeDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@BlackoutEyeDrawable.color
        style = Paint.Style.FILL
    }

    override fun getIntrinsicWidth(): Int = (24 * density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * density).toInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val w = 18f * density
        val h = 10f * density

        val path = Path().apply {
            moveTo(cx - w / 2, cy)
            quadTo(cx, cy - h, cx + w / 2, cy)
            quadTo(cx, cy + h, cx - w / 2, cy)
            close()
        }
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(cx, cy, 2.5f * density, pupilPaint)

        if (!isPasswordVisible) {
            canvas.drawLine(cx - w / 2, cy - h / 1.5f, cx + w / 2, cy + h / 1.5f, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        pupilPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        pupilPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun parseCookieHeader(url: HttpUrl, cookieHeader: String): List<Cookie> = cookieHeader.split(';')
    .mapNotNull { entry ->
        val trimmed = entry.trim()
        val sep = trimmed.indexOf('=')
        if (sep <= 0) return@mapNotNull null
        val name = trimmed.substring(0, sep).trim()
        val value = trimmed.substring(sep + 1).trim()
        if (name.isEmpty() || value.isEmpty() || value.equals("deleted", ignoreCase = true)) return@mapNotNull null
        Cookie.Builder()
            .domain(url.host)
            .path("/")
            .name(name)
            .value(value)
            .build()
    }
