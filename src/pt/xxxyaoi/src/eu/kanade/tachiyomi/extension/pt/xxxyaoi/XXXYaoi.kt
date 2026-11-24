package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.DEFAULT_CACHE_CONTROL
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class XXXYaoi : MadaraNoAjax() {

    override val supportsPostId = false

    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private val sessionManager = ChallengeInterceptor.createDefaultSessionManager()

    override fun OkHttpClient.Builder.configureClient() = apply {
        WebViewOriginHeader.ensure(baseUrl)
        interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
        addInterceptor(ChallengeInterceptor(sessionManager))
        addInterceptor(TransientRetryInterceptor())
        addInterceptor(ImageContentInterceptor())
        rateLimit(3, 1.seconds)
        addCookie { listOfNotNull(libraryConfigState?.config?.cookie) }
    }

    override val imageFetch: Fetch get() = Fetch.IMAGE

    // Do not leak Origin on GET navigation or override browser-matched headers.
    override fun Headers.Builder.configureHeaders() = apply {
        WebViewOriginHeader.ensure(baseUrl)
        removeAll("Origin")
    }

    override fun getHomeUrl(): String {
        WebViewOriginHeader.ensure(baseUrl)
        return "$baseUrl/biblioteca/"
    }

    override val supportsLatest = true

    /**
     * `3xyaoi.com` disabled view tracking (`enable_manga_view = "0"`) and aliases `views` /
     * `rating` / `trending` back to `latest` on `xyaoi_library_filter`. Use the site's real
     * `new-manga` (*Novidades*) listing here so Popular stays paginated and distinct from Latest
     * rather than representing an unexposed view ranking.
     */
    override suspend fun getPopularManga(page: Int) = libraryPage(page, "new-manga")

    override suspend fun getLatestUpdates(page: Int) = libraryPage(page, "latest")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = libraryPage(
        page = page,
        orderby = filters.firstInstance<SortFilter>().value,
        search = query.trim(),
        status = filters.firstInstance<StatusFilter>().value,
        genre = filters.firstInstanceOrNull<GenreFilter>()?.value.orEmpty(),
    )

    private suspend fun libraryPage(
        page: Int,
        orderby: String,
        search: String = "",
        status: String = "",
        genre: String = "",
    ): MangasPage {
        suspend fun libraryData(refresh: Boolean, failedGeneration: Long?): LibraryData {
            val state = libraryConfig(refresh, failedGeneration)
            val config = state.config
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("action", "xyaoi_library_filter")
                .apply {
                    config.formFields(page, search, status, genre)
                        .forEach { (name, value) -> addFormDataPart(name, value) }
                }
                .addFormDataPart("search", search)
                .addFormDataPart("status", status)
                .addFormDataPart("genre", genre)
                .addFormDataPart("orderby", orderby)
                .addFormDataPart("criador", "0")
                .addFormDataPart("page", page.toString())
                .build()
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("wp-admin/admin-ajax.php")
                .addQueryParameter("action", "xyaoi_library_filter")
                .build()
            val response = client.post(url, Fetch.XHR.from("/biblioteca/"), body, ensureSuccess = false)
            if (!refresh && (response.code == 403 || response.code == 400)) {
                response.close()
                sessionManager.markPossiblyExpired()
                return libraryData(refresh = true, failedGeneration = state.generation)
            }
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 403) {
                    sessionManager.markPossiblyExpired()
                    invalidateLibraryConfig(state.generation)
                }
                throw HttpException(code)
            }
            val parsed = try {
                response.parseAs<LibraryResponse>().toLibraryData()
            } catch (e: SerializationException) {
                if (!refresh) {
                    sessionManager.markPossiblyExpired()
                    return libraryData(refresh = true, failedGeneration = state.generation)
                }
                throw IllegalStateException("XXX Yaoi: resposta inválida da biblioteca (json-invalid).", e)
            }
            if (parsed == null) {
                if (!refresh) {
                    sessionManager.markPossiblyExpired()
                    return libraryData(refresh = true, failedGeneration = state.generation)
                }
                error("XXX Yaoi: catálogo rejeitou os parâmetros da biblioteca (ajax-rejected).")
            }
            return parsed
        }

        val library = libraryData(refresh = false, failedGeneration = null)
        return MangasPage(
            parseArchive(library.html.asJsoup(baseUrl)),
            library.currentPage < library.totalPages,
        )
    }

    private class CachedLibraryConfig(
        val config: LibraryConfig,
        val generation: Long,
    )

    private val libraryConfigMutex = Mutex()

    @Volatile
    private var libraryConfigState: CachedLibraryConfig? = null

    private fun invalidateLibraryConfig(expectedGeneration: Long) {
        val current = libraryConfigState ?: return
        if (current.generation == expectedGeneration) {
            libraryConfigState = null
        }
    }

    private suspend fun libraryConfig(
        refresh: Boolean,
        failedGeneration: Long? = null,
    ): CachedLibraryConfig = libraryConfigMutex.withLock {
        val existing = libraryConfigState
        if (!refresh && existing != null) {
            return@withLock existing
        }
        if (refresh && failedGeneration != null && existing != null && existing.generation > failedGeneration) {
            // A concurrent request already refreshed the library config after our failure.
            return@withLock existing
        }

        suspend fun fetchConfig(forceNetwork: Boolean): Pair<Document, LibraryConfig?> {
            val document = client.get(
                "$baseUrl/biblioteca/",
                Fetch.DOCUMENT,
                if (forceNetwork) CacheControl.FORCE_NETWORK else DEFAULT_CACHE_CONTROL,
            ).asJsoup()
            return document to LibraryConfig.fromDocument(document)
        }

        var (document, config) = fetchConfig(forceNetwork = refresh)
        if (config == null && !refresh) {
            val retried = fetchConfig(forceNetwork = true)
            document = retried.first
            config = retried.second
        }
        val resolved = config ?: run {
            val diagnosis = LibraryConfig.diagnose(document)
            if (diagnosis == "cloudflare-challenge" || diagnosis == "waf-blocked") {
                throw CloudflareSessionException(
                    state = sessionManager.state,
                    diagnosis = diagnosis,
                    message = "XXX Yaoi: bloqueio Cloudflare ao carregar a biblioteca ($diagnosis). Abra pelo ícone de globo para resolver.",
                )
            }
            error("XXX Yaoi: configuração da biblioteca não encontrada ($diagnosis).")
        }
        val nextGeneration = (existing?.generation ?: 0L) + 1L
        return@withLock CachedLibraryConfig(resolved, nextGeneration).also { libraryConfigState = it }
    }

    override val mangaSubString = "bl"
    override val hiatusStatus = super.hiatusStatus + "hiato"

    override val mangaDetailsSelectorTitle = ".xyaoi-main-title, h1"
    override val mangaDetailsSelectorAuthor = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(AUTOR)) a, a[href*=author]"
    override val mangaDetailsSelectorArtist = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(ARTISTA)) a, a[href*=artist]"
    override val mangaDetailsSelectorStatus = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(STATUS)) .xyaoi-prop-value, .xyaoi-prop-value[class*=status-value-], span:matchesOwn((?i)^status:?$) + span"
    override val mangaDetailsSelectorDescription = ".xyaoi-synopsis-text, .xyaoi-synopsis-content, [itemprop=description], [class*=synopsis-content]"
    override val mangaDetailsSelectorThumbnail = ".xyaoi-cover-img, div.summary_image img"

    override val mangaDetailsSelectorGenre = ".xyaoi-genres-list a, a[href*='/genero/'], a[rel=tag][href*='?genre=']"
    override val mangaDetailsSelectorTag = "[data-xxxyaoi-no-tags]"
    override val seriesTypeSelector = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(TIPO)) .xyaoi-prop-value"
    override val altNameSelector = "[data-xxxyaoi-no-alt-name]"
    override val genreDirectory = "genero"

    override fun archiveSelector() = "#xyaoi-cards-container > div, body > div:has(> a:has(img)), .xyaoi-search-card, .c-tabs-item__content"

    override val archiveUrlSelector = "a:has(img), h3 a, .post-title a"

    override fun archiveManga(element: Element, id: String): SManga? {
        val link = element.selectFirst(archiveUrlSelector) ?: return null
        val url = link.absUrl("href").toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter(String::isNotEmpty)
        if (url.host != baseUrl.toHttpUrl().host || segments.size != 2 || segments.firstOrNull() != mangaSubString) {
            return null
        }
        val titleText = element.selectFirst(".xyaoi-card-details a[href]:not(.xyaoi-chap-link), h2, h3, h4, .post-title a")
            ?.text()?.takeIf(String::isNotBlank)
            ?: link.selectFirst("img")?.attr("alt")?.takeIf(String::isNotBlank)
            ?: link.text().takeIf(String::isNotBlank)
            ?: return null
        val mangaPath = url.encodedPath
        return SManga.create().apply {
            this.url = mangaPath
            title = titleText
            thumbnail_url = element.selectFirst("img")?.let { processThumbnail(imageFromElement(it), true) }
            memo = mangaMemo(mangaPath, emptyList())
        }
    }

    override fun parseArchive(document: Document): List<SManga> = super.parseArchive(document).ifEmpty {
        Layout.cards(document).map { card ->
            SManga.create().apply {
                url = card.path
                title = card.title
                thumbnail_url = card.image?.let(::imageFromElement)
                memo = mangaMemo(card.path, emptyList())
            }
        }
    }.distinctBy(SManga::url)

    override fun parseSearchCards(document: Document): List<SearchCard> = super.parseSearchCards(document).ifEmpty {
        Layout.cards(document).map { SearchCard(it.title, it.path, it.image?.let(::imageFromElement)) }
    }

    override fun parseChapterList(document: Document, mangaPath: String): List<SChapter> {
        val scan = Layout.property(document, "Scan")?.text()
            ?.takeIf { it.isNotBlank() && !it.equals("Não informado", ignoreCase = true) }
        val base = document.location().toHttpUrlOrNull() ?: baseUrl.toHttpUrl()
        val encodedChapters = document.select("ul.version-chap > li, .wp-manga-chapter, [class*=chap-list] > li, [class*=chapter-list] > li, li[data-chap], li[data-xy], div[data-chap]").mapNotNull { rowElement ->
            val link = rowElement.selectFirst("a")
            val row = ChapterRow.fromElement(rowElement)
            val decodedHref = link?.let(ChapterRow::decodedHref)
            val rawHref = link?.attr("href")?.trim().orEmpty()
            val candidateUrl = decodedHref
                ?: rawHref.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                ?: row?.slug?.let { slug -> "${mangaPath.trimEnd('/')}/$slug/" }
                ?: return@mapNotNull null
            val resolved = base.resolve(mangaPath.trimEnd('/') + "/")?.resolve(candidateUrl) ?: return@mapNotNull null
            if (resolved.host != base.host) return@mapNotNull null
            val chapterSlug = row?.slug?.takeIf(String::isNotBlank)
                ?: resolved.encodedPath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val chapterName = row?.name?.takeIf(String::isNotBlank)
                ?: link?.selectFirst(".xyaoi-chapter-name")?.text()?.takeIf(String::isNotBlank)
                ?: link?.text()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val dateText = rowElement.selectFirst("time[datetime]")?.attr("datetime")
                ?: rowElement.selectFirst("time, .chapter-release-date, .xyaoi-chapter-date-line")?.text()
                ?: rowElement.selectFirst("[data-date]")?.attr("data-date")
            val cleanPath = resolved.newBuilder().removeAllQueryParameters("style").fragment(null).build().let {
                it.encodedPath + (it.encodedQuery?.let { query -> "?$query" } ?: "")
            }
            SChapter.create().apply {
                url = chapterSlug
                name = chapterName
                date_upload = Layout.absoluteDate(dateText) ?: parseChapterDate(dateText)
                scanlator = scan
                memo = buildJsonObject {
                    put("mangaPath", mangaPath)
                    put("chapterPath", cleanPath)
                }
            }
        }
        return encodedChapters.ifEmpty {
            Layout.chapters(document, mangaPath).map { entry ->
                SChapter.create().apply {
                    url = entry.path.substringBefore('?').trimEnd('/').substringAfterLast('/')
                    name = entry.name
                    date_upload = Layout.absoluteDate(entry.date) ?: parseChapterDate(entry.date)
                    scanlator = scan
                    memo = buildJsonObject {
                        put("mangaPath", mangaPath)
                        put("chapterPath", entry.path)
                    }
                }
            }
        }
    }

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> {
        val inline = mangaPage?.let { parseChapterList(it, mangaPath) }.orEmpty()
        return inline.ifEmpty {
            val url = "$baseUrl${mangaPath.trimEnd('/')}/ajax/chapters/"
            parseChapterList(client.post(url, xhrHeaders, Fetch.XHR.from(mangaPath), FormBody.Builder().build()).asJsoup(), mangaPath)
        }.also { check(it.isNotEmpty()) { "XXX Yaoi: capítulos não encontrados (chapter-links-not-found)." } }
    }

    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga {
        val title = (document.selectFirst(".xyaoi-main-title") ?: document.selectFirst("h1"))?.text()
        check(!title.isNullOrBlank()) {
            "XXX Yaoi: título da obra não encontrado."
        }
        return super.parseDetails(document, id, preserveUrl).apply {
            this.title = title
            author = author ?: Layout.property(document, "Autor")?.text()?.takeIf(String::isNotBlank)
            artist = artist ?: Layout.property(document, "Artista")?.text()?.takeIf(String::isNotBlank)
            if (status == SManga.UNKNOWN) status = Layout.property(document, "Status")?.text()?.toStatus() ?: status
            description = description
                ?: Layout.property(document, "Sinopse")?.text()?.takeIf(String::isNotBlank)
                ?: document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.takeIf(String::isNotBlank)
            thumbnail_url = thumbnail_url
                ?: document.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf(String::isNotBlank)
        }
    }

    // Keep saved legacy chapter paths usable while Madara migrates chapter memos.
    override fun getChapterUrl(chapter: SChapter): String = Layout.chapterUrl(
        baseUrl,
        chapter.url,
        (chapter.memo["mangaPath"] as? JsonPrimitive)?.content,
        (chapter.memo["chapterPath"] as? JsonPrimitive)?.content,
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val referer = chapterReferer(chapter, chapterUrl)

        suspend fun loadChapterDocument(): Document {
            val first = client.get(chapterUrl, Fetch.DOCUMENT.from(referer), CacheControl.FORCE_NETWORK).asJsoup()
            return if (first.selectFirst("#single-pager") != null) {
                val listUrl = first.location().toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("style", "list")?.build()
                    ?: error("XXX Yaoi: endereço de capítulo inválido.")
                client.get(listUrl, Fetch.DOCUMENT.from(chapterUrl), CacheControl.FORCE_NETWORK).asJsoup()
            } else {
                first
            }
        }

        suspend fun loadPagesOnce(document: Document): Pair<String, List<String>> {
            val playerUrl = Reader.playerIframeUrl(document)
            var playerRestriction: Reader.BrowserRestricted? = null
            if (playerUrl != null) {
                val playerDoc = client.get(playerUrl, Fetch.DOCUMENT.from(chapterUrl), CacheControl.FORCE_NETWORK).asJsoup()
                val playerPages = try {
                    Reader.extractPlayerPages(playerDoc).ifEmpty {
                        runCatching { Reader.load(playerDoc) { scriptUrl -> fetchScriptText(scriptUrl, playerUrl) } }.getOrDefault(emptyList())
                    }
                } catch (error: Reader.BrowserRestricted) {
                    playerRestriction = error
                    emptyList()
                }
                if (playerPages.isNotEmpty()) return playerUrl to playerPages
            }
            val directPages = try {
                Reader.load(document, { super.parsePages(document).mapNotNull(Page::imageUrl) }) { scriptUrl ->
                    fetchScriptText(scriptUrl, chapterUrl)
                }
            } catch (error: Reader.PagesNotFound) {
                playerRestriction?.let { throw it }
                throw error
            }
            return chapterUrl to directPages
        }

        val firstDoc = loadChapterDocument()
        val (pageReferer, imageUrls) = try {
            loadPagesOnce(firstDoc)
        } catch (firstError: Exception) {
            val shouldRefresh = (firstError is Reader.PagesNotFound || firstError is Reader.BrowserRestricted) &&
                Reader.playerIframeUrl(firstDoc) != null
            if (shouldRefresh) {
                val refreshedDoc = loadChapterDocument()
                loadPagesOnce(refreshedDoc)
            } else {
                throw firstError
            }
        }
        check(imageUrls.isNotEmpty()) { "XXX Yaoi: nenhuma página encontrada neste capítulo." }
        return imageUrls.mapIndexed { index, url -> Page(index, pageReferer, url) }
    }

    private suspend fun fetchScriptText(scriptUrl: String, referer: String): String? = client.get(scriptUrl, Fetch.SCRIPT.from(referer), ensureSuccess = false).use { response ->
        if (!response.isSuccessful) return@use null
        val body = response.body
        if (body.contentLength() > 512_000) return@use null
        val source = body.source()
        if (source.request(512_001)) null else source.readUtf8()
    }

    private fun chapterReferer(chapter: SChapter, chapterUrl: String): String {
        val mangaPath = (chapter.memo["mangaPath"] as? JsonPrimitive)?.content
        return mangaPath?.let { baseUrl.toHttpUrlOrNull()?.resolve(it)?.toString() }
            ?: chapterUrl.toHttpUrlOrNull()?.let { url ->
                url.newBuilder()
                    .encodedPath("/${url.pathSegments.dropLast(1).joinToString("/")}/")
                    .build()
                    .toString()
            }
            ?: baseUrl
    }

    // Image requests must not inherit document headers (Origin/Sec-Fetch/HTML Accept).
    // Cookies are still supplied by the same source client and its CookieJar.
    override fun getImageRequest(page: Page): Request = buildImageRequest(page, headers["User-Agent"])

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.let {
            try {
                it.parseAs<List<Genre>>()
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.orEmpty()
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                if (genres.isNotEmpty()) add(GenreFilter(genres))
            },
        )
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/biblioteca/", Fetch.DOCUMENT).asJsoup()
        val primary = document.select(".xyaoi-genre-item[data-genre], [data-genre]").mapNotNull { element ->
            val slug = element.attr("data-genre").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val label = element.selectFirst("> span:not(.xyaoi-genre-count):not(:matches(^\\(\\d+\\)$))")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: element.children().firstOrNull { !GENRE_COUNT_REGEX.matches(it.text().trim()) }?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: element.ownText().trim().takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            Genre(label, slug)
        }
        val genres = primary.ifEmpty {
            document.select("a[href*='/biblioteca/?genre='], a[href*='/genero/']").mapNotNull { link ->
                val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
                val slug = url.queryParameter("genre")?.trim()?.takeIf(String::isNotEmpty)
                    ?: url.pathSegments.filter(String::isNotEmpty).takeIf { it.size == 2 && it[0] == genreDirectory }?.get(1)
                    ?: return@mapNotNull null
                val label = link.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                Genre(label, slug)
            }
        }
        return genres.distinctBy(Genre::slug).toJsonElement()
    }
}

