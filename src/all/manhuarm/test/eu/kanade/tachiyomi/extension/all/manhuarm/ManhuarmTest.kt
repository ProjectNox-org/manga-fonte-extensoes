package eu.kanade.tachiyomi.extension.all.manhuarm

import eu.kanade.tachiyomi.multisrc.manhuarm.Language
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.utils.sha256
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ManhuarmTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpInjekt() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }

        // Real Nightwatcher Chapter 694 DOM/CSS/WASM snapshot captured from live manhuarmtl.com
        private val REAL_CHAPTER_694_HTML = """
            <html><head><style>
                #mrm-c1 { position: fixed !important; z-index: 750195 !important; --mrm-k1: "35351047"; }
                :root { --vb8fc24: 696; }
                #c9ea150 {
                    position: fixed !important;
                    --w4e73bd: 651;
                    z-index: calc(var(--vb8fc24) * 1000 + var(--w4e73bd)) !important;
                    --kfccbf7: "e11d429e";
                }
            </style></head><body>
                <div id="mrm-c1"></div>
                <span id="mrm-d1" data-u="42c9" hidden></span>
                <span id="mrm-d2" data-v="acb1" data-w="17390126" hidden></span>
                <div id="c9ea150"></div>
                <span id="df54d8d" data-5b0d="53da" hidden></span>
                <span id="ef7b912" data-60d9="a40d" data-f784="19bdf5b9" hidden></span>
                <template id="t6f6331" data-m="Qee+vdm/ZMbjI85LK8vN7Q==">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</template>
                <script>
                    const _v9 = {"p":"Zlm944RQVHv/9jPKZFUi0CgsMv4wOwJauCr38oaOUPjATEzQWd1ZN8sIrBGYfBHh","t":1795402368,"n":"d070bb457335199ada702ee2","s":"decoy9_4474739b23f3","g":"d6a13de17870dac15ca4458df4691ccc46e5516095ff4f2c36b522b8c545d9ef","cs":60219,"u":"https://manhuarmtl.com/fetch-ocr.php"};
                    const _tplId = "t6f6331";
                </script>
            </body></html>
        """.trimIndent()
    }

    @Test
    fun `readerKeysV10 ignores v9 decoy and evaluates real CSS calc, 16x4 canvas salt, and WASM challenge`() {
        val doc = Jsoup.parse(REAL_CHAPTER_694_HTML, "https://manhuarmtl.com/manga/nightwatcher/chapter-694/")
        val keys = ReaderKeysV10.fromOrNull(doc)
        assertNotNull(keys)
        keys!!

        assertEquals("https://manhuarmtl.com/fetch-ocr.php", keys.endpoint)
        assertEquals(696651L, CssSaltReader.read(doc, "c9ea150", "--kfccbf7").zIndex)
        assertEquals(1580642340L, canvasSaltV10(2756666898L))
        assertEquals(530017201L, keys.answer)

        val headers = keys.bootHeaders(Headers.Builder().build())
        assertEquals("XMLHttpRequest", headers["X-Requested-With"])
        assertEquals("fd27789949028fe1", headers["X-Rdr-Ctx"])
        assertEquals("d070bb457335199ada702ee2", headers["X-Rdr-Seq"])
        assertEquals("1795402368", headers["X-Rdr-Epoch"])
        assertEquals(
            "e786c861bbf8aa591e6eb536110db8b199df1a9772cca0bfa1577b478a2f3102",
            headers["X-Rdr-Auth"],
        )
        assertFalse(headers["X-Rdr-Mac"].isNullOrBlank())
        assertNull(headers["Origin"])
    }

    @Test
    fun `css calc handles nested variables, inline overrides, and comments`() {
        val html = """
            <html><head><style>
                /* #x11 { z-index: 999999; --kx: "00000000"; } */
                :root { --a1: 400; --b2: 20; }
                #x11 {
                    --b2: 25;
                    --c3: 7;
                    z-index: calc((var(--a1) + var(--b2)) * 100 + var(--c3)) !important;
                    --kx: "01020304";
                }
            </style></head>
            <body><div id="x11" style="--kx: 'AABBCCDD';"></div></body></html>
        """.trimIndent()
        val parsed = CssSaltReader.read(Jsoup.parse(html), "x11", "--kx")
        assertEquals(42507L, parsed.zIndex)
        assertEquals("aabbccdd", parsed.customKey)
    }

    @Test
    fun `wasm evaluator supports negative sleb constants, shifts, and rotations`() {
        val wasm = singleFuncWasm(
            exportName = "fn",
            paramCount = 2,
            body = byteArrayOf(
                0x20, 0x00,
                0x41, 0x79,
                0x6A,
                0x20, 0x01,
                0x41, 0x03,
                0x76,
                0x73,
                0x41, 0x05,
                0x77,
                0x0B,
            ),
        )
        val p0 = 0x12345678
        val p1 = -0x11223344
        val expected = (((p0 - 7) xor (p1 ushr 3)).rotateLeft(5)).toLong() and 0xFFFFFFFFL
        val actual = WasmI32Evaluator.execute(wasm, "fn", intArrayOf(p0, p1))
        assertEquals(expected, actual)
    }

    @Test
    fun `v10 reader boots without v9 decoy, decrypts tiles, and recovers from 403`() = runBlocking {
        val fakeWebp = ByteArray(128) { i -> (i * 7 + 3).toByte() }.apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
        }
        val server = makeSyntheticV10Server(fakeWebp)
        val recorded = Collections.synchronizedList(ArrayList<RecordedCall>())
        val tile403Once = AtomicInteger(0)

        val transport = Interceptor { chain ->
            val req = chain.request()
            val bodyText = req.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }
            recorded += RecordedCall(req.method, req.url.toString(), req.headers, bodyText)

            when {
                req.url.encodedPath == "/manga/nightwatcher/chapter-694/" ->
                    textResponse(req, 200, server.chapterHtml)
                req.url.encodedPath == "/fetch-ocr.php" && bodyText != null -> when {
                    req.header("X-Mrm-Seed")?.startsWith("decoy") == true -> textResponse(req, 403, "")
                    req.header("X-Rdr-Mac") != null && "\"op\":\"boot\"" in bodyText -> {
                        assertEquals(server.bootProof, req.header("X-Rdr-Mac"))
                        jsonResponse(req, 200, server.encryptedBootResponse)
                    }
                    req.header("X-Rdr-Mac") != null && "\"op\":\"tile\"" in bodyText -> {
                        assertEquals(server.tileProof, req.header("X-Rdr-Mac"))
                        if (tile403Once.getAndIncrement() == 0) {
                            textResponse(req, 403, "")
                        } else {
                            bytesResponse(req, 200, "application/octet-stream", server.encryptedTileBytes)
                        }
                    }
                    else -> textResponse(req, 403, "")
                }
                else -> textResponse(req, 404, "not found")
            }
        }

        lateinit var client: OkHttpClient
        val reader = ManhuarmV10Reader(
            httpClientProvider = { client },
            baseHeadersProvider = { Headers.Builder().set("Referer", "https://manhuarmtl.com/").build() },
            callExecutor = { c, r -> c.newCall(r).execute().also { if (!it.isSuccessful) { val code = it.code; it.close(); throw IOException("HTTP error $code") } } },
        )
        client = OkHttpClient.Builder()
            .addInterceptor(reader::intercept)
            .addInterceptor(transport)
            .build()

        val pages = reader.getPageListOrNull(
            "https://manhuarmtl.com/manga/nightwatcher/chapter-694/",
            Language("en"),
        )
        assertNotNull(pages)
        pages!!
        assertEquals(1, pages.size)
        val imageUrl = pages[0].imageUrl!!
        assertTrue(imageUrl.startsWith("https://${ManhuarmV10Reader.V10_PAGE_HOST}/#"))
        assertEquals(1, recorded.count { "\"op\":\"boot\"" in (it.body ?: "") })

        val imgReq = Request.Builder().url(imageUrl).fetch(Fetch.IMAGE).build()
        val imgBytes = client.newCall(imgReq).execute().use { res ->
            assertEquals(200, res.code)
            assertEquals("image/webp", res.body.contentType()?.toString())
            res.body.bytes()
        }
        assertArrayEquals(fakeWebp, imgBytes)
        assertEquals(2, tile403Once.get())
    }

    @Test
    fun `invalid decrypted tile bytes fail cleanly with IOException`() = runBlocking {
        val invalidPayload = ByteArray(96) { 0x55 }
        val server = makeSyntheticV10Server(invalidPayload)
        val transport = Interceptor { chain ->
            val req = chain.request()
            val bodyText = req.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }
            when {
                req.url.encodedPath == "/manga/nightwatcher/chapter-694/" ->
                    textResponse(req, 200, server.chapterHtml)
                req.url.encodedPath == "/fetch-ocr.php" && bodyText != null && "\"op\":\"boot\"" in bodyText ->
                    jsonResponse(req, 200, server.encryptedBootResponse)
                req.url.encodedPath == "/fetch-ocr.php" && bodyText != null && "\"op\":\"tile\"" in bodyText ->
                    bytesResponse(req, 200, "application/octet-stream", server.encryptedTileBytes)
                else -> textResponse(req, 404, "not found")
            }
        }

        lateinit var client: OkHttpClient
        val reader = ManhuarmV10Reader(
            httpClientProvider = { client },
            baseHeadersProvider = { Headers.headersOf() },
            callExecutor = { c, r -> c.newCall(r).execute().also { if (!it.isSuccessful) { val code = it.code; it.close(); throw IOException("HTTP error $code") } } },
        )
        client = OkHttpClient.Builder()
            .addInterceptor(reader::intercept)
            .addInterceptor(transport)
            .build()

        val pages = reader.getPageListOrNull(
            "https://manhuarmtl.com/manga/nightwatcher/chapter-694/",
            Language("en"),
        )!!
        var threwIo = false
        try {
            client.newCall(Request.Builder().url(pages[0].imageUrl!!).build()).execute().close()
        } catch (_: IOException) {
            threwIo = true
        }
        assertTrue(threwIo)
    }

    private class RecordedCall(
        val method: String,
        val url: String,
        val headers: Headers,
        val body: String?,
    )

    private class SyntheticV10Server(
        val chapterHtml: String,
        val bootProof: String,
        val tileProof: String,
        val encryptedBootResponse: String,
        val encryptedTileBytes: ByteArray,
    )

    private fun makeSyntheticV10Server(plainImageBytes: ByteArray): SyntheticV10Server {
        val wasmBytes = singleFuncWasm(
            exportName = "f0",
            paramCount = 5,
            body = byteArrayOf(
                0x20, 0x00, 0x20, 0x03, 0x73,
                0x20, 0x01, 0x20, 0x04, 0x73,
                0x6A,
                0x20, 0x02,
                0x6A,
                0x0B,
            ),
        )
        val ds = 0x12345678L
        val cs = 500321L
        val canvasSeed = 987654321L
        val cvs = canvasSaltV10(canvasSeed)
        val rkHex = "a1b2c3d4e5f60718"
        val rkh = 0xA1B2C3D4L
        val rkl = 0xE5F60718L
        val ansInt = (((ds xor rkh).toInt() + (cs xor rkl).toInt()) + cvs.toInt()).toLong() and 0xFFFFFFFFL

        val nonce = "n1234567890abcdef"
        val seed = "realv10seed1234"
        val ts = 1795403000L
        val sig = "sig_abc123"
        val wasmB64 = Base64.getEncoder().encodeToString(wasmBytes)

        val cfgJson = """
            {"p":"blob_test","t":$ts,"n":"$nonce","s":"$seed","g":"$sig","cs":$canvasSeed,"u":"https://manhuarmtl.com/fetch-ocr.php","w":"$wasmB64","x":"f0","ec":"c_real","d1":"d1_real","d2":"d2_real","au":"data-hi","av":"data-lo","aw":"data-rkl","ck":"--k-hi"}
        """.trimIndent()
        val mask = byteArrayOf(0x31, 0x7A, 0x5C, 0x29, 0x11)
        val cfgRaw = cfgJson.toByteArray(Charsets.UTF_8)
        val packed = ByteArray(cfgRaw.size) { i -> (cfgRaw[i].toInt() xor mask[i % mask.size].toInt()).toByte() }

        val html = """
            <html><head><style>
                :root { --v1: 500; }
                #c_real { --v2: 321; z-index: calc(var(--v1) * 1000 + var(--v2)) !important; --k-hi: "a1b2c3d4"; }
                #mrm-c1 { z-index: 999999; --mrm-k1: "00000000"; }
            </style></head><body>
                <span id="mrm-d1" data-u="0000" hidden></span>
                <span id="mrm-d2" data-v="0000" data-w="00000000" hidden></span>
                <div id="c_real"></div>
                <span id="d1_real" data-hi="1234" hidden></span>
                <span id="d2_real" data-lo="5678" data-rkl="e5f60718" hidden></span>
                <template id="t_real" data-m="${Base64.getEncoder().encodeToString(mask)}">${Base64.getEncoder().encodeToString(packed)}</template>
                <script>
                    const _v9 = {"p":"decoy","t":$ts,"n":"$nonce","s":"decoy9_bad","g":"bad","cs":1,"u":"https://manhuarmtl.com/fetch-ocr.php"};
                    const _tplId = "t_real";
                </script>
            </body></html>
        """.trimIndent()

        val bootProof = "$ansInt|$nonce|$seed|$rkHex|v10-boot".sha256().toHexString()
        val bootKey = "v10|$seed|$ansInt|$rkHex|$nonce|$ts".sha256()

        val imageKey = "ik_001"
        val imageSeed = 0x13572468L
        val tileTicket = "tile_tk_0"
        val bootJson = """{"ver":10,"pages":[{"id":"pg0","ls":[]}],"tiles":[{"n":0,"id":"pg0","tk":"$tileTicket","ek":"$imageKey","es":$imageSeed}]}"""
        val bootSealed = encryptAesGcmParts(bootKey, bootJson.toByteArray(Charsets.UTF_8))
        val bootEnvJson = V10EnvelopeDto(
            a = Base64.getEncoder().encodeToString(bootSealed.first),
            b = Base64.getEncoder().encodeToString(bootSealed.second),
            c = Base64.getEncoder().encodeToString(bootSealed.third),
        ).toJsonString()

        val tileProof = "$ansInt|$imageKey|$imageSeed|$nonce|$rkHex|v10-tile".sha256().toHexString()
        val tileKey = "v10-ib|$ansInt|$imageKey|$imageSeed|$rkHex|$nonce".sha256()

        val xored = xorPrefix(plainImageBytes, (ansInt xor imageSeed xor 0xA5A55A5AL) and 0xFFFFFFFFL, 4096)
        val shuffled = shuffle32ForServer(xored, (ansInt xor imageSeed) and 0xFFFFFFFFL)
        val (iv, ct, tag) = encryptAesGcmParts(tileKey, shuffled)
        val encryptedTile = iv + tag + ct

        return SyntheticV10Server(html, bootProof, tileProof, bootEnvJson, encryptedTile)
    }

    private fun singleFuncWasm(exportName: String, paramCount: Int, body: ByteArray): ByteArray {
        fun sec(id: Int, payload: ByteArray) = byteArrayOf(id.toByte(), payload.size.toByte()) + payload
        val typePayload = byteArrayOf(0x01, 0x60, paramCount.toByte()) + ByteArray(paramCount) { 0x7F } + byteArrayOf(0x01, 0x7F)
        val funcPayload = byteArrayOf(0x01, 0x00)
        val nameBytes = exportName.toByteArray(Charsets.UTF_8)
        val exportPayload = byteArrayOf(0x01, nameBytes.size.toByte()) + nameBytes + byteArrayOf(0x00, 0x00)
        val bodyWithLocals = byteArrayOf(0x00) + body
        val codePayload = byteArrayOf(0x01, bodyWithLocals.size.toByte()) + bodyWithLocals
        return byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) +
            sec(1, typePayload) + sec(3, funcPayload) + sec(7, exportPayload) + sec(10, codePayload)
    }

    private fun shuffle32ForServer(bytes: ByteArray, seed: Long): ByteArray {
        if (bytes.size < 32) return bytes
        var state = (seed and 0xFFFFFFFFL).takeIf { it != 0L } ?: 1L
        fun next(): Long {
            state = state xor (state shl 13 and 0xFFFFFFFFL)
            state = state xor (state ushr 17)
            state = state xor (state shl 5 and 0xFFFFFFFFL)
            return state
        }
        val perm = IntArray(32) { it }
        for (i in 31 downTo 1) {
            val j = (next() % (i + 1)).toInt()
            perm[i] = perm[j].also { perm[j] = perm[i] }
        }
        val chunk = bytes.size / 32
        val out = ByteArray(bytes.size)
        for (i in 0 until 32) {
            bytes.copyInto(out, destinationOffset = i * chunk, startIndex = perm[i] * chunk, endIndex = (perm[i] + 1) * chunk)
        }
        if (bytes.size > 32 * chunk) {
            bytes.copyInto(out, destinationOffset = 32 * chunk, startIndex = 32 * chunk, endIndex = bytes.size)
        }
        return out
    }

    private fun encryptAesGcmParts(key: ByteArray, plain: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ctWithTag = cipher.doFinal(plain)
        val ct = ctWithTag.copyOfRange(0, ctWithTag.size - 16)
        val tag = ctWithTag.copyOfRange(ctWithTag.size - 16, ctWithTag.size)
        return Triple(iv, ct, tag)
    }

    private fun textResponse(req: Request, code: Int, body: String): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_2)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun jsonResponse(req: Request, code: Int, body: String): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_2)
        .code(code)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun bytesResponse(req: Request, code: Int, mime: String, bytes: ByteArray): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_2)
        .code(code)
        .message("OK")
        .body(bytes.toResponseBody(mime.toMediaType()))
        .build()
}
