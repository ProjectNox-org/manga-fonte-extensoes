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
 * Decodes the dynamic anti-scraping configuration embedded on `#xyaoi-cards-container`.
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
        private val INLINE_CFG_REGEX = Regex("""(?:_xyCfg|var\s+[A-Za-z_$][\w$]*)\s*=\s*(\[[^\]]+\])""")
        private val JS_STRING_ARG_REGEX = Regex("""["'](xy\d+\$[^"']{16,}|[A-Za-z0-9+/=_-]{32,})["']""")

        fun fromContainer(container: Element?): LibraryConfig? {
            if (container == null) return null
            return container.attributes()
                .filter { it.key.startsWith("data-") && it.value.isNotBlank() }
                .sortedByDescending { it.key.startsWith("data-xy") }
                .firstNotNullOfOrNull { attr -> decodeArrayConfig(attr.value) }
        }

        fun fromDocument(document: Document): LibraryConfig? {
            fromContainer(document.selectFirst("#xyaoi-cards-container"))?.let { return it }

            val fallbackContainers = document.select(".xyaoi-cards-grid, [id*=cards-container], [class*=cards-grid]")
            fallbackContainers.firstNotNullOfOrNull(::fromContainer)?.let { return it }

            document.select("[data-xy-o], [data-xy-q], [class*=cards] [data-xy], [data-xy]")
                .firstNotNullOfOrNull(::fromContainer)
                ?.let { return it }

            // Fallback: inspect any element carrying a `data-xy-*` attribute before full document scan.
            for (el in document.allElements) {
                val xyAttr = el.attributes().firstOrNull { it.key.startsWith("data-xy") && it.value.isNotBlank() }
                if (xyAttr != null) {
                    decodeArrayConfig(xyAttr.value)?.let { return it }
                }
            }

            // Fallback: inline script embedding `_xyCfg = [...]` or encoded config literal.
            for (script in document.select("script:not([src])")) {
                val data = script.data()
                if (!data.contains("_xyCfg") && !data.contains("xyaoi_library_filter") && !data.contains("xy2$")) continue
                INLINE_CFG_REGEX.findAll(data).firstNotNullOfOrNull { match ->
                    parseConfigArray(match.groupValues[1])
                }?.let { return it }
                JS_STRING_ARG_REGEX.findAll(data).firstNotNullOfOrNull { match ->
                    decodeArrayConfig(match.groupValues[1])
                }?.let { return it }
            }
            return null
        }

        fun diagnose(document: Document): String {
            val text = document.text()
            val html = document.html()
            return when {
                html.contains("_cf_chl_opt") || html.contains("cf-turnstile") || html.contains("/cdn-cgi/challenge-platform") ->
                    "cloudflare-challenge"
                document.selectFirst("#xyaoi-noscript-wall") != null && document.selectFirst("#xyaoi-cards-container") == null ->
                    "noscript-wall"
                text.isBlank() -> "empty-document"
                document.selectFirst("#xyaoi-cards-container, .xyaoi-cards-grid") == null ->
                    "missing-container"
                else -> "config-parse-failed"
            }
        }

        private fun decodeArrayConfig(raw: String): LibraryConfig? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith('[')) {
                parseConfigArray(trimmed)?.let { return it }
            }
            val candidates = buildList {
                add(trimmed)
                stripPayloadPrefix(trimmed)?.let(::add)
            }.distinct()
            for (candidate in candidates) {
                decodeReversedBase64Safe(candidate)?.let(::parseConfigArray)?.let { return it }
                decodePlainBase64Safe(candidate)?.let(::parseConfigArray)?.let { return it }
            }
            return null
        }

        private fun parseConfigArray(raw: String): LibraryConfig? = parseOrNull {
            val array = raw.trim().parseAs<JsonArray>()
            if (array.size >= 10 &&
                array[0] is JsonPrimitive &&
                array[1] is JsonPrimitive &&
                array[2] is JsonPrimitive &&
                array[3].let { it is JsonPrimitive && it.intOrNull != null } &&
                array[8] is JsonPrimitive &&
                array[9] is JsonPrimitive
            ) {
                LibraryConfig(array).takeIf(LibraryConfig::isUsable)
            } else {
                null
            }
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
                parseOrNull {
                    val decoded = attr.value.decodeReversedBase64()
                    val array = decoded.parseAs<JsonArray>()
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
                parseOrNull { attr.value.decodeReversedBase64().trim() }
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith('/') }
            }
    }
}

@Serializable
class Genre(val name: String, val slug: String)

/**
 * Decoder for the `script[type=application/json]` / `script[type=text/x-xy-data]` inline reader format.
 */
