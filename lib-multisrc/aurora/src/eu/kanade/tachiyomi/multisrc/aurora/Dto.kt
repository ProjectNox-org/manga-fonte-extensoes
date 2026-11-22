package eu.kanade.tachiyomi.multisrc.aurora

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.string
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

@Serializable
class SeriesDto(
    @JsonNames("updates")
    val series: List<MangaDto>,
) {
    fun toSMangaList(): List<SManga> = series.map(MangaDto::toSManga)
}

@Serializable
class MangaDto(
    @JsonNames("name")
    val title: String,
    @JsonNames("image")
    val coverUrl: String?,
    @JsonNames("url")
    val slug: String,
    var type: String? = null,
    val alternateName: String? = null,
    val description: String? = null,
    val author: Value? = null,
    val genre: List<String> = emptyList(),
) {
    fun toSManga(createManga: () -> SManga = SManga::create) = createManga().apply {
        title = this@MangaDto.title
        thumbnail_url = coverUrl
        description = MarkdownDescription.Builder()
            .description(this@MangaDto.description)
            .altTitles(listOfNotNull(alternateName), "Títulos alternativos")
            .build()
        author = this@MangaDto.author?.name
        genre = this@MangaDto.genre.joinToString()
        val location = slug.toHttpUrlOrNull()
        url = location?.pathSegments?.last() ?: slug

        memo = buildJsonObject {
            put("slug", url)
            put("type", (location?.pathSegments?.first() ?: type)?.lowercase())
        }
    }
}

@Serializable
class Value(val name: String)

@Serializable
class ChapterListDto(
    @JsonNames("slug")
    val serieSlug: String,
    val chapters: List<ChapterDto>,
) {
    fun toSChapterList(manga: SManga) = chapters.map { it.toSChapter(manga, serieSlug) }
}

@Serializable
class ChapterDto(
    val id: String,
    val number: String,
    val releaseDate: String,
) {
    fun toSChapter(
        manga: SManga,
        serieSlug: String,
        createChapter: () -> SChapter = SChapter::create,
    ) = createChapter().apply {
        val type = manga.memo["type"]!!.string
        name = "Capítulo $number"
        date_upload = dateFormat.tryParseDate(releaseDate).takeIf { it > 0L }
            ?: fallbackDateFormat.tryParseDate(releaseDate.replace(".", ""))
        chapter_number = number.toFloat()
        url = id
        memo = buildJsonObject {
            put("id", id)
            put("number", number)
            put("slug", serieSlug)
            put("type", type)
        }
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("d 'de' MMM 'de' yyyy", Locale.forLanguageTag("pt-BR"))
        private val fallbackDateFormat = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d 'de' ")
            .appendText(
                ChronoField.MONTH_OF_YEAR,
                mapOf(
                    1L to "jan",
                    2L to "fev",
                    3L to "mar",
                    4L to "abr",
                    5L to "mai",
                    6L to "jun",
                    7L to "jul",
                    8L to "ago",
                    9L to "set",
                    10L to "out",
                    11L to "nov",
                    12L to "dez",
                ),
            )
            .appendPattern(" 'de' yyyy")
            .toFormatter(Locale.forLanguageTag("pt-BR"))
    }
}

@Serializable
class PagesDto(
    val pages: List<ImageDto>,
)

@Serializable
class ImageDto(
    val url: String,
)

@Serializable
class KeyDto(
    val k: String,
    val v: Int? = null,
    val e: Long? = null,
)
