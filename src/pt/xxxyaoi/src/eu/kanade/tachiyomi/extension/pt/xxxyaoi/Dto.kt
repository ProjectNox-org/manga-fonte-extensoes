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
class LibraryResponse(val data: LibraryData)

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
        fun fromContainer(container: Element?): LibraryConfig? {
            if (container == null) return null
            return container.attributes()
                .filter { it.key.startsWith("data-") && it.value.isNotBlank() }
                .firstNotNullOfOrNull { attr ->
                    parseOrNull {
                        val decoded = attr.value.decodeReversedBase64()
                        val array = decoded.parseAs<JsonArray>()
                        if (array.size >= 10) LibraryConfig(array) else null
                    }
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
                    val array = attr.value.decodeReversedBase64().parseAs<JsonArray>()
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
        private val TRANSFORM_REGEX = Regex("""(?:charCodeAt|codePointAt)\(\s*i\s*\)(?:\s*\+\s*256)?(.*?)\)\s*(?:&\s*255|%\s*256)""")
        private val XOR_REGEX = Regex("""_key\.charCodeAt\([^)]+\)\s*\^\s*(\d+)""")
        private val KEY_FIELD_REGEX = Regex("""_key\s*=\s*\w+\s*\[\s*["']([^"']+)["']\s*]""")
        private val SLICE_FIELDS_REGEX = Regex("""\w+\s*\[\s*["']([^"']+)["']\s*]\s*\.slice\(\s*\w+\s*\[\s*["']([^"']+)["']\s*]\s*\)""")
        private val MOD_REGEX = Regex("""\(\s*i\s*%\s*(\d+)\s*\)""")
        private val TERM_REGEX = Regex("""-\s*(\w+)""")

        fun from(document: Document): ReaderData? {
            val html = document.html()
            val transform = TRANSFORM_REGEX.find(html)?.groupValues?.get(1) ?: return null
            val shift = TERM_REGEX.findAll(MOD_REGEX.replace(transform, "")).sumOf { term ->
                val token = term.groupValues[1]
                token.toIntOrNull()
                    ?: Regex("""(?:var|let|const)\s+$token\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return null
            }
            val mods = MOD_REGEX.findAll(transform).map { it.groupValues[1].toInt() }.toList()
            val xorMask = XOR_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val keyField = KEY_FIELD_REGEX.find(html)?.groupValues?.get(1)
            val sliceMatch = SLICE_FIELDS_REGEX.find(html)
            val payloadField = sliceMatch?.groupValues?.get(1)
            val offsetField = sliceMatch?.groupValues?.get(2)

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
            val compact = trimmed.filterNot(Char::isWhitespace)
            return (
                parseOrNull { Base64.getDecoder().decode(compact.reversed()).decodeToString().parseAs<JsonElement>() }
                    ?: parseOrNull { compact.decodeReversedBase64().parseAs<JsonElement>() }
                ) as? JsonObject
        }
    }
}

private inline fun <T> parseOrNull(block: () -> T): T? = try {
    block()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Decodes `atob(value).split('').reverse().join('')` strings used by `3xyaoi.com`.
 */
fun String.decodeReversedBase64(): String = Base64.getDecoder().decode(filterNot(Char::isWhitespace)).decodeToString().reversed()
