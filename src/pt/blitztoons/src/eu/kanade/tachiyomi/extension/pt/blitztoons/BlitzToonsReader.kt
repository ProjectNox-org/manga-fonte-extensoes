package eu.kanade.tachiyomi.extension.pt.blitztoons

import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.network.call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val DEFAULT_PAGES_HMAC_KEY = "WwjYZS9WcWwif89zgvfFdQlPfwS_vfOE"
internal const val DATA_URI_PROXY_HOST = "inline-data.blitztoons.invalid"
internal const val DATA_URI_HEADER = "X-Blitz-Data-Uri"

private val readerJson = Json { ignoreUnknownKeys = true }

private val LAZY_IMAGE_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "data-url")
private val SRCSET_ATTRS = listOf("data-srcset", "srcset")

private val RASTER_DATA_URI_REGEX = Regex(
    """^data:(image/(?:png|jpe?g|webp|avif|gif));base64,([A-Za-z0-9+/=\s]+)$""",
    RegexOption.IGNORE_CASE,
)

private val KNOWN_PLACEHOLDER_PATH_SNIPPETS = listOf(
    "/static/img/cover-fallback.svg",
    "/static/img/icone-",
    "cover-fallback.svg",
)

private val READER_JS_KEY_PATTERNS = listOf(
    Regex("""[,;\s]([A-Za-z_${'$'}][\w${'$'}]*)\s*=\s*"([^"]{16,})".*?importKey\(\s*"raw"\s*,[^)]*\b\1\b"""),
    Regex("""importKey\(\s*"raw"\s*,\s*[^)]*?encode\(\s*"([^"]{16,})"\s*\)"""),
)

internal data class BlitzPagesApiDescriptor(
    val slug: String,
    val chapterNum: String,
    val token: String,
    val readerScriptUrl: String?,
)

internal sealed interface ImageUrlValidation {
    data class HttpImage(val url: String) : ImageUrlValidation
    data class InlineRasterData(val dataUri: String, val mimeType: String, val bytes: ByteArray) : ImageUrlValidation
    data class Rejected(val reason: String) : ImageUrlValidation
}

/**
 * Serves legitimate embedded raster `data:image/...;base64,...` pages locally without
 * passing a `data:` scheme into OkHttp's HTTP URL builder.
 */
internal class BlitzDataUriInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != DATA_URI_PROXY_HOST) {
            return chain.proceed(request)
        }

        val rawDataUri = request.header(DATA_URI_HEADER).orEmpty()
        return when (val validated = classifyImageUrl(rawDataUri)) {
            is ImageUrlValidation.InlineRasterData -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(validated.bytes.toResponseBody(validated.mimeType.toMediaTypeOrNull()))
                .build()
            else -> throw IOException("URI de imagem inline inválida na BlitzToons.")
        }
    }
}

