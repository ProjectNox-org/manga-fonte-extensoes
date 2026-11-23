package eu.kanade.tachiyomi.extension.all.manhuarm

import eu.kanade.tachiyomi.multisrc.manhuarm.Dialog
import eu.kanade.tachiyomi.multisrc.manhuarm.Language
import eu.kanade.tachiyomi.multisrc.manhuarm.PageFragment
import eu.kanade.tachiyomi.multisrc.manhuarm.pageFragment
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.network.Fetch
import keiyoushi.network.call
import keiyoushi.network.fetch
import keiyoushi.utils.aesDecrypt
import keiyoushi.utils.aesGcm
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.sha256
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

@Serializable
internal class V10ConfigDto(
    val p: String,
    val t: Long,
    val n: String,
    val s: String,
    val g: String,
    val cs: Long,
    val u: String,
    val w: String,
    val x: String,
    val ec: String,
    val d1: String,
    val d2: String,
    val au: String,
    val av: String,
    val aw: String,
    val ck: String,
)

@Serializable
internal class V10BootRequestDto(
    private val op: String,
    private val blob: String,
)

@Serializable
internal class V10TileRequestDto(
    private val op: String,
    private val ref: String,
)

@Serializable
internal class V10EnvelopeDto(
    val a: String,
    val b: String,
    val c: String,
)

@Serializable
internal class V10BootDataDto(
    val ver: Int,
    val pages: List<V10PageDto> = emptyList(),
    val tiles: List<V10TileDto> = emptyList(),
)

@Serializable
internal class V10PageDto(
    val id: String,
    val ls: List<V10TextBoxDto> = emptyList(),
)

@Serializable
internal class V10TextBoxDto(
    private val q: List<Float>,
    private val t: String?,
) {
    fun toDialog(offsets: IntArray): Dialog {
        val x = q.getOrElse(0) { 0f } - offsets[0]
        val y = q.getOrElse(1) { 0f } - offsets[1]
        val width = q.getOrElse(2) { 0f } - offsets[2]
        val height = q.getOrElse(3) { 0f } - offsets[3]
        return Dialog(
            x = x,
            y = y,
            _width = width,
            _height = height,
            textByLanguage = mapOf("text" to t.orEmpty()),
        )
    }
}

@Serializable
internal class V10TileDto(
    val n: Int,
    val id: String,
    val tk: String,
    val ek: String,
    val es: Long,
)

/**
 * Manages v10 chapter sessions, page listing, encrypted tile fetching, and 403 session recovery.
 */
