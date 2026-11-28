package eu.kanade.tachiyomi.extension.id.comicaso

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CatalogMoreDto(
    val html: String,
    val hasMore: Boolean,
)

@Serializable
class GenreDto(val name: String, val value: String)

@Serializable
class MediaSourceDto(val url: String)

@Serializable
class VerificationDto(
    private val token: String,
    @SerialName("elapsed_ms") private val elapsedMs: Int,
    private val moves: Int,
    private val progress: Int,
)

@Serializable
class VerificationResultDto(val ok: Boolean?)
