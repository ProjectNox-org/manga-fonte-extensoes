package eu.kanade.tachiyomi.multisrc.aurora

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.addCookie
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Aurora : KeiSource() {

    /** Turnstile site key the reader gate renders when the app answers `captcha_required`; null when the site has none. */
    protected open val captchaSiteKey: String? = null

    /** Turnstile action the widget declares; Cloudflare rejects a token minted for a different action. */
    protected open val captchaAction: String? = null

    /** Countdown used when the gate declares nothing usable: the site's own default unless a source measured it. */
    protected open val gateFallbackSeconds: Int = 15

    /** Set false when a source sees `method` partner variants declare `0` but still refuse an early callback. */
    protected open val gateTrustsPartnerZero: Boolean = true

    // Lazy so a subclass can assign both properties during its own construction.
    private val captchaSolver: (suspend (String) -> String)? by lazy {
        captchaSiteKey?.let { siteKey ->
            { url: String -> getTurnstileToken(url, siteKey = siteKey, action = captchaAction) }
        }
    }

    protected fun OkHttpClient.Builder.defaultClient(): OkHttpClient.Builder = rateLimit(
        permits = 3,
        period = 1.seconds,
        shouldLimit = { url ->
            !url.host.contains("snipercache") &&
                !url.encodedPath.endsWith(".webp") &&
                !url.encodedPath.endsWith(".jpg") &&
                !url.encodedPath.endsWith(".png")
        },
    ).addCookie("mnx_adulto" to "1")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = defaultClient()

    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).build() }

    private val readerCoordinator by lazy {
        AuroraReaderCoordinator(
            sourceNameProvider = { name },
            clientProvider = { client },
            noRedirectClientProvider = { noRedirectClient },
            baseHeadersProvider = { headers },
            baseUrlProvider = { baseUrl },
            gateTiming = GateTiming(gateFallbackSeconds, gateTrustsPartnerZero),
            captchaSolver = captchaSolver,
        )
    }

    override fun getImageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!.toHttpUrl()
        val imageHeaders = headersBuilder().apply {
            if (imageUrl.host != baseUrl.toHttpUrl().host) {
                removeAll("Alt-Used")
            }
        }.build()
        return Request.Builder()
            .url(imageUrl)
            .headers(imageHeaders)
            .fetch(Fetch.IMAGE.from("$baseUrl/"))
            .build()
    }

    override fun Headers.Builder.configureHeaders() = set("Alt-Used", baseUrl.toHttpUrl().host)

    override suspend fun getPopularManga(page: Int) = getMangasPage(client.get("$baseUrl/catalogo", Fetch.DOCUMENT))

    override suspend fun getLatestUpdates(page: Int) = getMangasPage(client.get("$baseUrl/novidades", Fetch.DOCUMENT))

    private fun getMangasPage(response: Response): MangasPage {
        val dto = response.extractNextJs<SeriesDto>()
        return MangasPage(dto?.toSMangaList() ?: emptyList(), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangas = getPopularManga(page).mangas.filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter(String::isNotEmpty)
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.") || segments.size < 2) {
            return null
        }
        val mangaUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(segments[0])
            .addPathSegment(segments[1])
            .build()
        return client.get(mangaUrl, Fetch.DOCUMENT, ensureSuccess = false).use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw HttpException(response.code)
                else -> response.asJsoup().seriesDto()?.toSManga()?.apply { initialized = true }
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = entryURL(manga.memo)

    override fun getChapterUrl(chapter: SChapter): String {
        val number = chapter.memo["number"]?.string ?: chapter.chapter_number.toString().removeSuffix(".0")
        return "${entryURL(chapter.memo)}/$number"
    }

    private fun entryURL(memo: JsonObject): String = "$baseUrl/${memo["type"]!!.string}/${memo["slug"]!!.string}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()

        val updatedManga = getSMangaDetails(document, manga)
        val updatedChapters = document.extractNextJs<ChapterListDto>()
            ?.toSChapterList(updatedManga)
            ?: emptyList()

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private val mangaDetailsDescriptionRegex = """description":"([^"]+)""".toRegex()
    private val mangaDetailsGenreRegex = """genre":([^]]+])""".toRegex()
    private val mangaDetailsAuthorRegex = """author[^.]+name":"([^"]+)""".toRegex()
    private val cleanRegex = """\\{2,}""".toRegex()

    private fun getSMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga = document.seriesDto()
        ?.toSManga()
        ?: manga.apply {
            document.selectFirst("script:containsData(ComicSeries)")?.data()
                ?.replace(cleanRegex, "")
                ?.let {
                    description = MarkdownDescription.Builder()
                        .description(mangaDetailsDescriptionRegex.find(it)?.groupValues?.last())
                        .build()
                    genre = mangaDetailsGenreRegex.find(it)?.groupValues?.last()?.parseAs<List<String>>()?.joinToString()
                    author = mangaDetailsAuthorRegex.find(it)?.groupValues?.last()
                }
        }

    private fun Document.seriesDto() = selectFirst("script[type]:containsData(ComicSeries)")?.data()?.parseAs<MangaDto>()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val entryUrl = entryURL(chapter.memo)
        return readerCoordinator.getPageUrls(chapterUrl, entryUrl).mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    internal suspend fun getKey(params: Pair<Int, Long>, chapterUrl: String): String = readerCoordinator.getKey(params, chapterUrl)
}