class ReaderData(
    private val key: String,
    private val payload: List<String>,
    private val offset: Int,
    private val shift: Int,
    private val mods: List<Int>,
    private val xorMask: Int = 0,
) {
    fun toPageUrls(): List<String> = payload.drop(offset).map { entry ->
        val reversed = Base64.getDecoder().decode(entry.filterNot(Char::isWhitespace)).reversedArray()
        val bytes = ByteArray(reversed.size) { i ->
            val shifted = (reversed[i].toInt() and 0xFF) - shift - mods.sumOf { i % it }
            ((shifted and 0xFF) xor key[i % key.length].code xor xorMask).toByte()
        }
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    companion object {
        private val TRANSFORM_REGEX = Regex("""(?:charCodeAt|codePointAt)\(\s*([A-Za-z_$][\w$]*)\s*\)(?:\s*\+\s*256)?(.*?)\)\s*(?:&\s*255|%\s*256)""")
        private val XOR_AFTER_REGEX = Regex("""[A-Za-z_$][\w$]*\.(?:charCodeAt|codePointAt)\([^)]+\)\s*\^\s*(0[xX][0-9a-fA-F]+|\d+)""")
        private val XOR_BEFORE_REGEX = Regex("""(0[xX][0-9a-fA-F]+|\d+)\s*\^\s*[A-Za-z_$][\w$]*\.(?:charCodeAt|codePointAt)\([^)]+\)""")
        private val KEY_FIELD_REGEX = Regex("""[A-Za-z_$][\w$]*key[\w$]*\s*=\s*[A-Za-z_$][\w$]*\s*\[\s*["']([^"']+)["']\s*]""", RegexOption.IGNORE_CASE)
        private val SLICE_FIELDS_REGEX = Regex("""([A-Za-z_$][\w$]*)\s*\[\s*["']([^"']+)["']\s*]\s*\.slice\(\s*\1\s*\[\s*["']([^"']+)["']\s*]\s*\)""")
        private val TERM_REGEX = Regex("""-\s*(0[xX][0-9a-fA-F]+|[A-Za-z0-9_$]+)""")

        private fun parseNumericLiteral(token: String): Int? = if (token.startsWith("0x", ignoreCase = true)) {
            token.drop(2).toIntOrNull(16)
        } else {
            token.toIntOrNull()
        }

        fun from(document: Document): ReaderData? {
            val html = document.html()
            val transformMatch = TRANSFORM_REGEX.find(html) ?: return null
            val loopVar = Regex.escape(transformMatch.groupValues[1])
            val transform = transformMatch.groupValues[2]
            val modRegex = Regex("""\(\s*$loopVar\s*%\s*(\d+)\s*\)""")
            val shift = TERM_REGEX.findAll(modRegex.replace(transform, "")).sumOf { term ->
                val token = term.groupValues[1]
                parseNumericLiteral(token)
                    ?: Regex("""(?:var|let|const)\s+${Regex.escape(token)}\s*=\s*(0[xX][0-9a-fA-F]+|\d+)""")
                        .find(html)?.groupValues?.get(1)?.let(::parseNumericLiteral)
                    ?: return null
            }
            val mods = modRegex.findAll(transform).mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { m -> m > 0 } }.toList()
            val xorMask = (
                XOR_AFTER_REGEX.find(html)?.groupValues?.get(1)
                    ?: XOR_BEFORE_REGEX.find(html)?.groupValues?.get(1)
                )?.let(::parseNumericLiteral) ?: 0
            val keyField = KEY_FIELD_REGEX.find(html)?.groupValues?.get(1)
            val sliceMatch = SLICE_FIELDS_REGEX.find(html)
            val payloadField = sliceMatch?.groupValues?.get(2)
            val offsetField = sliceMatch?.groupValues?.get(3)

            return document.select("script[type=application/json], script[type=text/x-xy-data]")
                .firstNotNullOfOrNull { script ->
                    val jsonObject = parsePayloadObject(script.data()) ?: return@firstNotNullOfOrNull null
                    val key = (keyField?.let { jsonObject[it] as? JsonPrimitive }?.takeIf(JsonPrimitive::isString)?.content)
                        ?.takeIf(String::isNotEmpty)
                        ?: jsonObject.values
                            .filterIsInstance<JsonPrimitive>()
                            .filter(JsonPrimitive::isString)
                            .map(JsonPrimitive::content)
                            .filter(String::isNotEmpty)
                            .maxByOrNull(String::length)
                        ?: return@firstNotNullOfOrNull null
                    val payload = (payloadField?.let { jsonObject[it] as? JsonArray })
                        ?: jsonObject.values.filterIsInstance<JsonArray>().maxByOrNull(JsonArray::size)
                        ?: return@firstNotNullOfOrNull null
                    val offset = (offsetField?.let { (jsonObject[it] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull })
                        ?: jsonObject.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull }
                        ?: return@firstNotNullOfOrNull null
                    parseOrNull {
                        ReaderData(
                            key = key,
                            payload = payload.map { it.string },
                            offset = offset,
                            shift = shift,
                            mods = mods,
                            xorMask = xorMask,
                        ).takeIf { it.toPageUrls().isNotEmpty() }
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
                decodeReversedBase64Safe(compact.reversed())?.let { parseOrNull { it.reversed().parseAs<JsonElement>() } }
                    ?: parseOrNull { decodeBase64Flexible(compact.reversed()).decodeToString().parseAs<JsonElement>() }
                    ?: decodeReversedBase64Safe(compact)?.let { parseOrNull { it.parseAs<JsonElement>() } }
                    ?: decodePlainBase64Safe(compact)?.let { parseOrNull { it.parseAs<JsonElement>() } }
                ) as? JsonObject
        }
    }
}

private val PAYLOAD_PREFIX_REGEX = Regex("""^[A-Za-z0-9_-]{1,24}\$(.+)$""", RegexOption.DOT_MATCHES_ALL)

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

internal fun decodePlainBase64Safe(raw: String): String? = parseOrNull {
    decodeBase64Flexible(raw).decodeToString()
}

private inline fun <T> parseOrNull(block: () -> T): T? = try {
    block()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Decodes `atob(value).split('').reverse().join('')` strings used by `3xyaoi.com`,
 * stripping optional versioned prefixes such as `xy2$`.
 */
fun String.decodeReversedBase64(): String {
    val payload = stripPayloadPrefix(this) ?: this
    return decodeBase64Flexible(payload).decodeToString().reversed()
}
