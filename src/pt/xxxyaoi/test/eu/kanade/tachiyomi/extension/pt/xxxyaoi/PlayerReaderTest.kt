package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Base64

class PlayerReaderTest {

    @Test fun decodesLiveLibraryConfigAndChecksum() {
        val html = """
            <div id="xyaoi-cards-container" data-xy-q_8013b3e9="XSIwYmEwNGFkZV9xIiwiZTg2M2MyOGJfcSIsNDE0MSw3LDgzLDIzLDEzLCI2MTAyMDYxNl9xIiwiOTIzZjc2MWFfcSIsIjE4MmNjMDI4X3EiWw=="></div>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/biblioteca/")
        val config = LibraryConfig.fromContainer(doc.selectFirst("#xyaoi-cards-container"))!!

        assertEquals("q_b82c368e" to "q_eda40ab0", config.cookie)
        assertEquals(
            listOf(
                "q_820cc281" to "q_a167f329",
                "q_61602016" to (2 * 31 + 2 * 32 + 7 * 38 + 4 * 7 + 1414).toString(),
            ),
            config.formFields(page = 2, search = "ab", status = "ongoing", genre = "yaoi"),
        )
    }

    @Test fun decodesLiveChapterRowWithHashedDataQAttributes() {
        val html = """
            <ul class="version-chap">
              <li class="q_370485dd" data-q_c634cdbb="XSI1MyBvbHV0ZGUwMHVccGFDIiwiNTMtb2x1dGlwYWMiLCI3Mjk1NSJb">
                <a href="#cap" data-q_6716592f="LzUzLW9sdXRpcGFjLzAwLXJhZWQvbGIvbW9jLmlvYXl4My8vOnNwdHRo" class="xyaoi-chapter-row">
                  <span class="q_707904ea">Capítulo 35</span>
                </a>
              </li>
            </ul>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/dear-00/")
        val li = doc.selectFirst("ul.version-chap > li")!!
        val row = ChapterRow.fromElement(li)!!
        val href = ChapterRow.decodedHref(li.selectFirst("a")!!)

        assertEquals("capitulo-35", row.slug)
        assertEquals("Capítulo 35", row.name)
        assertEquals("https://3xyaoi.com/bl/dear-00/capitulo-35/", href)
    }

    @Test fun detectsSameOriginPlayerIframeAndRejectsCrossOrigin() {
        val chapterHtml = """
            <div class="reading-content">
              <iframe src="https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=abc123"></iframe>
            </div>
        """.trimIndent()
        val chapterDoc = Jsoup.parse(chapterHtml, "https://3xyaoi.com/bl/dear-00/capitulo-35/")
        assertEquals(
            "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=abc123",
            Reader.playerIframeUrl(chapterDoc),
        )

        val evilHtml = """<div class="reading-content"><iframe src="https://evil.example/player.php?token=abc"></iframe></div>"""
        assertNull(Reader.playerIframeUrl(Jsoup.parse(evilHtml, "https://3xyaoi.com/bl/dear-00/capitulo-35/")))
    }

