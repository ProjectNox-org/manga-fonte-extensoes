package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Base64

class ReaderHardeningTest {

    private fun encodeLiveEntry(url: String, key: String, shift: Int, mod: Int, xorMask: Int): String {
        val utf8 = url.toByteArray(Charsets.UTF_8)
        val raw = ByteArray(utf8.size) { i ->
            val xored = (utf8[i].toInt() and 0xFF) xor key[i % key.length].code xor xorMask
            ((xored - shift - (i % mod) + 512) and 0xFF).toByte()
        }
        val rev = raw.reversedArray()
        return Base64.getEncoder().encodeToString(rev).trimEnd('=')
    }

    private fun encodeReversedBase64Json(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).reversed()

    private fun encodeRc4Template(key: String, plain: String): String {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + key[i % key.length].code) and 0xFF
            state[i] = state[j].also { state[j] = state[i] }
        }
        val input = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(input.size)
        var i = 0
        j = 0
        input.forEachIndexed { idx, byte ->
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            state[i] = state[j].also { state[j] = state[i] }
            out[idx] = ((byte.toInt() and 0xFF) xor state[(state[i] + state[j]) and 0xFF]).toByte()
        }
        return Base64.getEncoder().encodeToString(out).chunked(7).joinToString("~")
    }

    @Test
    fun `extracts text_plain payload with positive shift xor mask and bypasses rc4 warning honeypot`() = runBlocking {
        val key = "bodinho_protect_88aa7289cbba088241003dd413393a30"
        val decoyKey = "S6V6Q07R57W2R3Q0"
        val fakeTemplate = encodeRc4Template(
            decoyKey,
            """["https://3xyaoi.com/wp-content/uploads/warning_app.jpg","https://http.cat/403.jpg"]""",
        )
        val entries = listOf(
            "https://3xyaoi.com/wp-content/uploads/warning_app.jpg",
            "https://http.cat/403.jpg",
            "https://http.cat/401.jpg",
            "https://http.cat/429.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/0.1-knot.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/1.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/150-recrutamento.jpg",
        ).map { encodeLiveEntry(it, key, shift = 8, mod = 7, xorMask = 58) }

        val json = buildString {
            append('{')
            append("\"m_v\":\"2\",")
            append("\"n_d\":9,")
            append("\"a_d\":[\"decoy\"],")
            append("\"l_0bd0e41b\":\"$key\",")
            append("\"l_12f0ad3d\":[${entries.joinToString(",") { "\"$it\"" }}],")
            append("\"l_f59468b6\":4")
            append('}')
        }
        val encodedJson = encodeReversedBase64Json(json)
        val secKeyExpr = decoyKey.map { "${it.code + 27} - 27" }.joinToString("+") { "String.fromCharCode($it)" }

        val html = """
            <!doctype html>
            <html><body>
              <div class="reading-content xyaoi-reading-content-clean">
                <script type="text/template" id="v_d3f7bea4ad">$fakeTemplate</script>
                <div id="l_fefbad09_414_r"></div>
                <script type="text/plain" id="l_fefbad09_414_l_2d457db7">$encodedJson</script>
                <script>
                  (function(){
                    var secKey = $secKeyExpr;
                    var vaultId = "v_d3f7bea4ad";
                    function _rc4(k, b) { return b; }
                    var _id = "l_fefbad09_414";
                    var _el = document.getElementById(_id + '_l_2d457db7');
                    var _dat = JSON.parse(atob(_el.textContent.trim().split('').reverse().join('')));
                    var _k9 = _dat["l_0bd0e41b"];
                    var l_011a26fd = 8;
                    var _fakePayload = _dat["l_12f0ad3d"].slice(0, _dat["l_f59468b6"]);
                    var _validPayload = _dat["l_12f0ad3d"].slice(_dat["l_f59468b6"]);
                    _validPayload.forEach(function(_e) {
                      var _dec = atob(_e);
                      var _raw = _dec.split('').reverse().join('');
                      var _u = '';
                      for(var i=0; i<_raw.length; i++) {
                        var _b = (_raw.codePointAt(i) + l_011a26fd + (i % 7)) % 256;
                        _u += String.fromCharCode(_b ^ _k9.charCodeAt(i % _k9.length) ^ 58);
                      }
                    });
                  })();
                </script>
              </div>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://3xyaoi.com/bl/knot-meant-to-be/capitulo-9/")
        val pages = Reader.load(document) { null }
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/0.1-knot.jpg",
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/1.jpg",
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_1/ch9/150-recrutamento.jpg",
            ),
            pages,
        )
    }

    @Test
    fun `handles signed shifts mixed signs renamed vars and missing slice call via decoy elimination`() = runBlocking {
        val key = "dynamic_key_without_fixed_name_9911"
        val entries = listOf(
            "https://3xyaoi.com/wp-content/uploads/warning_app.jpg",
            "https://http.cat/403.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_2/01.jpg",
            "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_2/02.jpg",
        ).map { url ->
            val utf8 = url.toByteArray(Charsets.UTF_8)
            // Inverse of decode transform (+ shiftA - shiftB - (idx % 5)): subtract shiftA, add shiftB, add (idx % 5)
            val raw = ByteArray(utf8.size) { idx ->
                val xored = (utf8[idx].toInt() and 0xFF) xor key[idx % key.length].code xor 0x15
                ((xored - 12 + 3 + (idx % 5) + 512) and 0xFF).toByte()
            }
            Base64.getEncoder().encodeToString(raw.reversedArray())
        }
        val json = """{"decoyInt":99,"kField":"$key","off":2,"items":[${entries.joinToString(",") { "\"$it\"" }}]}"""
        val encodedJson = encodeReversedBase64Json(json)
        val html = """
            <!doctype html>
            <html><body>
              <template id="box_99">$encodedJson</template>
              <script>
                (function(){
                  const box = document.getElementById("box_99");
                  const parsed = JSON.parse(atob(box.textContent.trim().split('').reverse().join('')));
                  const mask = 0x15;
                  const sA = 12;
                  const sB = 3;
                  const sec = parsed["kField"];
                  const items = parsed["items"];
                  for (var idx = parsed["off"]; idx < items.length; idx++) {
                    var _dec = atob(items[idx]);
                    var _raw = _dec.split('').reverse().join('');
                    for (var c = 0; c < _raw.length; c++) {
                      var b = (_raw.codePointAt(c) + sA - sB - (c % 5) + 256) % 256;
                      String.fromCharCode(b ^ mask ^ sec.codePointAt(c % sec.length));
                    }
                  }
                })();
              </script>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://3xyaoi.com/bl/test/capitulo-1/")
        val pages = Reader.load(document) { null }
        assertEquals(
            listOf(
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_2/01.jpg",
                "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_2/02.jpg",
            ),
            pages,
        )
    }

    @Test
    fun `still throws BrowserRestricted when only warning_app exists and no real reader succeeds`() {
        val key = "T6V6Q07R57W2R3Q0"
        val template = encodeRc4Template(key, """["https://3xyaoi.com/wp-content/uploads/warning_app.jpg"]""")
        val keyExpr = key.map { "${it.code + 27} - 27" }.joinToString("+") { "String.fromCharCode($it)" }
        val html = """
            <html><body>
              <script type="text/template" id="v_only">$template</script>
              <script>
                var secKey = $keyExpr;
                var vaultId = "v_only";
                function _rc4(k, b) {
                  var s=[], j=0, x, res='';
                  for (var i=0; i<256; i++) s[i]=i;
                  return res ^ b;
                }
              </script>
            </body></html>
        """.trimIndent()

        val document = Jsoup.parse(html, "https://3xyaoi.com/bl/blocked/capitulo-1/")
        assertThrows(Reader.BrowserRestricted::class.java) {
            runBlocking { Reader.load(document) { null } }
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