internal fun signBlitzPageToken(pt: String, hmacKey: String = DEFAULT_PAGES_HMAC_KEY): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(hmacKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal("p|$pt".toByteArray(StandardCharsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun extractReaderJsHmacKey(scriptText: String): String? {
    for (pattern in READER_JS_KEY_PATTERNS) {
        val match = pattern.find(scriptText) ?: continue
        val key = match.groupValues.lastOrNull()?.trim()
        if (!key.isNullOrBlank()) return key
    }
    return null
}

internal fun extractPagesApiDescriptor(doc: Document, chapterUrl: String): BlitzPagesApiDescriptor? {
    val block = doc.selectFirst("section.ch-block[data-pt], [data-block][data-pt]") ?: return null
    val token = block.attr("data-pt").trim()
    if (token.isBlank()) return null

    val reader = doc.selectFirst("[data-reader]")
    val parsedChapterUrl = chapterUrl.toHttpUrlOrNull()
    val pathSegments = parsedChapterUrl?.pathSegments.orEmpty().filter { it.isNotBlank() }

    val slug = reader?.attr("data-slug")?.trim()?.takeIf { it.isNotBlank() }
        ?: pathSegments.takeIf { it.size >= 2 && it[0] == "ler" }?.get(1)
        ?: return null

    val chapterNum = block.attr("data-num").trim().takeIf { it.isNotBlank() }
        ?: reader?.attr("data-num")?.trim()?.takeIf { it.isNotBlank() }
        ?: pathSegments.takeIf { it.size >= 3 && it[0] == "ler" }?.get(2)
        ?: return null

    val scriptEl = doc.selectFirst("script[src*=/reader.js], script[src*=reader.js]")
    val readerScriptUrl = scriptEl?.let { resolveRelativeUrl(it.attr("src"), doc.baseUri().ifBlank { chapterUrl }) }

    return BlitzPagesApiDescriptor(
        slug = slug,
        chapterNum = chapterNum,
        token = token,
        readerScriptUrl = readerScriptUrl,
    )
}

internal fun buildPagesApiUrl(baseUrl: String, descriptor: BlitzPagesApiDescriptor): String {
    val encodedSlug = encodePathSegment(descriptor.slug)
    val encodedNum = encodePathSegment(descriptor.chapterNum)
    return "$baseUrl/api/paginas/$encodedSlug/$encodedNum".toHttpUrlOrNull()!!
        .newBuilder()
        .addQueryParameter("t", descriptor.token)
        .build()
        .toString()
}

internal fun parsePagesApiResponse(jsonBody: String, baseUrl: String): List<Page> {
    val root = runCatching { readerJson.parseToJsonElement(jsonBody).jsonObject }.getOrNull() ?: return emptyList()
    val rawArray = findPagesArray(root) ?: return emptyList()

    val resolvedUrls = rawArray.mapNotNull { entry ->
        val rawCandidate = when (entry) {
            is JsonPrimitive -> entry.contentOrNull
            is JsonObject -> entry["u"]?.jsonPrimitive?.contentOrNull
                ?: entry["url"]?.jsonPrimitive?.contentOrNull
                ?: entry["src"]?.jsonPrimitive?.contentOrNull
            else -> null
        } ?: return@mapNotNull null

        resolveAndFilterCandidate(rawCandidate, baseUrl)
    }

    return buildOrderedPages(resolvedUrls)
}

private fun findPagesArray(root: JsonObject): JsonArray? = root["paginas"]?.let { runCatching { it.jsonArray }.getOrNull() }
    ?: root["pages"]?.let { runCatching { it.jsonArray }.getOrNull() }

internal suspend fun fetchDynamicPages(
    client: OkHttpClient,
    baseUrl: String,
    chapterUrl: String,
    baseHeaders: Headers,
    descriptor: BlitzPagesApiDescriptor,
    cachedKey: String,
    onKeyUpdated: (String) -> Unit,
): List<Page> {
    val firstAttempt = executePagesApiCall(
        client = client,
        baseUrl = baseUrl,
        chapterUrl = chapterUrl,
        baseHeaders = baseHeaders,
        descriptor = descriptor,
        hmacKey = cachedKey,
    )

    if (firstAttempt.pages.isNotEmpty()) {
        return firstAttempt.pages
    }

    if (firstAttempt.statusCode in listOf(401, 403) && !descriptor.readerScriptUrl.isNullOrBlank()) {
        val refreshedKey = fetchKeyFromReaderScript(client, descriptor.readerScriptUrl, chapterUrl, baseHeaders)
        if (!refreshedKey.isNullOrBlank() && refreshedKey != cachedKey) {
            onKeyUpdated(refreshedKey)
            val retryAttempt = executePagesApiCall(
                client = client,
                baseUrl = baseUrl,
                chapterUrl = chapterUrl,
                baseHeaders = baseHeaders,
                descriptor = descriptor,
                hmacKey = refreshedKey,
            )
            if (retryAttempt.pages.isNotEmpty()) {
                return retryAttempt.pages
            }
        }
    }

    if (firstAttempt.statusCode !in 200..299 && firstAttempt.statusCode > 0) {
        throw IOException("BlitzToons: falha HTTP ${firstAttempt.statusCode} ao buscar páginas do capítulo.")
    }

    return emptyList()
}

private data class PagesApiCallResult(
    val statusCode: Int,
    val pages: List<Page>,
)

private suspend fun executePagesApiCall(
    client: OkHttpClient,
    baseUrl: String,
    chapterUrl: String,
    baseHeaders: Headers,
    descriptor: BlitzPagesApiDescriptor,
    hmacKey: String,
): PagesApiCallResult {
    val apiUrl = buildPagesApiUrl(baseUrl, descriptor)
    val signature = signBlitzPageToken(descriptor.token, hmacKey)
    val apiHeaders = baseHeaders.newBuilder()
        .set("Accept", "application/json")
        .set("Referer", chapterUrl)
        .set("X-BT-P", signature)
        .removeAll("Origin")
        .build()

    val request = Request.Builder()
        .url(apiUrl)
        .headers(apiHeaders)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .get()
        .build()

    client.call(request, ensureSuccess = false).use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            return PagesApiCallResult(response.code, emptyList())
        }
        return PagesApiCallResult(response.code, parsePagesApiResponse(body, baseUrl))
    }
}

private suspend fun fetchKeyFromReaderScript(
    client: OkHttpClient,
    scriptUrl: String,
    chapterUrl: String,
    baseHeaders: Headers,
): String? {
    val validated = classifyImageUrl(scriptUrl) as? ImageUrlValidation.HttpImage ?: return null
    val headers = baseHeaders.newBuilder()
        .set("Referer", chapterUrl)
        .removeAll("Origin")
        .build()
    val request = Request.Builder()
        .url(validated.url)
        .headers(headers)
        .get()
        .build()

    return runCatching {
        client.call(request, ensureSuccess = false).use { response ->
            if (!response.isSuccessful) return@use null
            extractReaderJsHmacKey(response.body.string())
        }
    }.getOrNull()
}

internal fun parsePageList(doc: Document): List<Page> {
    val baseUri = doc.baseUri()
    val containerSelectors = "div.chapters, section.ch-block, div.pages"
    val containers = doc.select(containerSelectors)
    val rootScope = if (containers.isNotEmpty()) containers else doc.select("img.page").parents().ifEmpty { listOf(doc.body()) }

    val extractedUrls = mutableListOf<String>()
    val seenElements = mutableSetOf<Element>()

    for (container in rootScope) {
        for (slot in container.select("picture, img, noscript")) {
            if (!seenElements.add(slot)) continue

            val candidate = when (slot.tagName()) {
                "picture" -> extractUrlFromPicture(slot, baseUri)
                "noscript" -> extractUrlsFromNoscript(slot, baseUri).firstOrNull()
                "img" -> {
                    if (slot.parents().any { it.tagName() == "picture" }) {
                        null
                    } else {
                        extractUrlFromImageElement(slot, baseUri)
                    }
                }
                else -> null
            }

            if (!candidate.isNullOrBlank()) {
                extractedUrls.add(candidate)
            }
        }
    }

    return buildOrderedPages(extractedUrls)
}

private fun extractUrlFromPicture(picture: Element, baseUri: String): String? {
    picture.selectFirst("img")?.let { img ->
        extractUrlFromLazyAttrsAndSrcset(img, baseUri)?.let { return it }
    }

    for (source in picture.select("source")) {
        for (attr in SRCSET_ATTRS) {
            val srcset = source.attr(attr).trim()
            if (srcset.isNotEmpty()) {
                pickSrcsetCandidate(srcset, baseUri)?.let { return it }
            }
        }
    }

    picture.selectFirst("img")?.let { img ->
        val src = img.attr("src").trim()
        if (src.isNotEmpty()) {
            resolveAndFilterCandidate(src, baseUri)?.let { return it }
        }
    }

    return null
}

private fun extractUrlsFromNoscript(noscript: Element, baseUri: String): List<String> {
    val fragment = Jsoup.parseBodyFragment(noscript.html(), baseUri)
    return fragment.select("picture, img").mapNotNull { el ->
        when (el.tagName()) {
            "picture" -> extractUrlFromPicture(el, baseUri)
            "img" -> if (el.parents().any { it.tagName() == "picture" }) null else extractUrlFromImageElement(el, baseUri)
            else -> null
        }
    }
}

private fun extractUrlFromImageElement(img: Element, baseUri: String): String? {
    if (img.hasClass("brand-mark")) return null

    extractUrlFromLazyAttrsAndSrcset(img, baseUri)?.let { return it }

    val src = img.attr("src").trim()
    if (src.isNotEmpty()) {
        return resolveAndFilterCandidate(src, baseUri)
    }

    return null
}

private fun extractUrlFromLazyAttrsAndSrcset(img: Element, baseUri: String): String? {
    for (attr in LAZY_IMAGE_ATTRS) {
        val raw = img.attr(attr).trim()
        if (raw.isNotEmpty()) {
            resolveAndFilterCandidate(raw, baseUri)?.let { return it }
        }
    }

    for (attr in SRCSET_ATTRS) {
        val srcset = img.attr(attr).trim()
        if (srcset.isNotEmpty()) {
            pickSrcsetCandidate(srcset, baseUri)?.let { return it }
        }
    }

    return null
}

internal fun pickSrcsetCandidate(srcset: String, baseUri: String): String? {
    val candidates = srcset.split(',')
        .mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split(Regex("""\s+"""))
            val urlPart = parts[0]
            val weight = parts.getOrNull(1)?.let { descriptor ->
                when {
                    descriptor.endsWith("w", ignoreCase = true) ->
                        descriptor.dropLast(1).toDoubleOrNull() ?: 0.0
                    descriptor.endsWith("x", ignoreCase = true) ->
                        (descriptor.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000.0
                    else -> 0.0
                }
            } ?: 0.0
            val resolved = resolveAndFilterCandidate(urlPart, baseUri) ?: return@mapNotNull null
            resolved to weight
        }

    return candidates.maxByOrNull { it.second }?.first
}

internal fun resolveAndFilterCandidate(rawCandidate: String, baseUri: String): String? {
    val resolved = resolveRelativeUrl(rawCandidate, baseUri) ?: return null
    return when (classifyImageUrl(resolved)) {
        is ImageUrlValidation.HttpImage,
        is ImageUrlValidation.InlineRasterData,
        -> resolved
        is ImageUrlValidation.Rejected -> null
    }
}

internal fun resolveRelativeUrl(rawUrl: String, baseUri: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("data:", ignoreCase = true)) {
        return trimmed
    }

    if (trimmed.contains("://")) {
        return trimmed
    }

    if (trimmed.startsWith("//")) {
        val scheme = baseUri.toHttpUrlOrNull()?.scheme ?: "https"
        return "$scheme:$trimmed"
    }

    val baseHttpUrl = baseUri.toHttpUrlOrNull() ?: return null
    return baseHttpUrl.resolve(trimmed)?.toString()
}

