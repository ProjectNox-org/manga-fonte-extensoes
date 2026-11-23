package eu.kanade.tachiyomi.extension.pt.blitztoons

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BlitzToonsTest {

    private val baseUrl = "https://blitztoons.com"

    @Test
    fun imageRequestStrikesOriginAndSetsCdnHeaders() {
        val baseHeaders = Headers.Builder()
            .set("Origin", "https://blitztoons.com")
            .set("Referer", "https://blitztoons.com/")
            .build()

        val request = buildBlitzImageRequest(
            imageUrl = "https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp",
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        )

        assertNull("Image request must NOT contain Origin header (prevents CDN 403)", request.header("Origin"))
        assertEquals("https://blitztoons.com/", request.header("Referer"))
        assertEquals("image", request.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", request.header("Sec-Fetch-Mode"))
        assertEquals("same-site", request.header("Sec-Fetch-Site"))
        assertTrue("Accept header must request image types", request.header("Accept")?.contains("image/") == true)
        assertEquals(BLITZTOONS_USER_AGENT, request.header("User-Agent"))
    }

    @Test
    fun resolveMangaUrlHandlesObraAndLerLinks() {
        val obraUrl = "https://blitztoons.com/obra/solsticio".toHttpUrl()
        assertEquals("/obra/solsticio", resolveMangaUrl(obraUrl))

        val lerUrl = "https://blitztoons.com/ler/solsticio/57".toHttpUrl()
        assertEquals("/obra/solsticio", resolveMangaUrl(lerUrl))

        val invalidUrl = "https://blitztoons.com/ranking".toHttpUrl()
        assertNull(resolveMangaUrl(invalidUrl))
    }

    @Test
    fun filtersContainExpectedValues() {
        val orderFilter = OrderFilter()
        assertEquals("recentes", orderFilter.selected)

        val typeFilter = TypeFilter()
        assertEquals("", typeFilter.selected)

        val statusFilter = StatusFilter()
        assertEquals("", statusFilter.selected)

        val genreFilter = GenreFilter()
        assertEquals("", genreFilter.selected)
    }

    @Test
    fun parsePageListExtractsImageUrls() {
        val html = """
            <div class="chapters" data-chapters>
                <section class="ch-block" data-block>
                    <div class="pages" data-pages-wrap>
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/001.webp?v=1792353996"
                             alt="Página 1" data-page="1">
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/002.webp?v=1792353996"
                             alt="Página 2" data-page="2">
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/003.webp?v=1792353996"
                             alt="Página 3" data-page="3">
                    </div>
                </section>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://blitztoons.com/ler/solsticio/57")
        val pages = parsePageList(doc)

        assertEquals(3, pages.size)
        assertEquals(0, pages[0].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/001.webp?v=1792353996", pages[0].imageUrl)
        assertEquals(1, pages[1].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/002.webp?v=1792353996", pages[1].imageUrl)
        assertEquals(2, pages[2].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/003.webp?v=1792353996", pages[2].imageUrl)
    }

    @Test
    fun htmlSelectorsExtractExpectedValues() {
        val cardHtml = """
            <article class="card">
                <a class="card-cover" href="/obra/solsticio">
                    <img src="https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp"
                         alt="Capa de Solstício" loading="lazy">
                    <span class="card-type card-type-comic">Comic</span>
                </a>
                <div class="card-body">
                    <h3 class="card-title"><a href="/obra/solsticio">Solstício</a></h3>
                    <p class="card-meta">57 cap. · há 17 min</p>
                </div>
            </article>
        """.trimIndent()

        val cardDoc = Jsoup.parse(cardHtml, baseUrl)
        val link = cardDoc.selectFirst("a.card-cover")
        assertNotNull(link)
        assertEquals("/obra/solsticio", link!!.attr("href"))

        val title = cardDoc.selectFirst("h3.card-title a")?.text()
        assertEquals("Solstício", title)

        val img = cardDoc.selectFirst("img")
        assertEquals("https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp", img?.attr("src"))
    }

    @Test
    fun parsePageListIgnoresSvgDataPlaceholders() {
        val html = """
            <div class="reader" data-reader data-slug="a-imperatriz-se-casou-novamente" data-num="0">
                <div class="chapters" data-chapters>
                    <section class="ch-block" data-block data-chapter="38901" data-num="0"
                             data-pt="38901.tozlwi.3DF4vIOBKqZmSqDENyWd2V">
                        <div class="pages" data-pages-wrap>
                            <img class="page" src="data:image/svg+xml,%3Csvg%20xmlns=%27http://www.w3.org/2000/svg%27%20width=%271200%27%20height=%271200%27/%3E" data-page="1">
                            <img class="page" src="data:image/svg+xml,%3Csvg%20xmlns=%27http://www.w3.org/2000/svg%27%20width=%271200%27%20height=%2712000%27/%3E" data-page="2">
                        </div>
                    </section>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://blitztoons.com/ler/a-imperatriz-se-casou-novamente/0")
        val pages = parsePageList(doc)
        assertTrue("SVG placeholders alone must not become Page items", pages.isEmpty())

        val descriptor = extractPagesApiDescriptor(doc, "https://blitztoons.com/ler/a-imperatriz-se-casou-novamente/0")
        assertNotNull(descriptor)
        assertEquals("a-imperatriz-se-casou-novamente", descriptor!!.slug)
        assertEquals("0", descriptor.chapterNum)
        assertEquals("38901.tozlwi.3DF4vIOBKqZmSqDENyWd2V", descriptor.token)
        assertEquals(
            "6hFENNyK4OKdOBu5gnhAH1R4z7Nd3_-JuU7EKhmpe98",
            signBlitzPageToken(descriptor.token),
        )
        assertEquals(
            "https://blitztoons.com/api/paginas/a-imperatriz-se-casou-novamente/0?t=38901.tozlwi.3DF4vIOBKqZmSqDENyWd2V",
            buildPagesApiUrl(baseUrl, descriptor),
        )
    }

    @Test
    fun parsePagesApiResponsePreservesOrderResolvesRelativeAndDeduplicates() {
        val json = """
            {
              "paginas": [
                "https://cdn.blitztoons.com/chapters/410/0/001.webp",
                "/chapters/410/0/002.webp",
                "https://cdn.blitztoons.com/chapters/410/0/001.webp",
                "data:image/svg+xml,%3Csvg/%3E",
                {"u": "//cdn.blitztoons.com/chapters/410/0/003.webp"}
              ]
            }
        """.trimIndent()

        val pages = parsePagesApiResponse(json, baseUrl)
        assertEquals(3, pages.size)
        assertEquals("https://cdn.blitztoons.com/chapters/410/0/001.webp", pages[0].imageUrl)
        assertEquals("https://blitztoons.com/chapters/410/0/002.webp", pages[1].imageUrl)
        assertEquals("https://cdn.blitztoons.com/chapters/410/0/003.webp", pages[2].imageUrl)
    }

    @Test
    fun parsePageListSupportsLazyAttributesPictureNoscriptAndRelativeUrls() {
        val html = """
            <img class="brand-mark" src="/static/img/icone-96.png">
            <div class="chapters">
                <section class="ch-block">
                    <div class="pages">
                        <img class="page" src="data:image/svg+xml,%3Csvg/%3E" data-src="/chapters/1/1/001.webp">
                        <img class="page" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-original="//cdn.blitztoons.com/chapters/1/1/002.webp">
                        <picture>
                            <source srcset="https://cdn.blitztoons.com/chapters/1/1/003-small.webp 400w, https://cdn.blitztoons.com/chapters/1/1/003.webp 1200w">
                            <img class="page" src="data:image/svg+xml,%3Csvg/%3E">
                        </picture>
                        <img class="page" src="data:image/svg+xml,%3Csvg/%3E">
                        <noscript><img src="https://cdn.blitztoons.com/chapters/1/1/004.webp"></noscript>
                        <img class="page" data-lazy-src="https://cdn.blitztoons.com/chapters/1/1/002.webp">
                        <img class="page" src="/static/img/cover-fallback.svg">
                    </div>
                </section>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://blitztoons.com/ler/obra/1")
        val pages = parsePageList(doc)

        assertEquals(4, pages.size)
        assertEquals("https://blitztoons.com/chapters/1/1/001.webp", pages[0].imageUrl)
        assertEquals("https://cdn.blitztoons.com/chapters/1/1/002.webp", pages[1].imageUrl)
        assertEquals("https://cdn.blitztoons.com/chapters/1/1/003.webp", pages[2].imageUrl)
        assertEquals("https://cdn.blitztoons.com/chapters/1/1/004.webp", pages[3].imageUrl)
    }

    @Test
    fun realRasterDataUriIsPreservedAndServedThroughInterceptor() {
        val payload = ByteArray(96) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(payload)
        val dataUri = "data:image/png;base64,$encoded"

        val html = """
            <div class="chapters">
                <div class="pages">
                    <img class="page" src="$dataUri">
                </div>
            </div>
        """.trimIndent()

        val pages = parsePageList(Jsoup.parse(html, "https://blitztoons.com/ler/obra/1"))
        assertEquals(1, pages.size)
        assertEquals(dataUri, pages[0].imageUrl)

        val request = buildBlitzImageRequest(pages[0].imageUrl!!, baseUrl)
        assertEquals(DATA_URI_PROXY_HOST, request.url.host)

        val client = OkHttpClient.Builder()
            .addInterceptor(BlitzDataUriInterceptor())
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("image/png", response.body.contentType()?.toString())
            assertArrayEquals(payload, response.body.bytes())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildBlitzImageRequestRejectsSvgDataPlaceholder() {
        buildBlitzImageRequest("data:image/svg+xml,%3Csvg/%3E", baseUrl)
    }

    @Test
    fun extractReaderJsHmacKeyDetectsDynamicKey() {
        val snippet = """const c=r("[data-reader]");const l=c.dataset.slug,h="NewDynamicSecretKey123456";await crypto.subtle.importKey("raw",f.encode(h),{name:"HMAC",hash:"SHA-256"},!1,["sign"])"""
        assertEquals("NewDynamicSecretKey123456", extractReaderJsHmacKey(snippet))
    }
}