    @Test fun extractsOrderedImgPhpUrlsFromPlayerDocumentAndRejectsWarning() {
        val playerHtml = """
            <div class="reader-container">
              <div class="page-wrapper" data-index="2">
                <img src="/wp-content/plugins/3xyaoi-leitor/img.php?t=tok3" class="reader-img" alt="Página 3">
              </div>
              <div class="page-wrapper" data-index="0">
                <img src="/wp-content/plugins/3xyaoi-leitor/img.php?t=tok1" class="reader-img" alt="Página 1">
              </div>
              <div class="page-wrapper" data-index="1">
                <img src="/wp-content/plugins/3xyaoi-leitor/img.php?t=tok2" class="reader-img" alt="Página 2">
              </div>
              <div class="page-wrapper" data-index="3">
                <img src="data:image/svg+xml,placeholder" class="reader-img">
              </div>
            </div>
        """.trimIndent()
        val playerDoc = Jsoup.parse(playerHtml, "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=abc")
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=tok1",
                "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=tok2",
                "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=tok3",
            ),
            Reader.extractPlayerPages(playerDoc),
        )

        val blockedHtml = """
            <div class="reader-container">
              <div class="page-wrapper" data-index="0">
                <img src="https://3xyaoi.com/wp-content/uploads/warning_app.jpg" class="reader-img">
              </div>
            </div>
        """.trimIndent()
        assertThrows(Reader.BrowserRestricted::class.java) {
            Reader.extractPlayerPages(Jsoup.parse(blockedHtml, "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=abc"))
        }
    }

    @Test fun decodesApplicationJsonReaderDataFallback() {
        val key = "secretKey"
        val pages = listOf("https://3xyaoi.com/wp-content/uploads/01.webp", "https://3xyaoi.com/wp-content/uploads/02.webp")
        val decoy = "https://3xyaoi.com/decoy.webp"
        val encoded = (listOf(decoy) + pages).map { url ->
            val plain = url.toByteArray(Charsets.UTF_8)
            val transformed = ByteArray(plain.size) { i ->
                (((plain[i].toInt() and 0xFF) xor key[i % key.length].code) + 7 + (i % 5)).toByte()
            }.reversedArray()
            Base64.getEncoder().encodeToString(transformed)
        }
        val jsonPayload = encoded.joinToString(",", "[", "]") { "\"$it\"" }
        val html = """
            <script type="application/json">{"k":"$key","o":1,"p":$jsonPayload}</script>
            <script>
              var s = 7;
              var _c = (_raw.charCodeAt(i) - s - (i % 5)) & 255;
            </script>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-1/")
        assertEquals(pages, Reader.extract(doc))
    }

    @Test fun decodesReversedBase64XYDataReaderV53AndIgnoresRc4Decoy() {
        val key = "bodinho_protect_e80812e291481822"
        val pages = listOf(
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch_1/0.1.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch_1/CRÉDITO-FINAL.png",
        )
        val fakes = listOf("https://3xyaoi.com/wp-content/uploads/warning_app.jpg", "https://http.cat/403.jpg")
        val encodedEntries = (fakes + pages).map { url ->
            val plain = url.toByteArray(Charsets.UTF_8)
            val transformed = ByteArray(plain.size) { i ->
                val xored = (plain[i].toInt() and 0xFF) xor key[i % key.length].code xor 58
                ((xored + 3 + (i % 7)) and 0xFF).toByte()
            }.reversedArray()
            Base64.getEncoder().encodeToString(transformed).trimEnd('=')
        }
        val json = """{"m_v":"2","n_d":9,"a_d":["decoy"],"q_8260ec76":"$key","q_b04f4092":[${encodedEntries.joinToString(",") { "\"$it\"" }}],"q_93b8745b":2}"""
        val reversedJsonB64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).reversed()
        val html = """
            <script type="text/template" id="v_157ab142d6">BVBRozz~invalidDecoy</script>
            <script type="text/x-xy-data" id="q_94ed430d_502_q_5895b9bf">$reversedJsonB64</script>
            <script>
              var secKey = String.fromCharCode(125 - 27);
              var vaultId = "v_157ab142d6";
              function _rc4(k, b) { return b; }
              var _key = _dat["q_8260ec76"];
              var q_e069748c = 3;
              var _validPayload = _dat["q_b04f4092"].slice(_dat["q_93b8745b"]);
              var _b = (_raw.codePointAt(i) + 256 - q_e069748c - (i % 7)) % 256;
              _u += String.fromCharCode(_b ^ _key.charCodeAt(i % _key.length) ^ 58);
            </script>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/salt-society/capitulo-1/")
        assertEquals(pages, ReaderData.from(doc)!!.toPageUrls())
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch_1/0.1.jpg",
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch_1/CR%C3%89DITO-FINAL.png",
            ),
            Reader.extract(doc),
        )
    }

    @Test fun sortAndStatusFiltersMatchLiveSiteValues() {
        val sort = SortFilter()
        assertEquals("latest", sort.value)
        sort.state = 1
        assertEquals("new-manga", sort.value)
        sort.state = 2
        assertEquals("alphabet", sort.value)

        val status = StatusFilter()
        assertEquals("", status.value)
        status.state = 1
        assertEquals("on-going", status.value)
        status.state = 2
        assertEquals("completed", status.value)
        status.state = 3
        assertEquals("xxx", status.value)
        status.state = 4
        assertEquals("animacoes", status.value)
    }

    companion object {
        @JvmStatic @BeforeClass fun setup() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