internal fun classifyImageUrl(rawUrl: String): ImageUrlValidation {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        return ImageUrlValidation.Rejected("URL de imagem vazia")
    }

    val lower = trimmed.lowercase()
    if (lower.startsWith("data:")) {
        if (lower.startsWith("data:image/svg+xml")) {
            return ImageUrlValidation.Rejected("Placeholder SVG inline ignorado")
        }
        val match = RASTER_DATA_URI_REGEX.matchEntire(trimmed)
            ?: return ImageUrlValidation.Rejected("Data URI não suportada ou não é imagem raster base64")

        val mimeType = match.groupValues[1].lowercase()
        val payload = match.groupValues[2].replace(Regex("""\s+"""), "")
        val decoded = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
            ?: return ImageUrlValidation.Rejected("Base64 inválido em Data URI")

        // Ignore 1x1 blank GIF/PNG tracking/lazy spacers (< 64 bytes) while keeping real inline raster images.
        if (decoded.size < 64) {
            return ImageUrlValidation.Rejected("Placeholder Data URI minúsculo (${decoded.size} bytes)")
        }

        return ImageUrlValidation.InlineRasterData(
            dataUri = "data:$mimeType;base64,$payload",
            mimeType = mimeType,
            bytes = decoded,
        )
    }

    val httpUrl = trimmed.toHttpUrlOrNull()
        ?: return ImageUrlValidation.Rejected("Esquema de URL não HTTP/HTTPS: ${trimmed.substringBefore(':')}")

    if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
        return ImageUrlValidation.Rejected("Esquema de URL não permitido: ${httpUrl.scheme}")
    }

    val encodedPathLower = httpUrl.encodedPath.lowercase()
    if (KNOWN_PLACEHOLDER_PATH_SNIPPETS.any { it in encodedPathLower }) {
        return ImageUrlValidation.Rejected("Imagem placeholder estática ignorada: ${httpUrl.encodedPath}")
    }

    return ImageUrlValidation.HttpImage(httpUrl.toString())
}

