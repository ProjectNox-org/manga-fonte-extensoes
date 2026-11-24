package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import com.sun.net.httpserver.HttpServer
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SessionAndMutationTest {

    private fun buildV3Payload(
        json: String,
        prefix: String = "v3~",
        reverseBeforeBase64: Boolean = true,
    ): String {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val body = if (reverseBeforeBase64) encoded.reversed() else String(Base64.getEncoder().encode(json.reversed().toByteArray(Charsets.UTF_8)))
        return prefix + body
    }

    @Test fun liveV3JsonObjectAndPassiveJsdDoNotTriggerFalseCloudflareDiagnosis() {
        val json = """{"f0":"l_11111111","f1":"l_22222222","f2":"l_33333333","n3":34,"n4":63,"n5":9,"n6":22,"n7":2896,"c8":"l_ckname01","c9":"l_ckval002","extra":"v9"}"""
        val v3Attr = buildV3Payload(json, prefix = "v3~", reverseBeforeBase64 = true)
        val html = """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <body class="wp-singular page-template-page-biblioteca">
              <h1>Biblioteca 3X Yaoi</h1>
              <div class="xyaoi-cards-grid" id="xyaoi-cards-container" data-xy-l_46de1d4a="$v3Attr"></div>
              <script>
                let _xyCfg = null;
                const _o = JSON.parse(atob(rk.slice(3).split('').reverse().join('')));
                _xyCfg = [_o.f0, _o.f1, _o.f2, _o.n3, _o.n4, _o.n5, _o.n6, _o.n7, _o.c8, _o.c9];
                formData.append(['ac','tion'].join(''), ['xyaoi','library','filter'].join('_'));
              </script>
              <script>
                var u='/cdn-cgi/challenge-platform/scripts/jsd/main.js?ray=abc123';
              </script>
            </body>
            </html>
        """.trimIndent()

        // Passive jsd/main.js on a valid 200 site page must NEVER be flagged as a challenge.
        assertEquals(ChallengeClassification.CLEAN, CloudflareClassifier.classifyHtml(200, html))

        val doc = Jsoup.parse(html, "https://3xyaoi.com/biblioteca/")
        val config = LibraryConfig.fromDocument(doc)
        assertNotNull(config)
        assertEquals("l_ckname01" to "l_ckval002", config!!.cookie)
        assertEquals(
            listOf(
                "l_11111111" to "l_22222222",
                "l_33333333" to (2 * 34 + 3 * 63 + 4 * 9 + 5 * 22 + 2896).toString(),
            ),
            config.formFields(page = 2, search = "abc", status = "xxx1", genre = "yaoii"),
        )

        // Broken payload on a real page with passive jsd/main.js must report structural failure, NOT cloudflare-challenge.
        val brokenRealPage = html.replace(v3Attr, "v99~broken_payload")
        assertEquals("config-parse-failed", LibraryConfig.diagnose(Jsoup.parse(brokenRealPage, "https://3xyaoi.com/biblioteca/")))
    }

    @Test fun structuralMutationsOnLibraryConfigSelectorsPrefixesAndKeyNames() {
        val mutatedJson = """
            {
              "meta": {"ver": 4},
              "ckV": "ck_val",
              "pF": 11,
              "sF": 12,
              "stF": 13,
              "gF": 14,
              "base": 1000,
              "tokF": "fld_tok",
              "tokV": "val_tok",
              "chkF": "fld_chk",
              "ckN": "ck_name"
            }
        """.trimIndent()

        for (prefix in listOf("v7:", "cfg|", "xy12#", "b4!")) {
            val encoded = buildV3Payload(mutatedJson, prefix = prefix, reverseBeforeBase64 = true)
            val mutatedHtml = """
                <section id="renamed-grid-2027" class="new-wrapper-x9" data-payload-token="$encoded">
                  <div class="inner-slot"></div>
                </section>
                <script>
                  var _xyCfg = [_d.tokF, _d.tokV, _d.chkF, _d.pF, _d.sF, _d.stF, _d.gF, _d.base, _d.ckN, _d.ckV];
                </script>
            """.trimIndent()
            val cfg = LibraryConfig.fromDocument(Jsoup.parse(mutatedHtml, "https://3xyaoi.com/biblioteca/"))
            assertNotNull("Failed for prefix $prefix", cfg)
            assertEquals("ck_name" to "ck_val", cfg!!.cookie)
            assertEquals(
                listOf("fld_tok" to "val_tok", "fld_chk" to (1 * 11 + 2 * 12 + 3 * 13 + 4 * 14 + 1000).toString()),
                cfg.formFields(1, "hi", "abc", "yaoi"),
            )
        }

        // Auxiliary page without real library config must still be rejected cleanly instead of inventing values.
        val incompleteHtml = "<html><body><header>Menu</header><div class='aux-widget' data-info='hello'>Conteúdo auxiliar</div></body></html>"
        val parsed = LibraryConfig.fromDocument(Jsoup.parse(incompleteHtml, "https://3xyaoi.com/biblioteca/"))
        assertNull(parsed)
        assertEquals("missing-container", LibraryConfig.diagnose(Jsoup.parse(incompleteHtml)))
    }

    @Test fun sessionManagerRecovers403503200ChallengesAndRedirectsSingleFlight() {
        val activeCookie = AtomicReference<String?>(null)
        val mode = AtomicReference("challenge-403")
        val solveCalls = AtomicInteger(0)
        val clock = AtomicLong(1_000L)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/biblioteca/") { ex ->
            val cookieHeader = ex.requestHeaders.getFirst("Cookie").orEmpty()
            val hasValid = cookieHeader.contains("cf_clearance=fresh_ok")
            when {
                hasValid -> {
                    val body = """<div id="xyaoi-cards-container" data-xy="ok">Valid Library</div>""".toByteArray()
                    ex.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                mode.get() == "challenge-403" -> {
                    val body = """<html><head><title>Just a moment...</title></head><body>_cf_chl_opt</body></html>""".toByteArray()
                    ex.responseHeaders.set("Content-Type", "text/html")
                    ex.responseHeaders.set("cf-mitigated", "challenge")
                    ex.sendResponseHeaders(403, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                mode.get() == "challenge-503" -> {
                    val body = """<html><body><div class="cf-turnstile"></div>/cdn-cgi/challenge-platform/h/b/</body></html>""".toByteArray()
                    ex.responseHeaders.set("Content-Type", "text/html")
                    ex.sendResponseHeaders(503, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                mode.get() == "challenge-200" -> {
                    val body = """<html><head><title>Just a moment...</title></head><body><form id="challenge-form"></form></body></html>""".toByteArray()
                    ex.responseHeaders.set("Content-Type", "text/html")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                mode.get() == "redirect-302" -> {
                    ex.responseHeaders.set("Location", "/cdn-cgi/challenge-platform/orchestrate/ch/v1")
                    ex.sendResponseHeaders(302, -1)
                    ex.responseBody.close()
                }
            }
        }
        server.start()

        val manager = SessionManager(
            solver = ChallengeSolver { _, _, _ ->
                solveCalls.incrementAndGet()
                Thread.sleep(60)
                activeCookie.set("fresh_ok")
                true
            },
            cooldownMs = 5_000L,
            clockMs = { clock.get() },
        )

        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val v = activeCookie.get() ?: return emptyList()
                    return listOf(Cookie.Builder().name("cf_clearance").value(v).hostOnlyDomain(url.host).build())
                }
            })
            .addInterceptor(ChallengeInterceptor(manager))
            .build()

        val base = "http://127.0.0.1:${server.address.port}"
        try {
            // Test all challenge vectors (missing cookie -> 403, expired cookie -> 503, HTTP 200 challenge, 302 redirect).
            for (scenario in listOf("challenge-403", "challenge-503", "challenge-200", "redirect-302")) {
                activeCookie.set(if (scenario == "challenge-503") "expired_val" else null)
                mode.set(scenario)
                val beforeSolves = solveCalls.get()
                client.newCall(Request.Builder().url("$base/biblioteca/").build()).execute().use { res ->
                    assertEquals(200, res.code)
                    assertTrue(res.body.string().contains("Valid Library"))
                }
                assertEquals(beforeSolves + 1, solveCalls.get())
                assertEquals(SessionState.VALID, manager.state)
            }

            // Concurrency test: 8 threads hit 403 simultaneously -> solver runs ONCE, all 8 succeed!
            activeCookie.set(null)
            mode.set("challenge-403")
            val beforeConcurrentSolves = solveCalls.get()
            val pool = Executors.newFixedThreadPool(8)
            try {
                val results = pool.invokeAll(
                    List(8) {
                        Callable {
                            client.newCall(Request.Builder().url("$base/biblioteca/").build()).execute().use { res ->
                                res.code to res.body.string()
                            }
                        }
                    },
                ).map { it.get() }
                assertTrue(results.all { it.first == 200 && it.second.contains("Valid Library") })
                assertEquals(beforeConcurrentSolves + 1, solveCalls.get())
            } finally {
                pool.shutdownNow()
            }
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun persistentBlockTransitionsToStateEStopsLoopsAndRespectsCooldown() {
        val solveCalls = AtomicInteger(0)
        val clock = AtomicLong(10_000L)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/biblioteca/") { ex ->
            val body = """<html><head><title>Attention Required! | Cloudflare</title></head><body>Sorry, you have been blocked</body></html>""".toByteArray()
            ex.responseHeaders.set("Content-Type", "text/html")
            ex.sendResponseHeaders(403, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()

        val manager = SessionManager(
            solver = ChallengeSolver { _, _, _ ->
                solveCalls.incrementAndGet()
                true // Solver ran once, but site still returned hard WAF block on replay.
            },
            cooldownMs = 5_000L,
            clockMs = { clock.get() },
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(ChallengeInterceptor(manager))
            .build()

        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val pool = Executors.newFixedThreadPool(4)
            try {
                val exceptions = pool.invokeAll(
                    List(4) {
                        Callable {
                            try {
                                client.newCall(Request.Builder().url("$base/biblioteca/").build()).execute().close()
                                null
                            } catch (e: CloudflareSessionException) {
                                e
                            }
                        }
                    },
                ).mapNotNull { it.get() }
                assertEquals(4, exceptions.size)
                assertTrue(exceptions.all { it.state == SessionState.PERSISTENT_BLOCK && it.diagnosis == "waf-blocked" })
                assertEquals(1, solveCalls.get())
            } finally {
                pool.shutdownNow()
            }

            // Within cooldown window, subsequent attempt fails fast without opening another WebView.
            assertThrows(CloudflareSessionException::class.java) {
                client.newCall(Request.Builder().url("$base/biblioteca/").build()).execute().close()
            }
            assertEquals(1, solveCalls.get())

            // After cooldown elapses, recovery may be attempted cleanly again.
            clock.addAndGet(6_000L)
            assertThrows(CloudflareSessionException::class.java) {
                client.newCall(Request.Builder().url("$base/biblioteca/").build()).execute().close()
            }
            assertEquals(2, solveCalls.get())
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun chapterRowAndLayoutSurviveMutatedClassesTagsAndV3Prefix() {
        val rawRow = """[12345,"capitulo-99","Capítulo 99"]"""
        val v3Row = "v3~" + Base64.getEncoder().encodeToString(rawRow.toByteArray(Charsets.UTF_8)).reversed()
        val v3Href = "v3~" + Base64.getEncoder().encodeToString("https://3xyaoi.com/bl/work/capitulo-99/".toByteArray(Charsets.UTF_8)).reversed()
        val rowDoc = Jsoup.parse(
            """<ul class="version-chap"><li class="x_rnd1" data-row="$v3Row"><a href="#x" data-lnk="$v3Href"><span class="rnd_c">Capítulo 99</span></a></li></ul>""",
            "https://3xyaoi.com/bl/work/",
        )
        val li = rowDoc.selectFirst("ul.version-chap > li")!!
        val row = ChapterRow.fromElement(li)!!
        assertEquals("capitulo-99", row.slug)
        assertEquals("Capítulo 99", row.name)
        assertEquals("https://3xyaoi.com/bl/work/capitulo-99/", ChapterRow.decodedHref(li.selectFirst("a")!!))

        // Mutated genre item with randomized span class and randomized count span class.
        val genreHtml = """
            <div class="xyaoi-genre-item" data-genre="fantasia">
              <span class="l_8958bbeb">Fantasia</span>
              <span class="c_99aa11bb">(412)</span>
            </div>
        """.trimIndent()
        val el = Jsoup.parse(genreHtml).selectFirst(".xyaoi-genre-item[data-genre]")!!
        val label = el.children().firstOrNull { !Regex("""^\(\d+\)$""").matches(it.text().trim()) }?.text()?.trim()
        assertEquals("Fantasia", label)

        // Ensure LibraryResponse with additional server keys still parses cleanly.
        val resp = """{"success":true,"data":{"html":"<div></div>","current_page":2,"total_pages":10,"total_posts":160,"debug":"ignored"}}""".parseAs<LibraryResponse>()
        val data = resp.toLibraryData()!!
        assertEquals(2, data.currentPage)
        assertEquals(10, data.totalPages)
    }

    companion object {
        @JvmStatic @BeforeClass fun setup() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
