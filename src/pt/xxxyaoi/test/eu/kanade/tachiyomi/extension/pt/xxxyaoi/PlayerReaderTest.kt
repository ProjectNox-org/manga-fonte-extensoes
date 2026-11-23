package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun decodesLiveXy2PrefixedLibraryConfigAndRotatedPrefixVariants() {
        val liveHtml = """
            <div class="xyaoi-cards-grid" id="xyaoi-cards-container"
                 data-xy-o_766b6487="xy2${'$'}XSI4YmQ4ZTUyMV9vIiwiMGJlMDVkMTdfbyIsOTEyNCw2Myw5LDU1LDAyLCI5ZjRmZGE3OV9vIiwiMWQ4NzE0NjBfbyIsIjdmNTRkOWYwX28iWw==">
            </div>
        """.trimIndent()
        val liveConfig = LibraryConfig.fromDocument(Jsoup.parse(liveHtml, "https://3xyaoi.com/biblioteca/"))
        assertNotNull(liveConfig)
        assertEquals("o_71d50eb0" to "o_125e8db8", liveConfig!!.cookie)
        assertEquals(
            listOf(
                "o_0f9d45f7" to "o_064178d1",
                "o_97adf4f9" to (2 * 20 + 2 * 55 + 7 * 9 + 4 * 36 + 4219).toString(),
            ),
            liveConfig.formFields(page = 2, search = "ab", status = "ongoing", genre = "yaoi"),
        )

        // Rotated container id + xy9$ prefix + renamed element
        val rotatedHtml = """
            <section class="cards-grid-v3" data-xy-custom="xy9${'$'}XSI4YmQ4ZTUyMV9vIiwiMGJlMDVkMTdfbyIsOTEyNCw2Myw5LDU1LDAyLCI5ZjRmZGE3OV9vIiwiMWQ4NzE0NjBfbyIsIjdmNTRkOWYwX28iWw=="></section>
        """.trimIndent()
        val rotatedConfig = LibraryConfig.fromDocument(Jsoup.parse(rotatedHtml, "https://3xyaoi.com/biblioteca/"))
        assertNotNull(rotatedConfig)
        assertEquals("o_71d50eb0" to "o_125e8db8", rotatedConfig!!.cookie)

        // Inline script fallback when container attribute is removed
        val scriptHtml = """
            <div id="grid-new"></div>
            <script>var _xyCfg = ["f1","v1","chk",10,20,30,40,500,"ck","cv"];</script>
        """.trimIndent()
        val scriptConfig = LibraryConfig.fromDocument(Jsoup.parse(scriptHtml, "https://3xyaoi.com/biblioteca/"))
        assertNotNull(scriptConfig)
        assertEquals("ck" to "cv", scriptConfig!!.cookie)
        assertEquals(listOf("f1" to "v1", "chk" to "510"), scriptConfig.formFields(1, "", "", ""))
    }

    @Test fun diagnosesLibraryConfigFailuresWithoutLeakingTokens() {
        assertEquals("cloudflare-challenge", LibraryConfig.diagnose(Jsoup.parse("<div id='_cf_chl_opt'></div>")))
        assertEquals("noscript-wall", LibraryConfig.diagnose(Jsoup.parse("<div id='xyaoi-noscript-wall'>Enable JS</div>")))
        assertEquals("missing-container", LibraryConfig.diagnose(Jsoup.parse("<html><body><p>Olá</p></body></html>")))
        assertEquals("config-parse-failed", LibraryConfig.diagnose(Jsoup.parse("<div id='xyaoi-cards-container' data-xy-a='broken'>Olá</div>")))
        assertEquals("empty-document", LibraryConfig.diagnose(Jsoup.parse("<html><body></body></html>")))
    }

    @Test fun libraryResponseRejectsSuccessFalseErrorPayloads() {
        val ok = """{"success":true,"data":{"html":"<div>ok</div>","current_page":1,"total_pages":3}}""".parseAs<LibraryResponse>()
        assertNotNull(ok.toLibraryData())
        assertEquals(3, ok.toLibraryData()!!.totalPages)

        val errString = """{"success":false,"data":"Token expirado"}""".parseAs<LibraryResponse>()
        assertNull(errString.toLibraryData())
    }

    @Test fun chapterRowAndReaderDataHandlePrefixesRenamedLoopVarsAndHexShifts() {
        val prefixedAttr = "xy2${'$'}XSI1MyBvbHV0ZGUwMHVccGFDIiwiNTMtb2x1dGlwYWMiLCI3Mjk1NSJb"
        val prefixedHref = "xy2${'$'}LzUzLW9sdXRpcGFjLzAwLXJhZWQvbGIvbW9jLmlvYXl4My8vOnNwdHRo"
        val li = Jsoup.parse(
            """<ul><li data-x="$prefixedAttr"><a href="#c" data-h="$prefixedHref">Cap 35</a></li></ul>""",
            "https://3xyaoi.com/bl/dear-00/",
        ).selectFirst("li")!!
        val row = ChapterRow.fromElement(li)!!
        assertEquals("capitulo-35", row.slug)
        assertEquals("https://3xyaoi.com/bl/dear-00/capitulo-35/", ChapterRow.decodedHref(li.selectFirst("a")!!))

        val key = "bodinho_protect_xyz"
        val pages = listOf("https://3xyaoi.com/wp-content/uploads/01.jpg", "https://3xyaoi.com/wp-content/uploads/02.jpg")
        val encoded = (listOf("https://3xyaoi.com/wp-content/uploads/warning_app.jpg") + pages).map { url ->
            val plain = url.toByteArray(Charsets.UTF_8)
            val transformed = ByteArray(plain.size) { idx ->
                val xored = (plain[idx].toInt() and 0xFF) xor key[idx % key.length].code xor 0x3A
                ((xored + 0x11 + (idx % 7)) and 0xFF).toByte()
            }.reversedArray()
            Base64.getEncoder().encodeToString(transformed).trimEnd('=')
        }
        val json = """{"k":"$key","p":[${encoded.joinToString(",") { "\"$it\"" }}],"o":1}"""
        val prefixedReader = "xy2${'$'}" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).reversed()
        val html = """
            <script type="text/x-xy-data">$prefixedReader</script>
            <script>
              var _key = _dat["k"];
              var shiftVal = 0x11;
              var _valid = _dat["p"].slice(_dat["o"]);
              var _b = (_raw.codePointAt(idx) + 256 - shiftVal - (idx % 7)) % 256;
              _u += String.fromCharCode(_b ^ (0x3A ^ _key.codePointAt(idx % _key.length)));
            </script>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-1/")
        assertEquals(pages, Reader.extract(doc))
    }

    @Test fun lazyPlayerIframeDataSrcAndMixedAttrsWork() {
        val chapterHtml = """<div class="reading-content"><iframe src="about:blank" data-src="/wp-content/plugins/3xyaoi-leitor/player.php?token=t9"></iframe></div>"""
        val chapterDoc = Jsoup.parse(chapterHtml, "https://3xyaoi.com/bl/work/capitulo-1/")
        assertEquals(
            "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=t9",
            Reader.playerIframeUrl(chapterDoc),
        )
        val playerHtml = """
            <div class="reader-container">
              <div class="page-wrapper" data-index="1"><img data-vhash="${VhashReaderTest.encodeVhash("https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=p2")}"></div>
              <div class="page-wrapper" data-index="0"><img srcset="/wp-content/plugins/3xyaoi-leitor/img.php?t=p1 1x"></div>
            </div>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=p1",
                "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/img.php?t=p2",
            ),
            Reader.extractPlayerPages(Jsoup.parse(playerHtml, "https://3xyaoi.com/wp-content/plugins/3xyaoi-leitor/player.php?token=t9")),
        )
    }

    @Test fun webViewOriginHeaderRulesAndJvmSafety() {
        assertEquals(
            setOf("https://3xyaoi.com", "https://*.3xyaoi.com"),
            WebViewOriginHeader.originRulesFor("https://3xyaoi.com/biblioteca/"),
        )
        assertTrue(WebViewOriginHeader.originRulesFor("").isEmpty())
        assertTrue(WebViewOriginHeader.originRulesFor("ftp://3xyaoi.com").isEmpty())
        // Must be safe to call during unit tests without Android Looper/Application.
        WebViewOriginHeader.ensure("https://3xyaoi.com")
    }

    companion object {
        @JvmStatic @BeforeClass fun setup() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