private val GENRE_COUNT_REGEX = Regex("""^\(\d+\)$""")

internal class TransientRetryInterceptor(
    private val maxRetries: Int = 2,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var response = chain.proceed(request)
        if (request.method != "GET") return response
        while (attempt < maxRetries && response.code in RETRY_CODES && !chain.call().isCanceled()) {
            // Do not hijack Cloudflare challenges (handled by ChallengeInterceptor).
            if (response.isCloudflareChallenge()) break
            val waitMs = retryDelayMs(response, attempt)
            response.close()
            attempt++
            sleepMs(waitMs)
            if (chain.call().isCanceled()) throw IOException("Request canceled")
            response = chain.proceed(request)
        }
        return response
    }

    private fun retryDelayMs(response: Response, attempt: Int): Long {
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        if (retryAfter != null && retryAfter in 1..3) return retryAfter * 1000L
        return if (attempt == 0) 400L else 900L
    }

    companion object {
        private val RETRY_CODES = setOf(429, 502, 503, 504)
    }
}

internal class ImageContentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val expectsImage = request.header("Accept")?.contains("image/", ignoreCase = true) == true
        if (!expectsImage || !response.isSuccessful) return response
        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
        if (contentType.startsWith("text/") || contentType.startsWith("application/xhtml") || contentType.startsWith("application/json")) {
            response.close()
            throw IOException("XXX Yaoi: resposta inesperada ($contentType) ao baixar imagem.")
        }
        return response
    }
}

internal fun buildImageRequest(page: Page, userAgent: String?): Request {
    require('\n' !in page.url && '\r' !in page.url) { "XXX Yaoi: referência de capítulo inválida." }
    val chapterUrl = page.url.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("XXX Yaoi: referência de capítulo inválida.")
    require(chapterUrl.username.isEmpty() && chapterUrl.password.isEmpty()) { "XXX Yaoi: referência de capítulo inválida." }
    val rawImageUrl = page.imageUrl?.trim()
        ?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        ?: throw IllegalArgumentException("XXX Yaoi: endereço de imagem inválido.")
    val imageUrl = rawImageUrl.toHttpUrlOrNull()
        ?.takeIf { (it.scheme == "http" || it.scheme == "https") && it.username.isEmpty() && it.password.isEmpty() }
        ?: throw IllegalArgumentException("XXX Yaoi: endereço de imagem inválido.")
    return Request.Builder()
        .url(imageUrl)
        .headers(
            Headers.Builder().apply {
                userAgent?.let { set("User-Agent", it) }
                set("Accept", "image/*")
                set("Referer", chapterUrl.toString())
            }.build(),
        )
        .get()
        .build()
}
