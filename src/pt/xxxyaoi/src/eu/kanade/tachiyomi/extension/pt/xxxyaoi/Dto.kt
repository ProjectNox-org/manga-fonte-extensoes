package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import keiyoushi.utils.int
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

@Serializable
class LibraryResponse(
    val success: Boolean = true,
    val data: JsonElement? = null,
) {
    fun toLibraryData(json: kotlinx.serialization.json.Json? = null): LibraryData? {
        if (!success) return null
        val element = data as? JsonObject ?: return null
        val html = (element["html"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
        val currentPage = (element["current_page"] as? JsonPrimitive)?.intOrNull ?: 1
        val totalPages = (element["total_pages"] as? JsonPrimitive)?.intOrNull ?: currentPage
        return LibraryData(html, currentPage, totalPages)
    }
}

@Serializable
class LibraryData(
    val html: String,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("total_pages") val totalPages: Int,
)

/**
 * Decodes the dynamic anti-scraping configuration embedded on the library container or script.
 * Supports `JsonArray` (e.g. `xy2$` / unprefixed), `JsonObject` (e.g. `v3~` with `f0..c9` or
 * script-mapped custom keys), and forward/reversed Base64 encodings.
 */
class LibraryConfig(values: JsonArray) {
    private val tokenField = values[0].string
    private val token = values[1].string
    private val checksumField = values[2].string
    private val pageFactor = values[3].int
    private val searchFactor = values[4].int
    private val statusFactor = values[5].int
    private val genreFactor = values[6].int
    private val checksumBase = values[7].int
    val cookie = values[8].string to values[9].string

    fun isUsable(): Boolean = tokenField.isNotBlank() &&
        token.isNotBlank() &&
        checksumField.isNotBlank() &&
        cookie.first.isNotBlank() &&
        cookie.second.isNotBlank()

    fun formFields(page: Int, search: String, status: String, genre: String) = listOf(
        tokenField to token,
        checksumField to (
            page * pageFactor +
                search.codePointCount(0, search.length) * searchFactor +
                status.length * statusFactor +
                genre.length * genreFactor +
                checksumBase
            ).toString(),
    )

    companion object {
        private val CANONICAL_OBJECT_KEYS = listOf("f0", "f1", "f2", "n3", "n4", "n5", "n6", "n7", "c8", "c9")
        private val INLINE_CFG_REGEX = Regex("""(?:_xyCfg|(?:var|let|const)\s+[A-Za-z_$][\w$]*)\s*=\s*(\[[^\]]+\])""")
        private val SCRIPT_OBJECT_MAP_REGEX = Regex("""\b(?:_xyCfg|[A-Za-z_$][\w$]*)\s*=\s*\[([^\]]{10,600})\]""")
        private val OBJECT_PROP_ACCESS_REGEX = Regex("""([A-Za-z_$][\w$]*)\s*(?:\.\s*([A-Za-z_$][\w$]*)|\[\s*["']([^"']+)["']\s*\])""")
        private val JS_STRING_ARG_REGEX = Regex("""["']((?:xy|v)\d+[$~:|#!][^"']{16,}|[A-Za-z0-9+/=_-]{32,})["']""")
        private val PREFIXED_ATTR_REGEX = Regex("""^[A-Za-z0-9_-]{1,16}[$~:|#!].{16,}$""")
        private val VERSION_PREFIX_MARKER = Regex("""\b(?:xy|v)\d+[$~:|#!]""")

        fun fromContainer(container: Element?): LibraryConfig? = fromContainer(container, null)

        internal fun fromContainer(container: Element?, scriptKeyOrder: List<String>?): LibraryConfig? {
            if (container == null) return null
            return container.attributes()
                .filter { it.key.startsWith("data-") && it.value.isNotBlank() }
                .sortedByDescending { it.key.startsWith("data-xy") }
                .firstNotNullOfOrNull { attr -> decodeConfigValue(attr.value, scriptKeyOrder) }
        }

        fun fromDocument(document: Document): LibraryConfig? {
            val scriptKeyOrder = extractScriptKeyOrder(document)

            fromContainer(document.selectFirst("#xyaoi-cards-container"), scriptKeyOrder)?.let { return it }

            val fallbackContainers = document.select(".xyaoi-cards-grid, [id*=cards-container], [class*=cards-grid]")
            fallbackContainers.firstNotNullOfOrNull { fromContainer(it, scriptKeyOrder) }?.let { return it }

            document.select("[data-xy-o], [data-xy-q], [data-xy-l], [class*=cards] [data-xy], [data-xy]")
                .firstNotNullOfOrNull { fromContainer(it, scriptKeyOrder) }
                ?.let { return it }

            // Fallback: inspect any element carrying a `data-xy*`, version-prefixed, or encoded `data-*` payload attribute.
            for (el in document.allElements) {
                val candidates = el.attributes()
                    .filter { attr ->
                        val trimmed = attr.value.trim()
                        attr.key.startsWith("data-") &&
                            trimmed.length >= 24 &&
                            (
                                attr.key.startsWith("data-xy") ||
                                    PREFIXED_ATTR_REGEX.matches(trimmed) ||
                                    trimmed.startsWith('{') ||
                                    trimmed.startsWith('[') ||
                                    trimmed.all { ch -> ch.isLetterOrDigit() || ch in "+/=_-\r\n\t " }
                                )
                    }
                    .sortedByDescending { it.key.startsWith("data-xy") || PREFIXED_ATTR_REGEX.matches(it.value.trim()) }
                candidates.firstNotNullOfOrNull { attr ->
                    decodeConfigValue(attr.value, scriptKeyOrder)
                }?.let { return it }
            }

            // Fallback: inline script embedding config array (`[...]`) or encoded config literal.
            for (script in document.select("script:not([src])")) {
                val data = script.data()
                val looksRelevant = data.contains("_xyCfg") ||
                    data.contains("xyaoi_library_filter") ||
                    data.contains("admin-ajax.php") ||
                    VERSION_PREFIX_MARKER.containsMatchIn(data) ||
                    (data.contains("xyaoi") && data.contains("library") && data.contains("filter"))
                if (!looksRelevant) continue
                INLINE_CFG_REGEX.findAll(data).firstNotNullOfOrNull { match ->
                    parseConfigPayload(match.groupValues[1], scriptKeyOrder)
                }?.let { return it }
                JS_STRING_ARG_REGEX.findAll(data).firstNotNullOfOrNull { match ->
                    decodeConfigValue(match.groupValues[1], scriptKeyOrder)
                }?.let { return it }
            }
            return null
        }

        fun diagnose(document: Document): String {
            val text = document.text()
            val html = document.html()
            return when (CloudflareClassifier.classifyHtml(200, html)) {
                ChallengeClassification.WAF_BLOCK -> "waf-blocked"
                ChallengeClassification.INTERACTIVE_CHALLENGE,
                ChallengeClassification.CHALLENGE_REDIRECT,
                -> "cloudflare-challenge"
                ChallengeClassification.CLEAN -> when {
                    document.selectFirst("#xyaoi-noscript-wall") != null &&
                        document.selectFirst("#xyaoi-cards-container, .xyaoi-cards-grid, [id*=cards-container], [class*=cards-grid]") == null ->
                        "noscript-wall"
                    text.isBlank() -> "empty-document"
                    document.selectFirst("#xyaoi-cards-container, .xyaoi-cards-grid, [id*=cards-container], [class*=cards-grid]") == null ->
                        "missing-container"
                    else -> "config-parse-failed"
                }
            }
        }

        internal fun extractScriptKeyOrder(document: Document): List<String>? {
            for (script in document.select("script:not([src])")) {
                val data = script.data()
                if (!data.contains('[')) continue
                for (match in SCRIPT_OBJECT_MAP_REGEX.findAll(data)) {
                    val items = match.groupValues[1].split(',')
                    if (items.size != 10) continue
                    val pairs = items.mapNotNull { item ->
                        val propMatch = OBJECT_PROP_ACCESS_REGEX.find(item.trim()) ?: return@mapNotNull null
                        val receiver = propMatch.groupValues[1]
                        val prop = propMatch.groupValues[2].ifEmpty { propMatch.groupValues[3] }.trim().takeIf(String::isNotEmpty)
                            ?: return@mapNotNull null
                        receiver to prop
                    }
                    if (pairs.size == 10 && pairs.map { it.first }.distinct().size == 1) {
                        val keys = pairs.map { it.second }
                        if (keys.distinct().size == 10) return keys
                    }
                }
            }
            return null
        }

        private fun decodeConfigValue(raw: String, scriptKeyOrder: List<String>?): LibraryConfig? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
                parseConfigPayload(trimmed, scriptKeyOrder)?.let { return it }
            }
            val candidates = buildList {
                stripPayloadPrefix(trimmed)?.let(::add)
                add(trimmed)
            }.distinct()
            for (candidate in candidates) {
                // Newest format (`v3~`): reverse string BEFORE Base64 decode.
                decodeReversedPayloadBase64Safe(candidate)?.let { parseConfigPayload(it, scriptKeyOrder) }?.let { return it }
                // Legacy/unreversed Base64.
                decodePlainBase64Safe(candidate)?.let { parseConfigPayload(it, scriptKeyOrder) }?.let { return it }
                // Earlier `xy2$` / `data-xy-*` format: Base64 decode FIRST, then reverse string.
                decodeReversedBase64Safe(candidate)?.let { parseConfigPayload(it, scriptKeyOrder) }?.let { return it }
            }
            return null
        }

        private fun parseConfigPayload(raw: String, scriptKeyOrder: List<String>?): LibraryConfig? = parseOrNull {
            when (val element = raw.trim().parseAs<JsonElement>()) {
                is JsonArray -> parseArrayElement(element)
                is JsonObject -> parseObjectElement(element, scriptKeyOrder)
                else -> null
            }
        }

        private fun parseArrayElement(array: JsonArray): LibraryConfig? {
            if (array.size < 10) return null
            val stringsValid = listOf(0, 1, 2, 8, 9).all { idx ->
                array[idx].let { it is JsonPrimitive && it.isString && it.content.isNotBlank() }
            }
            val intsValid = (3..7).all { idx ->
                array[idx].let { it is JsonPrimitive && !it.isString && it.intOrNull != null }
            }
            if (!stringsValid || !intsValid) return null
            return LibraryConfig(array).takeIf(LibraryConfig::isUsable)
        }

        private fun parseObjectElement(obj: JsonObject, scriptKeyOrder: List<String>?): LibraryConfig? {
            val keyOrderCandidates = listOfNotNull(scriptKeyOrder, CANONICAL_OBJECT_KEYS)
            for (keys in keyOrderCandidates) {
                if (keys.size == 10 && keys.all { it in obj }) {
                    val mapped = JsonArray(keys.map { obj.getValue(it) })
                    parseArrayElement(mapped)?.let { return it }
                }
            }
            return null
        }
    }
}