private fun buildOrderedPages(urls: List<String>): List<Page> = urls.distinct().mapIndexed { index, imageUrl ->
    Page(index, imageUrl = imageUrl)
}

private fun encodePathSegment(segment: String): String = URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun buildBlitzImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
    val builder = baseHeaders?.newBuilder() ?: Headers.Builder()
    if (builder["User-Agent"] == null) {
        builder.set("User-Agent", BLITZTOONS_USER_AGENT)
    }

    val validated = classifyImageUrl(imageUrl)
    val sanitizedReferer = baseUrl.trimEnd('/') + "/"

    return when (validated) {
        is ImageUrlValidation.HttpImage -> {
            val finalHeaders = builder
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .set("Sec-Fetch-Dest", "image")
                .set("Sec-Fetch-Mode", "no-cors")
                .set("Sec-Fetch-Site", "same-site")
                .set("Referer", sanitizedReferer)
                .removeAll("Origin")
                .build()

            Request.Builder()
                .url(validated.url)
                .headers(finalHeaders)
                .get()
                .build()
        }
        is ImageUrlValidation.InlineRasterData -> {
            val finalHeaders = builder
                .set(DATA_URI_HEADER, validated.dataUri)
                .removeAll("Origin")
                .build()

            Request.Builder()
                .url("https://$DATA_URI_PROXY_HOST/image")
                .headers(finalHeaders)
                .get()
                .build()
        }
        is ImageUrlValidation.Rejected -> throw IllegalArgumentException(
            "BlitzToons: ${validated.reason}",
        )
    }
}