internal class ManhuarmV10Reader(
    private val httpClientProvider: () -> OkHttpClient,
    private val baseHeadersProvider: () -> Headers,
    private val callExecutor: suspend (OkHttpClient, Request) -> Response = { client, request ->
        client.call(request)
    },
) {
    private val sessionMutex = Mutex()
    private val sessions = object : LinkedHashMap<String, V10ChapterSession>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V10ChapterSession>?): Boolean = size > MAX_CACHED_SESSIONS
    }

    suspend fun getPageListOrNull(chapterUrl: String, language: Language): List<Page>? {
        val session = sessionMutex.withLock { openOrNull(chapterUrl) } ?: return null
        return session.tiles.mapIndexed { index, tile ->
            val dialogues = session.dialogues[tile.id].orEmpty().filter { it.getTextBy(language).isNotBlank() }
            val imageUrl = HttpUrl.Builder()
                .scheme("https")
                .host(V10_PAGE_HOST)
                .fragment(PageFragment(chapterUrl, index, dialogues).toJsonString())
                .build()
            Page(index, imageUrl = imageUrl.toString())
        }
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != V10_PAGE_HOST) return chain.proceed(request)
        return try {
            val page = request.url.pageFragment() ?: throw IOException("Missing page fragment")
            val session = runBlocking { getSession(page.chapter) }
            val response = chain.proceed(session.imageRequest(request, page.index))
            if (response.code != 403) return session.decryptImage(response, page.index)
            response.close()
            val reopened = runBlocking { getSession(page.chapter, rejected = session) }
            reopened.decryptImage(chain.proceed(reopened.imageRequest(request, page.index)), page.index)
        } catch (e: Exception) {
            throw e as? IOException ?: IOException(e.message, e)
        }
    }

    // Caller holds sessionMutex.
    private suspend fun openOrNull(chapterUrl: String): V10ChapterSession? {
        val headers = baseHeadersProvider()
        val client = httpClientProvider()
        val docRequest = Request.Builder()
            .url(chapterUrl)
            .headers(headers)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .fetch(Fetch.DOCUMENT)
            .build()
        val document = callExecutor(client, docRequest).asJsoup()
        val keys = ReaderKeysV10.fromOrNull(document) ?: return null
        val bootRequest = Request.Builder()
            .url(keys.endpoint)
            .headers(keys.bootHeaders(headers))
            .post(keys.bootRequest.toJsonRequestBody())
            .fetch(Fetch.XHR.from(chapterUrl))
            .build()
        val data = callExecutor(client, bootRequest)
            .parseAs<V10EnvelopeDto>()
            .let(keys::decryptBoot)

        val offsets = keys.boxOffsets()
        val session = V10ChapterSession(
            chapterUrl = chapterUrl,
            keys = keys,
            tiles = data.tiles.sortedBy { it.n },
            dialogues = data.pages.associate { page ->
                page.id to page.ls.map { it.toDialog(offsets) }
            },
            baseHeaders = headers,
        )
        sessions[chapterUrl] = session
        return session
    }

    private suspend fun getSession(chapterUrl: String, rejected: V10ChapterSession? = null): V10ChapterSession = sessionMutex.withLock {
        sessions[chapterUrl]?.takeIf { it !== rejected && !it.isExpired }
            ?: openOrNull(chapterUrl)
            ?: throw IOException("Chapter no longer exposes v10 reader metadata")
    }

    private class V10ChapterSession(
        private val chapterUrl: String,
        private val keys: ReaderKeysV10,
        val tiles: List<V10TileDto>,
        val dialogues: Map<String, List<Dialog>>,
        private val baseHeaders: Headers,
    ) {
        private val openedAt = TimeSource.Monotonic.markNow()
        val isExpired: Boolean get() = openedAt.elapsedNow() > SESSION_LIFETIME

        fun imageRequest(request: Request, index: Int): Request {
            val tile = tiles.getOrNull(index)
                ?: throw IOException("Missing tile metadata for page $index")
            return request.newBuilder()
                .url(keys.endpoint)
                .headers(keys.tileHeaders(baseHeaders, tile))
                .post(keys.tileRequest(tile).toJsonRequestBody())
                .fetch(Fetch.XHR.from(chapterUrl))
                .build()
        }

        fun decryptImage(response: Response, index: Int): Response {
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw HttpException(code)
            }
            val tile = tiles[index]
            val image = keys.decryptTile(response.body.bytes(), tile)
            val mime = image.sniffImageType()
                ?: throw IOException("Unsupported or invalid decrypted image for page $index")
            return response.newBuilder()
                .body(image.asResponseBody(mime.toMediaType(), image.size))
                .build()
        }
    }

    companion object {
        const val V10_PAGE_HOST = "manhuarm-v10.invalid"
        private const val MAX_CACHED_SESSIONS = 3
        private val SESSION_LIFETIME = 14.minutes
    }
}

/**
 * Computes and holds the per-page cryptographic keys and proofs for Manhuarm's v10 protected canvas reader.
 */