/**
 * Decodes `[id, slug, name]` chapter row payloads stored on chapter `<li>` `data-*` attributes.
 */
class ChapterRow(values: JsonArray) {
    val slug = values[1].string
    val name = values[2].string

    companion object {
        fun fromElement(element: Element): ChapterRow? = element.attributes()
            .filter { it.key.startsWith("data-") && it.value.isNotBlank() }
            .firstNotNullOfOrNull { attr ->
                decodeChapterJsonArray(attr.value)?.let { array ->
                    if (array.size >= 3 && array[1] is JsonPrimitive && array[2] is JsonPrimitive) {
                        ChapterRow(array)
                    } else {
                        null
                    }
                }
            }

        fun decodedHref(link: Element): String? = link.attributes()
            .filter { it.key.startsWith("data-") && it.value.isNotBlank() }
            .firstNotNullOfOrNull { attr ->
                decodeAnyTextVariants(attr.value)
                    .map(String::trim)
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") || it.startsWith('/') }
            }

        private fun decodeChapterJsonArray(raw: String): JsonArray? = decodeAnyTextVariants(raw)
            .firstNotNullOfOrNull { candidate ->
                val trimmed = candidate.trim()
                if (!trimmed.startsWith('[')) return@firstNotNullOfOrNull null
                parseOrNull { trimmed.parseAs<JsonArray>() }
            }
    }
}

