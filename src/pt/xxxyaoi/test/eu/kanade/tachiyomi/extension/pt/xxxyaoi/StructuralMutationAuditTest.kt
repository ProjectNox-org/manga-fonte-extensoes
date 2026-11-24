package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class StructuralMutationAuditTest {

    private fun encodeLiveEntry(url: String, key: String, shiftDelta: Int, mod: Int, modSign: Int, xorMask: Int): String {
        val utf8 = url.toByteArray(Charsets.UTF_8)
        val raw = ByteArray(utf8.size) { i ->
            val xored = (utf8[i].toInt() and 0xFF) xor key[i % key.length].code xor xorMask
            ((xored - shiftDelta - modSign * (i % mod) + 512) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(raw.reversedArray())
    }

    @Test
    fun `tolerates renamed script array var, custom reordered keys, decoy fields, and varied prefixes in LibraryConfig`() {
        val payloadObj = buildJsonObject {
            put("extraNoise", "ignored_value")
            put("k_token_name", "nonce_xy")
            put("extraNum", 999)
            put("k_cookie_val", "cookieV")
            put("k_page", 3)
            put("k_search", 5)
            put("k_base", 100)
            put("k_cookie_key", "cookieK")
            put("k_status", 7)
            put("k_genre", 11)
            put("k_checksum_name", "chk_xy")
            put("k_token_val", "secretTok99")
        }
        val jsonText = payloadObj.toString()
        val prefixes = listOf("v4~", "v5|", "")
        prefixes.forEach { prefix ->
            val encoded = prefix + Base64.getEncoder().encodeToString(jsonText.toByteArray(Charsets.UTF_8)).reversed()
            val html = """
                <html><body>
                  <section id="custom-wrapper" data-noise="abc" data-alt-cfg="   $encoded   "></section>
                  <script>
                    (function(){
                      const cfg = JSON.parse("...");
                      const customBibMap = [
                        cfg.k_token_name, cfg.k_token_val, cfg.k_checksum_name,
                        cfg.k_page, cfg.k_search, cfg.k_status, cfg.k_genre, cfg.k_base,
                        cfg.k_cookie_key, cfg.k_cookie_val
                      ];
                    })();
                  </script>
                </body></html>
            """.trimIndent()
            val doc = Jsoup.parse(html, "https://3xyaoi.com/biblioteca/")
            val cfg = LibraryConfig.fromDocument(doc)
            assertNotNull("Config should parse with prefix=$prefix and renamed array var", cfg)
            assertEquals("cookieK" to "cookieV", cfg!!.cookie)
            val fields = cfg.formFields(page = 2, search = "ab", status = "s", genre = "gg")
            // 2*3 + 2*5 + 1*7 + 2*11 + 100 = 6 + 10 + 7 + 22 + 100 = 145
            assertEquals(listOf("nonce_xy" to "secretTok99", "chk_xy" to "145"), fields)
        }
    }

    @Test
    fun `tolerates dot notation inline key lookup comma declarations and literal slice in ReaderData`() = runBlocking {
        val key = "dot_inline_key_2026"
        val entries = listOf(
            "https://3xyaoi.com/wp-content/uploads/warning_app.jpg",
            "https://http.cat/403.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/dot/01.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/dot/02.jpg",
        ).map { encodeLiveEntry(it, key, shiftDelta = 9, mod = 5, modSign = -1, xorMask = 0x2a) }

        val json = """{"extra":"decoy","k":"$key","list":[${entries.joinToString(",") { "\"$it\"" }}]}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).reversed()
            .chunked(24).joinToString("\n  ")

        val html = """
            <html><body>
              <script type="text/plain" id="p_block">
                $encoded
              </script>
              <script>
                (function(){
                  var dummy = 1, shiftVal = 9, maskVal = 0x2a;
                  var el = document.getElementById("p_block");
                  var dat = JSON.parse(atob(el.textContent.trim().split('').reverse().join('')));
                  var valid = dat.list.slice(2);
                  valid.forEach(function(e){
                    var raw = atob(e).split('').reverse().join('');
                    for (var i = 0; i < raw.length; i++) {
                      var b = (raw.codePointAt(i) + shiftVal - (i % 5) + 256) % 256;
                      String.fromCharCode(b ^ dat.k.charCodeAt(i % dat.k.length) ^ maskVal);
                    }
                  });
                })();
              </script>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-1/")
        val pages = Reader.load(doc) { null }
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/dot/01.jpg",
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/dot/02.jpg",
            ),
            pages,
        )
    }

    @Test
    fun `fails cleanly with PagesNotFound on corrupted ReaderData without leaking decoy URLs`() {
        val html = """
            <html><body>
              <script type="text/plain" id="broken_p">NOT_VALID_JSON_OR_BASE64</script>
              <script>
                var s = 8;
                var dat = JSON.parse(atob(document.getElementById("broken_p").textContent.trim().split('').reverse().join('')));
                for (var i=0;i<1;i++){ var b = (raw.codePointAt(i) + s + (i % 7)) % 256; String.fromCharCode(b ^ k.charCodeAt(i)); }
              </script>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-1/")
        assertThrows(Reader.PagesNotFound::class.java) {
            runBlocking { Reader.load(doc) { null } }
        }
    }

    @Test
    fun `tolerates variable salt and base64 whitespace in AesGcmReader`() = runBlocking {
        val token = "tok_var_salt_77"
        val salt = "xy_var_salt_99"
        val iv = ByteArray(12) { (it + 3).toByte() }
        val plain = """["https://3xyaoi.com/wp-content/uploads/aes-1.webp","https://3xyaoi.com/wp-content/uploads/aes-2.webp"]"""
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(token.toCharArray(), salt.toByteArray(Charsets.UTF_8), 1000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ct = Base64.getEncoder().encodeToString(out.copyOfRange(0, out.size - 16)).chunked(12).joinToString(" ")
        val tag = Base64.getEncoder().encodeToString(out.copyOfRange(out.size - 16, out.size))
        val ivB64 = Base64.getEncoder().encodeToString(iv)

        val html = """
            <html><body>
              <div class="xy-aes-box" data-ct="$ct" data-iv="$ivB64" data-tg="$tag" data-tk="$token"></div>
              <script>
                (function(){
                  var node = document.querySelector(".xy-aes-box");
                  var mySalt = "$salt";
                  crypto.subtle.deriveKey(
                    { name: "PBKDF2", salt: new TextEncoder().encode(mySalt), iterations: 1000, hash: "SHA-256" },
                    null, { name: "AES-GCM", length: 256 }, false, ["decrypt"]
                  );
                  openBox(node.dataset.ct, node.dataset.iv, node.dataset.tg, node.dataset.tk);
                  crypto.subtle.decrypt({ name: "AES-GCM", iv: iv }, k, data);
                })();
              </script>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-2/")
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/uploads/aes-1.webp",
                "https://3xyaoi.com/wp-content/uploads/aes-2.webp",
            ),
            Reader.load(doc) { null },
        )
    }

    @Test
    fun `extracts reordered ChapterRow attributes inside non-standard chapter wrappers`() {
        val rowJson = JsonArray(
            listOf(
                JsonPrimitive(901),
                JsonPrimitive("capitulo-15"),
                JsonPrimitive("Capítulo 15 - Extra"),
            ),
        ).toString()
        val encodedRow = "v3~" + Base64.getEncoder().encodeToString(rowJson.toByteArray(Charsets.UTF_8)).reversed()
        val encodedHref = "xy2$" + Base64.getEncoder().encodeToString("/bl/obra/capitulo-15/".reversed().toByteArray(Charsets.UTF_8))
        val html = """
            <html><body>
              <ul class="xyaoi-chap-list">
                <li data-meta="noise" data-xy-row="$encodedRow">
                  <a href="#" data-z="unused" data-href-enc="$encodedHref">Ler</a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://3xyaoi.com/bl/obra/")
        val li = doc.selectFirst("[data-xy-row]")!!
        val row = ChapterRow.fromElement(li)!!
        assertEquals("capitulo-15", row.slug)
        assertEquals("Capítulo 15 - Extra", row.name)
        assertEquals("/bl/obra/capitulo-15/", ChapterRow.decodedHref(li.selectFirst("a")!!))
    }

    @Test
    fun `SessionManager does not clear persistent block from cache-only 200 responses and resolves relative referer`() {
        var now = 1_000L
        var solves = 0
        val manager = SessionManager(
            solver = { _, _, _ ->
                solves++
                false
            },
            cooldownMs = 5_000L,
            clockMs = { now },
        )
        val req = Request.Builder().url("https://3xyaoi.com/biblioteca/").build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                manager.interceptWithRecovery(chain.call(), chain.request()) { r ->
                    if (r.header("X-Cache-Only") == "1") {
                        val cached = Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("cached".toResponseBody("text/html".toMediaType()))
                            .build()
                        Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .cacheResponse(cached)
                            .networkResponse(null)
                            .body("cached".toResponseBody("text/html".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .header("cf-mitigated", "challenge")
                            .body("<html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        // Trigger PERSISTENT_BLOCK
        assertThrows(CloudflareSessionException::class.java) {
            client.newCall(req).execute()
        }
        assertEquals(SessionState.PERSISTENT_BLOCK, manager.state)
        assertEquals(1, solves)

        // A disk-cache-only 200 response must NOT clear PERSISTENT_BLOCK
        now += 1_000L
        val cacheHitReq = Request.Builder()
            .url("https://3xyaoi.com/wp-content/uploads/cached.jpg")
            .header("X-Cache-Only", "1")
            .cacheControl(CacheControl.FORCE_CACHE)
            .build()
        client.newCall(cacheHitReq).execute().close()
        assertEquals(SessionState.PERSISTENT_BLOCK, manager.state)

        // Subsequent network call inside cooldown still fast-fails without calling solver again
        assertThrows(CloudflareSessionException::class.java) {
            client.newCall(req).execute()
        }
        assertEquals(1, solves)

        // Verify relative referer resolution on subresources
        val ajaxReq = Request.Builder()
            .url("https://3xyaoi.com/wp-admin/admin-ajax.php")
            .header("Referer", "/biblioteca/")
            .build()
        assertEquals(
            "https://3xyaoi.com/biblioteca/",
            WebViewChallengeSolver.challengeNavigationUrl(ajaxReq, "https://3xyaoi.com"),
        )
    }

    @Test
    fun `ImageContentInterceptor rejects 200 text_plain and application_json payloads`() {
        listOf("text/plain; charset=utf-8", "application/json", "text/html").forEach { contentType ->
            val client = OkHttpClient.Builder()
                .addInterceptor(ImageContentInterceptor())
                .addInterceptor(
                    Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", contentType)
                            .body("error payload".toResponseBody(contentType.toMediaType()))
                            .build()
                    },
                )
                .build()
            val req = Request.Builder()
                .url("https://3xyaoi.com/wp-content/uploads/01.jpg")
                .header("Accept", "image/*")
                .build()
            assertThrows(IOException::class.java) {
                client.newCall(req).execute()
            }
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            Injekt.addSingleton<Json>(Json)
        }
    }
}