internal class ReaderKeysV10 private constructor(
    private val cfg: V10ConfigDto,
    val answer: Long,
    private val rkHex: String,
) {
    val endpoint: String get() = cfg.u
    val bootRequest: V10BootRequestDto get() = V10BootRequestDto("boot", cfg.p)

    private val answerStr: String = answer.toString()

    fun bootHeaders(base: Headers): Headers = headers(base, sha256Hex("$answerStr|${cfg.n}|${cfg.s}|$rkHex|v10-boot"))

    fun decryptBoot(envelope: V10EnvelopeDto): V10BootDataDto {
        val key = "v10|${cfg.s}|$answerStr|$rkHex|${cfg.n}|${cfg.t}".sha256()
        val iv = envelope.a.decodeBase64Bytes()
        val data = envelope.b.decodeBase64Bytes() + envelope.c.decodeBase64Bytes()
        val cipher = aesGcm(key, iv)
        return data.aesDecrypt(cipher).decodeToString().parseAs<V10BootDataDto>().also {
            check(it.ver == 10) { "Unsupported v10 boot payload version: ${it.ver}" }
        }
    }

    fun boxOffsets(): IntArray {
        val hash = "v10-box|$answerStr|$rkHex|${cfg.n}".sha256()
        return intArrayOf(
            (hash[0].toInt() and 0xFF) + 23,
            (hash[1].toInt() and 0xFF) + 41,
            (hash[2].toInt() and 0xFF) + 17,
            (hash[3].toInt() and 0xFF) + 31,
        )
    }

    fun tileHeaders(base: Headers, tile: V10TileDto): Headers = headers(base, sha256Hex("$answerStr|${tile.ek}|${tile.es}|${cfg.n}|$rkHex|v10-tile"))

    fun tileRequest(tile: V10TileDto): V10TileRequestDto = V10TileRequestDto("tile", tile.tk)

    fun decryptTile(body: ByteArray, tile: V10TileDto): Buffer {
        check(body.size >= 29) { "Encrypted tile payload too short: ${body.size}" }
        val key = "v10-ib|$answerStr|${tile.ek}|${tile.es}|$rkHex|${cfg.n}".sha256()
        val iv = body.copyOfRange(0, 12)
        val tag = body.copyOfRange(12, 28)
        val cipher = aesGcm(key, iv)
        val payload = body.copyOf()
        payload.copyInto(payload, 12, 28)
        tag.copyInto(payload, payload.size - 16)
        val plain = cipher.doFinal(payload, 12, payload.size - 12)
        val imageSeed = tile.es and UINT32
        val unshuffled = unshuffle32(plain, (answer xor imageSeed) and UINT32)
        val unmasked = xorPrefix(unshuffled, (answer xor imageSeed xor PREFIX_XOR_MASK) and UINT32, PREFIX_XOR_LIMIT)
        return Buffer().write(unmasked)
    }

    private fun headers(base: Headers, proof: String): Headers = base.newBuilder()
        .removeAll("Origin")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("X-Rdr-Auth", cfg.g)
        .set("X-Rdr-Seq", cfg.n)
        .set("X-Rdr-Ctx", cfg.s)
        .set("X-Rdr-Epoch", cfg.t.toString())
        .set("X-Rdr-Mac", proof)
        .build()

    companion object {
        private const val UINT32 = 0xFFFFFFFFL
        private const val PREFIX_XOR_MASK = 0xA5A55A5AL
        private const val PREFIX_XOR_LIMIT = 4096

        private val TPL_ID_REGEX = Regex("""const\s+_tplId\s*=\s*["']([^"']+)["']""")
        private val RK_HEX_REGEX = Regex("^[0-9a-f]{16}$")

        fun fromOrNull(document: Document): ReaderKeysV10? {
            val tplId = document.select("script")
                .map(Element::data)
                .firstNotNullOfOrNull { TPL_ID_REGEX.find(it)?.groupValues?.get(1) }
                ?: return null
            val tpl = document.getElementById(tplId) ?: return null
            val mask = tpl.attr("data-m").decodeBase64Bytes()
            val raw = tpl.html().trim().decodeBase64Bytes()
            if (mask.isEmpty() || raw.isEmpty()) return null

            val unpacked = ByteArray(raw.size) { i ->
                (raw[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            val cfg = unpacked.decodeToString().parseAs<V10ConfigDto>()
            if (cfg.s.startsWith("decoy", ignoreCase = true)) return null

            val d1 = document.getElementById(cfg.d1)
            val d2 = document.getElementById(cfg.d2)
            val domHi = (d1?.attr(cfg.au)?.toLongOrNull(16) ?: 0L) and 0xFFFFL
            val domLo = (d2?.attr(cfg.av)?.toLongOrNull(16) ?: 0L) and 0xFFFFL
            val domSalt = ((domHi shl 16) or domLo) and UINT32

            val cssInfo = CssSaltReader.read(document, cfg.ec, cfg.ck)
            val rkHi = cssInfo.customKey.orEmpty()
            val rkLo = d2?.attr(cfg.aw).orEmpty().trim().lowercase()
            val rkHex = rkHi + rkLo
            check(RK_HEX_REGEX.matches(rkHex)) { "Invalid v10 reader key fragments" }

            val cssSalt = cssInfo.zIndex
            val cvsSalt = canvasSaltV10(cfg.cs and UINT32)
            val rkh = rkHex.substring(0, 8).toLong(16) and UINT32
            val rkl = rkHex.substring(8, 16).toLong(16) and UINT32

            val wasmBytes = cfg.w.decodeBase64Bytes()
            val answer = WasmI32Evaluator.execute(
                wasm = wasmBytes,
                exportName = cfg.x,
                args = intArrayOf(
                    domSalt.toInt(),
                    cssSalt.toInt(),
                    cvsSalt.toInt(),
                    rkh.toInt(),
                    rkl.toInt(),
                ),
            )
            return ReaderKeysV10(cfg, answer, rkHex)
        }
    }
}

internal data class CssAnchorValues(
    val zIndex: Long,
    val customKey: String?,
)

internal object CssSaltReader {
    private val COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")
    private val RULE_REGEX = Regex("""([^{}]+)\{([^{}]*)\}""")
    private val VAR_REF_REGEX = Regex("""var\(\s*(--[\w-]+)\s*(?:,\s*([^)]+))?\)""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val IMPORTANT_SUFFIX_REGEX = Regex("""!important\s*$""", RegexOption.IGNORE_CASE)

    fun read(document: Document, elementId: String, customKeyProp: String): CssAnchorValues {
        val vars = HashMap<String, String>()
        var zIndexExpr: String? = null
        var customKeyRaw: String? = null

        val targetSelector = "#$elementId"
        val cssText = document.select("style").joinToString("\n") { it.data() }
        val cleanCss = COMMENT_REGEX.replace(cssText, "")

        for (match in RULE_REGEX.findAll(cleanCss)) {
            val selectors = match.groupValues[1]
                .split(',')
                .map { it.trim() }
            val appliesToRoot = selectors.any { it == ":root" || it == "html" || it == "body" }
            val appliesToAnchor = selectors.any { it == targetSelector }
            if (!appliesToRoot && !appliesToAnchor) continue

            parseDeclarations(match.groupValues[2]).forEach { (prop, value) ->
                if (prop.startsWith("--")) {
                    vars[prop] = value
                }
                if (appliesToAnchor) {
                    when {
                        prop.equals("z-index", ignoreCase = true) -> zIndexExpr = value
                        prop == customKeyProp -> customKeyRaw = value
                    }
                }
            }
        }

        // Inline style overrides stylesheet rules if present
        document.getElementById(elementId)?.attr("style")?.takeIf(String::isNotBlank)?.let { inlineStyle ->
            parseDeclarations(inlineStyle).forEach { (prop, value) ->
                if (prop.startsWith("--")) vars[prop] = value
                when {
                    prop.equals("z-index", ignoreCase = true) -> zIndexExpr = value
                    prop == customKeyProp -> customKeyRaw = value
                }
            }
        }

        val resolvedKey = (customKeyRaw ?: vars[customKeyProp])
            ?.replace("\"", "")
            ?.replace("'", "")
            ?.replace(WHITESPACE_REGEX, "")
            ?.lowercase()

        val zIndex = zIndexExpr?.let { evaluateCssInt(it, vars) } ?: 0L
        return CssAnchorValues(zIndex and 0xFFFFFFFFL, resolvedKey)
    }

    private fun parseDeclarations(block: String): List<Pair<String, String>> = block.split(';')
        .mapNotNull { decl ->
            val colon = decl.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = decl.substring(0, colon).trim()
            val value = decl.substring(colon + 1)
                .replace(IMPORTANT_SUFFIX_REGEX, "")
                .trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }

    internal fun evaluateCssInt(raw: String, vars: Map<String, String>, depth: Int = 0): Long {
        if (depth > 16) return 0L
        var expr = raw.trim()
        while (VAR_REF_REGEX.containsMatchIn(expr)) {
            expr = VAR_REF_REGEX.replace(expr) { m ->
                val varName = m.groupValues[1]
                val resolved = vars[varName] ?: m.groupValues[2].trim()
                if (resolved.isEmpty()) "0" else "($resolved)"
            }
        }
        val unwrapped = if (expr.startsWith("calc(", ignoreCase = true) && expr.endsWith(")")) {
            expr.substring(5, expr.length - 1)
        } else {
            expr
        }
        return CalcParser(unwrapped).parse().toLong() and 0xFFFFFFFFL
    }

    private class CalcParser(private val text: String) {
        private var pos = 0

        fun parse(): Double = parseAddSub()

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseAddSub(): Double {
            var value = parseMulDiv()
            while (true) {
                skipWs()
                if (pos >= text.length) break
                value = when (text[pos]) {
                    '+' -> {
                        pos++
                        value + parseMulDiv()
                    }
                    '-' -> {
                        pos++
                        value - parseMulDiv()
                    }
                    else -> break
                }
            }
            return value
        }

        private fun parseMulDiv(): Double {
            var value = parseUnary()
            while (true) {
                skipWs()
                if (pos >= text.length) break
                value = when (text[pos]) {
                    '*' -> {
                        pos++
                        value * parseUnary()
                    }
                    '/' -> {
                        pos++
                        val d = parseUnary()
                        if (d == 0.0) 0.0 else value / d
                    }
                    else -> break
                }
            }
            return value
        }

        private fun parseUnary(): Double {
            skipWs()
            if (pos < text.length && text[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (pos < text.length && text[pos] == '+') {
                pos++
                return parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (pos < text.length && text[pos] == '(') {
                pos++
                val v = parseAddSub()
                skipWs()
                if (pos < text.length && text[pos] == ')') pos++
                return v
            }
            if (text.startsWith("calc(", pos, ignoreCase = true)) {
                pos += 4
                return parsePrimary()
            }
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: 0.0
        }
    }
}

/**
 * Pure-Kotlin evaluator for Manhuarm's straight-line 32-bit integer WebAssembly challenge functions.
 */
internal object WasmI32Evaluator {
    private val WASM_MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    fun execute(wasm: ByteArray, exportName: String, args: IntArray): Long {
        check(wasm.size >= 8 && wasm.copyOfRange(0, 8).contentEquals(WASM_MAGIC)) {
            "Invalid WebAssembly header"
        }

        val reader = ByteReader(wasm, 8)
        val types = ArrayList<Pair<Int, Int>>()
        val functionTypeIndices = ArrayList<Int>()
        var importedFuncCount = 0
        var targetFuncIndex: Int? = null
        val codeBodies = ArrayList<Pair<Int, ByteArray>>()

        while (!reader.eof()) {
            val sectionId = reader.readU8()
            val sectionLen = reader.readUleb32()
            val sectionEnd = reader.pos + sectionLen
            when (sectionId) {
                1 -> {
                    val count = reader.readUleb32()
                    repeat(count) {
                        val form = reader.readU8()
                        check(form == 0x60) { "Unexpected WASM type form: $form" }
                        val paramCount = reader.readUleb32()
                        reader.skip(paramCount)
                        val resultCount = reader.readUleb32()
                        reader.skip(resultCount)
                        types += paramCount to resultCount
                    }
                }
                2 -> {
                    val count = reader.readUleb32()
                    repeat(count) {
                        reader.readString()
                        reader.readString()
                        val kind = reader.readU8()
                        when (kind) {
                            0x00 -> {
                                reader.readUleb32()
                                importedFuncCount++
                            }
                            0x01 -> {
                                reader.readU8()
                                readLimits(reader)
                            }
                            0x02 -> readLimits(reader)
                            0x03 -> {
                                reader.readU8()
                                reader.readU8()
                            }
                            else -> error("Unsupported WASM import kind: $kind")
                        }
                    }
                }
                3 -> {
                    val count = reader.readUleb32()
                    repeat(count) { functionTypeIndices += reader.readUleb32() }
                }
                7 -> {
                    val count = reader.readUleb32()
                    repeat(count) {
                        val name = reader.readString()
                        val kind = reader.readU8()
                        val index = reader.readUleb32()
                        if (kind == 0x00 && name == exportName) {
                            targetFuncIndex = index
                        }
                    }
                }
                10 -> {
                    val count = reader.readUleb32()
                    repeat(count) {
                        val bodySize = reader.readUleb32()
                        val bodyEnd = reader.pos + bodySize
                        val localDeclCount = reader.readUleb32()
                        var extraLocals = 0
                        repeat(localDeclCount) {
                            extraLocals += reader.readUleb32()
                            reader.readU8()
                        }
                        val codeBytes = wasm.copyOfRange(reader.pos, bodyEnd)
                        codeBodies += extraLocals to codeBytes
                        reader.pos = bodyEnd
                    }
                }
            }
            reader.pos = sectionEnd
        }

        val funcIdx = targetFuncIndex ?: error("Exported WASM function '$exportName' not found")
        val definedIdx = funcIdx - importedFuncCount
        check(definedIdx in codeBodies.indices) { "Invalid WASM function index: $funcIdx" }

        val typeIdx = functionTypeIndices[definedIdx]
        val (paramCount, _) = types[typeIdx]
        val (extraLocals, codeBytes) = codeBodies[definedIdx]

        val locals = IntArray(maxOf(paramCount, args.size) + extraLocals)
        for (i in 0 until minOf(paramCount, args.size)) {
            locals[i] = args[i]
        }

        return evalCode(codeBytes, locals).toLong() and 0xFFFFFFFFL
    }

    private fun readLimits(reader: ByteReader) {
        val flags = reader.readU8()
        reader.readUleb32()
        if ((flags and 0x01) != 0) reader.readUleb32()
    }

    private fun evalCode(code: ByteArray, locals: IntArray): Int {
        val reader = ByteReader(code, 0)
        val stack = IntArray(maxOf(64, code.size))
        var sp = 0

        fun push(v: Int) {
            stack[sp++] = v
        }
        fun pop(): Int = stack[--sp]

        while (!reader.eof()) {
            when (val op = reader.readU8()) {
                0x01 -> Unit // nop
                0x0B, 0x0F -> if (sp > 0) return pop() else return 0 // end / return
                0x1A -> sp-- // drop
                0x1B -> { // select
                    val c = pop()
                    val v2 = pop()
                    val v1 = pop()
                    push(if (c != 0) v1 else v2)
                }
                0x20 -> push(locals[reader.readUleb32()]) // local.get
                0x21 -> locals[reader.readUleb32()] = pop() // local.set
                0x22 -> locals[reader.readUleb32()] = stack[sp - 1] // local.tee
                0x41 -> push(reader.readSleb32()) // i32.const
                0x45 -> push(if (pop() == 0) 1 else 0) // i32.eqz
                0x46 -> {
                    val b = pop()
                    val a = pop()
                    push(if (a == b) 1 else 0)
                }
                0x47 -> {
                    val b = pop()
                    val a = pop()
                    push(if (a != b) 1 else 0)
                }
                0x48 -> {
                    val b = pop()
                    val a = pop()
                    push(if (a < b) 1 else 0)
                }
                0x49 -> {
                    val b = pop()
                    val a = pop()
                    push(if (a.toUInt() < b.toUInt()) 1 else 0)
                }
                0x4A -> {
                    val b = pop()
                    val a = pop()
                    push(if (a > b) 1 else 0)
                }
                0x4B -> {
                    val b = pop()
                    val a = pop()
                    push(if (a.toUInt() > b.toUInt()) 1 else 0)
                }
                0x4C -> {
                    val b = pop()
                    val a = pop()
                    push(if (a <= b) 1 else 0)
                }
                0x4D -> {
                    val b = pop()
                    val a = pop()
                    push(if (a.toUInt() <= b.toUInt()) 1 else 0)
                }
                0x4E -> {
                    val b = pop()
                    val a = pop()
                    push(if (a >= b) 1 else 0)
                }
                0x4F -> {
                    val b = pop()
                    val a = pop()
                    push(if (a.toUInt() >= b.toUInt()) 1 else 0)
                }
                0x67 -> push(pop().countLeadingZeroBits())
                0x68 -> push(pop().countTrailingZeroBits())
                0x69 -> push(pop().countOneBits())
                0x6A -> {
                    val b = pop()
                    val a = pop()
                    push(a + b)
                } // i32.add
                0x6B -> {
                    val b = pop()
                    val a = pop()
                    push(a - b)
                } // i32.sub
                0x6C -> {
                    val b = pop()
                    val a = pop()
                    push(a * b)
                } // i32.mul
                0x6D -> {
                    val b = pop()
                    val a = pop()
                    push(a / b)
                } // i32.div_s
                0x6E -> {
                    val b = pop()
                    val a = pop()
                    push((a.toUInt() / b.toUInt()).toInt())
                } // i32.div_u
                0x6F -> {
                    val b = pop()
                    val a = pop()
                    push(a % b)
                } // i32.rem_s
                0x70 -> {
                    val b = pop()
                    val a = pop()
                    push((a.toUInt() % b.toUInt()).toInt())
                } // i32.rem_u
                0x71 -> {
                    val b = pop()
                    val a = pop()
                    push(a and b)
                } // i32.and
                0x72 -> {
                    val b = pop()
                    val a = pop()
                    push(a or b)
                } // i32.or
                0x73 -> {
                    val b = pop()
                    val a = pop()
                    push(a xor b)
                } // i32.xor
                0x74 -> {
                    val b = pop()
                    val a = pop()
                    push(a shl (b and 31))
                } // i32.shl
                0x75 -> {
                    val b = pop()
                    val a = pop()
                    push(a shr (b and 31))
                } // i32.shr_s
                0x76 -> {
                    val b = pop()
                    val a = pop()
                    push(a ushr (b and 31))
                } // i32.shr_u
                0x77 -> {
                    val b = pop()
                    val a = pop()
                    push(a.rotateLeft(b and 31))
                } // i32.rotl
                0x78 -> {
                    val b = pop()
                    val a = pop()
                    push(a.rotateRight(b and 31))
                } // i32.rotr
                else -> error("Unsupported WASM opcode: 0x${op.toString(16)}")
            }
        }
        return if (sp > 0) pop() else 0
    }

    private class ByteReader(private val bytes: ByteArray, var pos: Int) {
        fun eof(): Boolean = pos >= bytes.size
        fun skip(n: Int) {
            pos += n
        }
        fun readU8(): Int = bytes[pos++].toInt() and 0xFF

        fun readUleb32(): Int {
            var res = 0
            var shift = 0
            while (true) {
                val b = readU8()
                res = res or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
        }

        fun readSleb32(): Int {
            var res = 0
            var shift = 0
            var b: Int
            while (true) {
                b = readU8()
                res = res or ((b and 0x7F) shl shift)
                shift += 7
                if ((b and 0x80) == 0) break
            }
            if (shift < 32 && (b and 0x40) != 0) {
                res = res or (-1 shl shift)
            }
            return res
        }

        fun readString(): String {
            val len = readUleb32()
            val s = String(bytes, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
    }
}

private const val V10_UINT32 = 0xFFFFFFFFL

private fun xorShift32(seed: Long): () -> Long {
    var state = (seed and V10_UINT32).takeIf { it != 0L } ?: 1L
    return {
        state = state xor (state shl 13 and V10_UINT32)
        state = state xor (state ushr 17)
        state = state xor (state shl 5 and V10_UINT32)
        state
    }
}

internal fun canvasSaltV10(seed: Long): Long {
    val next = xorShift32(seed)
    val cells = IntArray(64)
    repeat(36) {
        val tx = (next() % 4).toInt()
        val ty = (next() % 2).toInt()
        val x = (next() % (16 - tx)).toInt()
        val y = (next() % (4 - ty)).toInt()
        val w = (next() % 4).toInt() + 1
        val h = (next() % 2).toInt() + 1
        val qr = (next() % 4).toInt()
        val qg = (next() % 4).toInt()
        val qb = (next() % 4).toInt()
        val cell = (qr shl 4) or (qg shl 2) or qb
        for (dy in 0 until h) {
            val py = ty + y + dy
            if (py !in 0 until 4) continue
            for (dx in 0 until w) {
                val px = tx + x + dx
                if (px in 0 until 16) {
                    cells[py * 16 + px] = cell
                }
            }
        }
    }
    var acc = 0x811C9DC5L
    for (p in 0 until 64) {
        acc = (((acc * 1664525L) and V10_UINT32) + cells[p].toLong() + 1013904223L) and V10_UINT32
    }
    return acc
}

internal fun unshuffle32(bytes: ByteArray, seed: Long): ByteArray {
    if (bytes.size < 32) return bytes
    val next = xorShift32(seed)
    val perm = IntArray(32) { it }
    for (i in 31 downTo 1) {
        val j = (next() % (i + 1)).toInt()
        perm[i] = perm[j].also { perm[j] = perm[i] }
    }
    val chunk = bytes.size / 32
    val out = ByteArray(bytes.size)
    for (i in 0 until 32) {
        bytes.copyInto(out, destinationOffset = perm[i] * chunk, startIndex = i * chunk, endIndex = (i + 1) * chunk)
    }
    if (bytes.size > 32 * chunk) {
        bytes.copyInto(out, destinationOffset = 32 * chunk, startIndex = 32 * chunk, endIndex = bytes.size)
    }
    return out
}

internal fun xorPrefix(bytes: ByteArray, seed: Long, limit: Int): ByteArray {
    val n = minOf(bytes.size, limit)
    if (n <= 0) return bytes
    val next = xorShift32(seed)
    val out = bytes.copyOf()
    for (i in 0 until n) {
        out[i] = (out[i].toInt() xor (next() and 0xFFL).toInt()).toByte()
    }
    return out
}

private val V10_JPEG_MAGIC = "ffd8ff".decodeHex()
private val V10_PNG_MAGIC = "89504e47".decodeHex()
private val V10_GIF_MAGIC = "GIF8".encodeUtf8()
private val V10_RIFF_MAGIC = "RIFF".encodeUtf8()
private val V10_WEBP_MAGIC = "WEBP".encodeUtf8()
private val V10_AVIF_MAGIC = "ftypavif".encodeUtf8()

internal fun Buffer.sniffImageType(): String? = when {
    rangeEquals(0, V10_JPEG_MAGIC) -> "image/jpeg"
    rangeEquals(0, V10_PNG_MAGIC) -> "image/png"
    rangeEquals(0, V10_GIF_MAGIC) -> "image/gif"
    rangeEquals(0, V10_RIFF_MAGIC) && rangeEquals(8, V10_WEBP_MAGIC) -> "image/webp"
    rangeEquals(4, V10_AVIF_MAGIC) -> "image/avif"
    else -> null
}

private fun String.decodeBase64Bytes(): ByteArray = decodeBase64()?.toByteArray() ?: error("Invalid base64 payload")

private fun sha256Hex(text: String): String = text.sha256().toHexString()