@Serializable
class Genre(val name: String, val slug: String)

/**
 * Decoder for obfuscated inline reader payloads (`text/plain`, `application/json`, `text/x-xy-data`,
 * `text/template`, or script-referenced data blocks) used by `3xyaoi.com`.
 */
class ReaderData(
    private val key: String,
    private val payload: List<String>,
    private val offset: Int,
    private val shiftDelta: Int,
    private val mods: List<SignedMod>,
    private val xorMask: Int = 0,
    private val entryOrder: EntryOrder = EntryOrder.BASE64_THEN_REVERSE,
) {
    data class SignedMod(val sign: Int, val modulus: Int)

    enum class EntryOrder {
        BASE64_THEN_REVERSE,
        REVERSE_THEN_BASE64,
        BASE64_ONLY,
    }

    fun toPageUrls(): List<String> = payload.drop(offset).mapNotNull { entry ->
        decodeEntry(entry, entryOrder)?.takeIf { url ->
            url.startsWith("https://") || url.startsWith("http://") || url.startsWith("//") || url.startsWith('/')
        }
    }

    private fun decodeEntry(entry: String, order: EntryOrder): String? {
        val compact = entry.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return null
        val rawBytes = parseOrNull {
            when (order) {
                EntryOrder.BASE64_THEN_REVERSE -> decodeBase64Flexible(compact).reversedArray()
                EntryOrder.REVERSE_THEN_BASE64 -> decodeBase64Flexible(compact.reversed())
                EntryOrder.BASE64_ONLY -> decodeBase64Flexible(compact)
            }
        } ?: return null
        val bytes = ByteArray(rawBytes.size) { i ->
            val modSum = mods.sumOf { it.sign * (i % it.modulus) }
            val transformed = (rawBytes[i].toInt() and 0xFF) + shiftDelta + modSum
            ((transformed and 0xFF) xor key[i % key.length].code xor xorMask).toByte()
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    companion object {
        private val TRANSFORM_REGEX = Regex("""(?:charCodeAt|codePointAt)\(\s*([A-Za-z_$][\w$]*)\s*\)(.*?)\)\s*(?:&\s*255|%\s*256)""")
        private val XOR_AFTER_REGEX = Regex("""(?:[A-Za-z_$][\w$]*(?:\s*(?:\.\s*[A-Za-z_$][\w$]*|\[\s*["'][^"']+["']\s*\]))*)\s*\.(?:charCodeAt|codePointAt)\([^)]+\)\s*\^\s*(0[xX][0-9a-fA-F]+|\d+|[A-Za-z_$][\w$]*)""")
        private val XOR_BEFORE_REGEX = Regex("""(0[xX][0-9a-fA-F]+|\d+|[A-Za-z_$][\w$]*)\s*\^\s*(?:[A-Za-z_$][\w$]*(?:\s*(?:\.\s*[A-Za-z_$][\w$]*|\[\s*["'][^"']+["']\s*\]))*)\s*\.(?:charCodeAt|codePointAt)\([^)]+\)""")
        private val XOR_KEY_VAR_REGEX = Regex("""([A-Za-z_$][\w$]*)(?:\s*(?:\.\s*([A-Za-z_$][\w$]*)|\[\s*["']([^"']+)["']\s*\]))?\s*\.(?:charCodeAt|codePointAt)\([^)]*\)""")
        private val KEY_FIELD_REGEX = Regex("""[A-Za-z_$][\w$]*key[\w$]*\s*=\s*[A-Za-z_$][\w$]*\s*(?:\[\s*["']([^"']+)["']\s*\]|\.\s*([A-Za-z_$][\w$]*))""", RegexOption.IGNORE_CASE)
        private val SLICE_FIELDS_REGEX = Regex("""([A-Za-z_$][\w$]*)\s*(?:\[\s*["']([^"']+)["']\s*\]|\.\s*([A-Za-z_$][\w$]*))\s*\.slice\(\s*\1\s*(?:\[\s*["']([^"']+)["']\s*\]|\.\s*([A-Za-z_$][\w$]*))\s*\)""")
        private val SLICE_LITERAL_REGEX = Regex("""([A-Za-z_$][\w$]*)\s*(?:\[\s*["']([^"']+)["']\s*\]|\.\s*([A-Za-z_$][\w$]*))\s*\.slice\(\s*(\d+)\s*\)""")
        private val TERM_REGEX = Regex("""([+-])\s*(0[xX][0-9a-fA-F]+|\d+|[A-Za-z_$][\w$]*)""")
        private val GET_ELEMENT_BY_ID_LITERAL = Regex("""getElementById\(\s*["']([^"']+)["']\s*\)""")
        private val GET_ELEMENT_BY_ID_CONCAT = Regex("""getElementById\(\s*([A-Za-z_$][\w$]*)\s*\+\s*["']([^"']+)["']\s*\)""")
        private val DECOY_URL_MARKER = Regex("""(?:warning_app\.jpg|http\.cat/)""", RegexOption.IGNORE_CASE)

        private fun parseNumericLiteral(token: String): Int? = if (token.startsWith("0x", ignoreCase = true)) {
            token.drop(2).toIntOrNull(16)
        } else {
            token.toIntOrNull()
        }

        private fun resolveNumericToken(token: String, scriptScope: String): Int? = parseNumericLiteral(token)
            ?: Regex("""\b(?:var|let|const)\s+(?:[^;\n]*,\s*)?${Regex.escape(token)}\s*=\s*(0[xX][0-9a-fA-F]+|\d+)\b""")
                .find(scriptScope)?.groupValues?.get(1)?.let(::parseNumericLiteral)

        fun from(document: Document): ReaderData? {
            val inlineScripts = document.select("script:not([src])").map { it.data() }
            val candidatesScripts = inlineScripts.filter {
                (it.contains("codePointAt") || it.contains("charCodeAt")) &&
                    (it.contains("& 255") || it.contains("% 256") || it.contains("256"))
            }.ifEmpty { listOf(document.html()) }

            val candidateElements = orderedPayloadElements(document, candidatesScripts)
            if (candidateElements.isEmpty()) return null

            for (scriptScope in candidatesScripts) {
                val parsed = extractTransformParams(scriptScope) ?: continue
                for (element in candidateElements) {
                    val raw = element.data().ifBlank { element.text() }
                    val jsonObject = parsePayloadObject(raw) ?: continue
                    buildReaderData(jsonObject, scriptScope, parsed)?.let { return it }
                }
            }
            return null
        }

        private class TransformParams(
            val shiftDelta: Int,
            val mods: List<SignedMod>,
            val xorMask: Int,
            val keyField: String?,
            val payloadField: String?,
            val offsetField: String?,
            val literalOffset: Int?,
            val entryOrder: EntryOrder,
        )

        private fun extractTransformParams(scriptScope: String): TransformParams? {
            val transformMatch = TRANSFORM_REGEX.find(scriptScope) ?: return null
            val loopVar = Regex.escape(transformMatch.groupValues[1])
            val rawTransform = transformMatch.groupValues[2]
            val modRegex = Regex("""([+-])\s*\(?\s*$loopVar\s*%\s*(\d+)\s*\)?""")
            val mods = modRegex.findAll(rawTransform).mapNotNull { match ->
                val mod = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val sign = if (match.groupValues[1] == "-") -1 else 1
                SignedMod(sign, mod)
            }.toList()

            val stripped = modRegex.replace(rawTransform, "")
                .replace(Regex("""\+\s*256\b"""), "")
            var shiftDelta = 0
            for (term in TERM_REGEX.findAll(stripped)) {
                val sign = if (term.groupValues[1] == "-") -1 else 1
                val value = resolveNumericToken(term.groupValues[2], scriptScope) ?: return null
                shiftDelta += sign * value
            }

            val xorToken = XOR_AFTER_REGEX.find(scriptScope)?.groupValues?.get(1)
                ?: XOR_BEFORE_REGEX.find(scriptScope)?.groupValues?.get(1)
            val xorMask = xorToken?.let { resolveNumericToken(it, scriptScope) } ?: 0

            val directKeyField = XOR_KEY_VAR_REGEX.findAll(scriptScope)
                .mapNotNull { m -> m.groupValues[2].ifEmpty { m.groupValues[3] }.takeIf(String::isNotEmpty) }
                .firstOrNull()
            val keyVarName = XOR_KEY_VAR_REGEX.findAll(scriptScope)
                .filter { it.groupValues[2].isEmpty() && it.groupValues[3].isEmpty() }
                .map { it.groupValues[1] }
                .firstOrNull { name ->
                    Regex("""\b(?:var|let|const)\s+(?:[^;\n]*,\s*)?${Regex.escape(name)}\s*=\s*[A-Za-z_$][\w$]*\s*(?:\[\s*["'][^"']+["']\s*\]|\.\s*[A-Za-z_$][\w$]*)""")
                        .containsMatchIn(scriptScope)
                }
            val keyField = directKeyField
                ?: keyVarName?.let { varName ->
                    Regex("""\b(?:var|let|const)\s+(?:[^;\n]*,\s*)?${Regex.escape(varName)}\s*=\s*[A-Za-z_$][\w$]*\s*(?:\[\s*["']([^"']+)["']\s*\]|\.\s*([A-Za-z_$][\w$]*))""")
                        .find(scriptScope)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
                }
                ?: KEY_FIELD_REGEX.find(scriptScope)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }

            val sliceMatch = SLICE_FIELDS_REGEX.find(scriptScope)
            val literalSliceMatch = if (sliceMatch == null) SLICE_LITERAL_REGEX.find(scriptScope) else null
            val entryOrder = when {
                Regex("""atob\(\s*(?:_rev|split\(['"]{2}\)\.reverse)""").containsMatchIn(scriptScope) -> EntryOrder.REVERSE_THEN_BASE64
                scriptScope.contains("reverse", ignoreCase = true) -> EntryOrder.BASE64_THEN_REVERSE
                else -> EntryOrder.BASE64_ONLY
            }

            return TransformParams(
                shiftDelta = shiftDelta,
                mods = mods,
                xorMask = xorMask,
                keyField = keyField,
                payloadField = sliceMatch?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] } }
                    ?: literalSliceMatch?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] } },
                offsetField = sliceMatch?.let { m -> m.groupValues[4].ifEmpty { m.groupValues[5] } },
                literalOffset = literalSliceMatch?.groupValues?.get(4)?.toIntOrNull(),
                entryOrder = entryOrder,
            )
        }

        private fun orderedPayloadElements(document: Document, scripts: List<String>): List<Element> {
            val referencedIds = buildList {
                for (script in scripts) {
                    GET_ELEMENT_BY_ID_LITERAL.findAll(script).forEach { add(it.groupValues[1]) }
                    GET_ELEMENT_BY_ID_CONCAT.findAll(script).forEach { match ->
                        val prefixVar = Regex.escape(match.groupValues[1])
                        val suffix = match.groupValues[2]
                        Regex("""\b(?:var|let|const)\s+$prefixVar\s*=\s*["']([^"']+)["']""")
                            .find(script)?.groupValues?.get(1)?.let { prefix -> add(prefix + suffix) }
                    }
                }
            }.distinct()

            val prioritized = referencedIds.mapNotNull(document::getElementById)
            val fallbackScripts = document.select("script[type]").filter { el ->
                val t = el.attr("type").lowercase()
                t.isNotBlank() && "javascript" !in t && "ecmascript" !in t && "module" !in t && "importmap" !in t
            }
            val nonScriptContainers = document.select("template[id], textarea[id]")
            return (prioritized + fallbackScripts + nonScriptContainers).distinct()
        }

        private fun buildReaderData(jsonObject: JsonObject, scriptScope: String, params: TransformParams): ReaderData? {
            val key = (params.keyField?.let { jsonObject[it] as? JsonPrimitive }?.takeIf(JsonPrimitive::isString)?.content)
                ?.takeIf(String::isNotEmpty)
                ?: jsonObject.values
                    .filterIsInstance<JsonPrimitive>()
                    .filter(JsonPrimitive::isString)
                    .map(JsonPrimitive::content)
                    .filter { it.length >= 8 }
                    .maxByOrNull(String::length)
                ?: return null
            val payloadArray = (params.payloadField?.let { jsonObject[it] as? JsonArray })
                ?: jsonObject.values.filterIsInstance<JsonArray>().maxByOrNull(JsonArray::size)
                ?: return null
            val entries = payloadArray.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            if (entries.isEmpty()) return null

            val explicitOffset = params.offsetField?.let {
                (jsonObject[it] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            } ?: params.literalOffset
            if (explicitOffset != null) {
                return buildValidated(key, entries, explicitOffset.coerceIn(0, entries.size), params)
            }

            // Without an explicit slice field, prefer an offset matching a decoy prefix, then fallback ints.
            val intOffsets = jsonObject.values
                .mapNotNull { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull }
                .filter { it in 1 until entries.size }
                .distinct()
            for (offset in intOffsets) {
                val candidate = buildValidated(key, entries, offset, params) ?: continue
                val prefix = ReaderData(key, entries.take(offset), 0, params.shiftDelta, params.mods, params.xorMask, params.entryOrder).toPageUrls()
                if (prefix.lastOrNull()?.let { DECOY_URL_MARKER.containsMatchIn(it) } == true) return candidate
            }
            for (offset in intOffsets + listOf(0)) {
                buildValidated(key, entries, offset, params)?.let { return it }
            }
            return null
        }

        private fun buildValidated(key: String, entries: List<String>, offset: Int, params: TransformParams): ReaderData? = parseOrNull {
            listOf(params.entryOrder, EntryOrder.BASE64_THEN_REVERSE, EntryOrder.REVERSE_THEN_BASE64, EntryOrder.BASE64_ONLY)
                .distinct()
                .firstNotNullOfOrNull { order ->
                    ReaderData(
                        key = key,
                        payload = entries,
                        offset = offset,
                        shiftDelta = params.shiftDelta,
                        mods = params.mods,
                        xorMask = params.xorMask,
                        entryOrder = order,
                    ).takeIf { data ->
                        val urls = data.toPageUrls()
                        urls.isNotEmpty() && urls.none { DECOY_URL_MARKER.containsMatchIn(it) }
                    }
                }
        }

        private fun parsePayloadObject(raw: String): JsonObject? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith('{')) {
                (parseOrNull { trimmed.parseAs<JsonElement>() } as? JsonObject)?.let { return it }
            }
            val compact = (stripPayloadPrefix(trimmed) ?: trimmed).filterNot(Char::isWhitespace)
            return (
                decodeReversedPayloadBase64Safe(compact)?.let { parseOrNull { it.parseAs<JsonElement>() } }
                    ?: decodeReversedBase64Safe(compact.reversed())?.let { parseOrNull { it.reversed().parseAs<JsonElement>() } }
                    ?: parseOrNull { decodeBase64Flexible(compact.reversed()).decodeToString().parseAs<JsonElement>() }
                    ?: decodeReversedBase64Safe(compact)?.let { parseOrNull { it.parseAs<JsonElement>() } }
                    ?: decodePlainBase64Safe(compact)?.let { parseOrNull { it.parseAs<JsonElement>() } }
                ) as? JsonObject
        }
    }
}

