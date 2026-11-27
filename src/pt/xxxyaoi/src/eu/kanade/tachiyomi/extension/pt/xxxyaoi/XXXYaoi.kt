package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class XXXYaoi : KeiSource() {

    override val supportsFilterFetching = true

    override val supportsRelatedMangas = true

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(TransientRetryInterceptor())
        addInterceptor(ImageContentInterceptor())
        rateLimit(3, 1.seconds)
    }

    private val sitePages by lazy { SitePages({ baseUrl }) { headers["User-Agent"]!! } }

    override fun getHomeUrl() = "$baseUrl/biblioteca/"

    override suspend fun getPopularManga(page: Int) = libraryPage(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = sitePages.document(pageUrl(page), "loaded.js")
        val mangas = document.select(".page-item-detail").mapNotNull { card ->
            val link = card.selectFirst("h3 a[href]") ?: return@mapNotNull null
            val path = link.absUrl("href").toHttpUrl().encodedPath.takeIf(SERIES_PATH::matches) ?: return@mapNotNull null
            SManga.create().apply {
                url = path
                title = link.textOrNull() ?: return@mapNotNull null
                thumbnail_url = card.selectFirst("img")?.cover()
            }
        }
        return MangasPage(mangas, document.selectFirst("a[href=\"${pageUrl(page + 1)}\"]") != null)
    }

    private fun pageUrl(page: Int) = baseUrl.toHttpUrl().newBuilder().apply {
        if (page > 1) addPathSegment("page").addPathSegment(page.toString()).addPathSegment("")
    }.build().toString()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = libraryPage(
        page = page,
        orderby = filters.firstInstance<SortFilter>().value,
        search = query.trim(),
        status = filters.firstInstance<StatusFilter>().value,
        genre = filters.firstInstanceOrNull<GenreFilter>()?.value.orEmpty(),
    )

    private suspend fun libraryPage(page: Int, orderby: String = DEFAULT_ORDER, search: String = "", status: String = "", genre: String = ""): MangasPage {
        val url = getHomeUrl().toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("pg", page.toString())
            if (orderby != DEFAULT_ORDER) addQueryParameter("orderby", orderby)
            if (search.isNotEmpty()) addQueryParameter("search", search)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            if (genre.isNotEmpty()) addQueryParameter("genre", genre)
        }.build()
        val replaced = page > 1 || orderby != DEFAULT_ORDER || status.isNotEmpty() || genre.isNotEmpty()
        val document = sitePages.document(url.toString(), "library.js", replaced.toString())
        val mangas = document.select("a[href]:has(img[alt])").mapNotNull { link ->
            val path = link.absUrl("href").toHttpUrl().encodedPath.takeIf(SERIES_PATH::matches) ?: return@mapNotNull null
            val image = link.selectFirst("img[alt]")!!
            SManga.create().apply {
                this.url = path
                title = image.attrOrNull("alt")!!
                thumbnail_url = image.cover()
                this.status = link.selectFirst("[class*=status]")?.text().toStatus()
            }
        }.distinctBy { it.url }
        val hasNextPage = document.select("button").any { (it.ownText().toIntOrNull() ?: 0) > page }
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val document = seriesDocument(getMangaUrl(manga))
        return SMangaUpdate(parseDetails(document).apply { url = manga.url }, parseChapters(document))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "bl" || url.pathSegments.size < 2) return null
        val seriesUrl = url.newBuilder().encodedPath("/").addPathSegment("bl").addPathSegment(url.pathSegments[1]).addPathSegment("").query(null).build()
        return parseDetails(seriesDocument(seriesUrl.toString())).apply { initialized = true }
    }

    private suspend fun seriesDocument(url: String) = sitePages.document(url, "loaded.js")

    private fun parseDetails(document: Document) = SManga.create().apply {
        url = document.location().toHttpUrl().encodedPath
        title = document.selectFirst("h1")!!.textOrNull()!!
        author = document.select("a[href*=/author/]").eachText().joinToString().ifBlank { null }
        artist = document.select("a[href*=/artist/]").eachText().joinToString().ifBlank { null }
        status = document.property("STATUS").toStatus()
        thumbnail_url = document.selectFirst(".xyaoi-cover-img")?.cover()
        description = MarkdownDescription.Builder()
            .description(document.selectFirst(".xyaoi-synopsis-text"))
            .field("Lançamento", document.property("LANÇAMENTO"))
            .field("Classificação", document.property("CLASSIFICAÇÃO"))
            .field("Info", document.property("INFO"))
            .altTitles(document.selectFirst("#xyaoi-alt-content")?.text()?.split(", ").orEmpty(), "Nomes alternativos")
            .build()
        genre = buildList {
            document.property("TIPO")?.let(::add)
            addAll(document.select("a[rel=tag][href*='?genre=']").eachText())
        }.distinct().joinToString().ifBlank { null }
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val mangaPath = document.location().toHttpUrl().encodedPath
        val scan = document.property("SCAN")
        return document.select("li > a[href]").mapNotNull { link ->
            val path = link.absUrl("href").toHttpUrl().encodedPath
            if (!path.startsWith(mangaPath)) return@mapNotNull null
            val slug = path.removePrefix(mangaPath).trimEnd('/')
            if (slug.isEmpty() || '/' in slug) return@mapNotNull null
            SChapter.create().apply {
                url = slug
                name = link.selectFirst("header > *")!!.text()
                date_upload = parseDate(link.selectFirst("time")?.text())
                scanlator = scan
                memo = buildJsonObject { put("mangaPath", mangaPath) }
            }
        }.distinctBy { it.url }
    }

    override fun getChapterUrl(chapter: SChapter): String = resolveChapterUrl(baseUrl, chapter)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val read = sitePages.read(getChapterUrl(chapter), "reader.js", carrySession = true)
        val reader = read.document
        if (reader.pages.isEmpty()) {
            val document = reader.html.asJsoup(reader.url)
            if (document.selectFirst("iframe[src*=player]") != null) return emptyList()
            if (document.selectFirst("iframe[src*=challenges.cloudflare.com], input[name=cf-turnstile-response]") != null) {
                throw IOException(SitePages.SOLVE_IN_WEBVIEW)
            }
            throw IOException("As páginas do capítulo não carregaram, tente novamente")
        }
        return reader.pages.mapIndexed { index, imageUrl ->
            val siteHeaders = read.imageHeaders[imageUrl].orEmpty().toJsonString()
            Page(index, url = reader.url.toHttpUrl().newBuilder().fragment(siteHeaders).build().toString(), imageUrl = imageUrl)
        }
    }

    override fun getImageRequest(page: Page): Request {
        val siteHeaders = page.url.toHttpUrl().fragment?.parseAs<Map<String, String>>().orEmpty()
        return buildImageRequest(page, headers["User-Agent"], siteHeaders)
            .newBuilder()
            .fetch(Fetch.XHR.from(page.url))
            .build()
    }

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = seriesDocument(getMangaUrl(manga))
        .select("a.xyaoi-scan-work-item")
        .mapNotNull { link ->
            val path = link.absUrl("href").toHttpUrl().encodedPath.takeIf(SERIES_PATH::matches) ?: return@mapNotNull null
            SManga.create().apply {
                url = path
                title = link.attrOrNull("title") ?: return@mapNotNull null
                thumbnail_url = link.selectFirst("img")?.cover()
            }
        }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<Genre>>().orEmpty()
        return FilterList(
            buildList {
                add(SortFilter())
                add(StatusFilter())
                if (genres.isNotEmpty()) add(GenreFilter(genres))
            },
        )
    }

    override suspend fun fetchFilterData(): JsonElement = sitePages.document(getHomeUrl(), "library.js", "false")
        .select("[data-genre]")
        .mapNotNull { element ->
            val slug = element.attrOrNull("data-genre") ?: return@mapNotNull null
            Genre(element.selectFirst("> span")!!.text(), slug)
        }
        .distinctBy { it.slug }
        .toJsonElement()

    private fun Document.property(label: String) = selectFirst(":matchesOwn(^$label$) + *:not(:containsOwn(Não informado))")?.textOrNull()

    private fun Element.cover() = absUrl("src").ifEmpty { null }?.replace(RESIZED_REGEX, "")

    private fun String?.toStatus(): Int {
        val status = this?.lowercase() ?: return SManga.UNKNOWN
        return when {
            "andamento" in status -> SManga.ONGOING
            "conclu" in status -> SManga.COMPLETED
            "hiatus" in status -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(date: String?): Long {
        val value = date?.lowercase() ?: return 0
        val amount = AGO_REGEX.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: return DATE_FORMAT.tryParseDate(value, ZoneOffset.UTC)
        val unit = when {
            "minuto" in value -> ChronoUnit.MINUTES
            "hora" in value -> ChronoUnit.HOURS
            "dia" in value -> ChronoUnit.DAYS
            else -> return 0
        }
        return ZonedDateTime.now(ZoneOffset.UTC).minus(amount, unit).toInstant().toEpochMilli()
    }

    companion object {
        private const val DEFAULT_ORDER = "latest"
        private val SERIES_PATH = Regex("""^/bl/[^/]+/$""")
        private val RESIZED_REGEX = Regex("""-\d+x\d+(?=\.\w+$)""")
        private val AGO_REGEX = Regex("""^(\d+) .+ atrás$""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    }
}

internal fun resolveChapterUrl(baseUrl: String, chapter: SChapter): String {
    val rawUrl = chapter.url.trim()
    if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
    if (rawUrl.startsWith("/")) return baseUrl.trimEnd('/') + rawUrl
    val mangaPath = chapter.memo.getStringOrNull("mangaPath") ?: throw IOException("Atualize a lista de capítulos")
    return baseUrl.toHttpUrl().newBuilder().encodedPath(mangaPath).addPathSegment(rawUrl).addPathSegment("").build().toString()
}

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
            // Leave Cloudflare challenges unhandled here so WebView challenge recovery handles them cleanly.
            if (response.isCloudflareChallengeResponse()) break
            val waitMs = retryDelayMs(response, attempt)
            response.close()
            attempt++
            sleepMs(waitMs)
            if (chain.call().isCanceled()) throw IOException("Request canceled")
            response = chain.proceed(request)
        }
        return response
    }

    private fun Response.isCloudflareChallengeResponse(): Boolean {
        if (header("cf-mitigated") == "challenge") return true
        if (code != 403 && code != 503) return false
        val snippet = runCatching { peekBody(4096).string() }.getOrDefault("")
        return "_cf_chl_opt" in snippet || "Just a moment" in snippet
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

internal fun buildImageRequest(
    page: Page,
    userAgent: String?,
    siteHeaders: Map<String, String> = emptyMap(),
): Request {
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
                siteHeaders.forEach { (name, value) ->
                    if (name.isNotBlank() && '\n' !in name && '\r' !in name && '\n' !in value && '\r' !in value) {
                        add(name, value)
                    }
                }
            }.build(),
        )
        .get()
        .build()
}