private val PAYLOAD_PREFIX_REGEX = Regex("""^[A-Za-z0-9_-]{1,24}[$~:|#!](.+)$""", RegexOption.DOT_MATCHES_ALL)

internal fun stripPayloadPrefix(value: String): String? = PAYLOAD_PREFIX_REGEX.matchEntire(value.trim())?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)

internal fun decodeBase64Flexible(raw: String): ByteArray {
    val compact = raw.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
    val rem = compact.length % 4
    val padded = if (rem == 2 || rem == 3) compact + "=".repeat(4 - rem) else compact
    return Base64.getDecoder().decode(padded)
}

internal fun decodeReversedBase64Safe(raw: String): String? = parseOrNull {
    decodeBase64Flexible(raw).decodeToString().reversed()
}

internal fun decodeReversedPayloadBase64Safe(raw: String): String? = parseOrNull {
    decodeBase64Flexible(raw.reversed()).decodeToString()
}

internal fun decodePlainBase64Safe(raw: String): String? = parseOrNull {
    decodeBase64Flexible(raw).decodeToString()
}

internal fun decodeAnyTextVariants(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    val candidates = listOfNotNull(stripPayloadPrefix(trimmed), trimmed).distinct()
    return buildList {
        for (candidate in candidates) {
            if (candidate.startsWith('[') || candidate.startsWith('{') || candidate.startsWith("http") || candidate.startsWith('/')) {
                add(candidate)
            }
            decodeReversedBase64Safe(candidate)?.let(::add)
            decodeReversedPayloadBase64Safe(candidate)?.let(::add)
            decodePlainBase64Safe(candidate)?.let(::add)
        }
    }.distinct()
}

private inline fun <T> parseOrNull(block: () -> T): T? = try {
    block()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Decodes `atob(value).split('').reverse().join('')` or prefix-wrapped Base64 strings used by
 * `3xyaoi.com`, stripping optional versioned prefixes such as `xy2$` or `v3~`.
 */
fun String.decodeReversedBase64(): String {
    val payload = stripPayloadPrefix(this) ?: this
    return decodeBase64Flexible(payload).decodeToString().reversed()
}
