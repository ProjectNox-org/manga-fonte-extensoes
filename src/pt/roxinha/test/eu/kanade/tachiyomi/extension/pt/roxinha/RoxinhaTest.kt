package eu.kanade.tachiyomi.extension.pt.roxinha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoxinhaTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun resolveUrlHandlesRelativeAndAbsoluteUrls() {
        assertEquals(
            "https://roxinha.online/uploads/covers/a.webp",
            resolveUrl("https://roxinha.online", "/uploads/covers/a.webp"),
        )
        assertEquals(
            "https://roxinha.online/uploads/pages/1.webp",
            resolveUrl("https://roxinha.online/", "uploads/pages/1.webp"),
        )
        assertEquals(
            "https://cdn.roxinha.online/img/1.webp",
            resolveUrl("https://roxinha.online", "https://cdn.roxinha.online/img/1.webp"),
        )
    }

    @Test
    fun chapterDetailsDtoFiltersBlankAndResolvesPages() {
        val raw = """{"pages":["/uploads/1.webp","","https://cdn.roxinha.online/2.webp"]}"""
        val pages = json.decodeFromString<ChapterDetailsDto>(raw).toPages("https://roxinha.online")

        assertEquals(2, pages.size)
        assertEquals("https://roxinha.online/uploads/1.webp", pages[0].imageUrl)
        assertEquals("https://cdn.roxinha.online/2.webp", pages[1].imageUrl)
    }

    @Test
    fun errorDtoParsesOfficialClientRequiredAndTokenErrors() {
        val clientErr = json.decodeFromString<ErrorDto>(
            """{"error":"Acesso negado. Use o site ou o app oficial Roxinha.","code":"OFFICIAL_CLIENT_REQUIRED"}""",
        )
        assertEquals("OFFICIAL_CLIENT_REQUIRED", clientErr.code)
        assertFalse(isTokenErrorMessage(clientErr.error))

        val tokenErr = json.decodeFromString<ErrorDto>("""{"error":"Token inválido ou expirado"}""")
        assertTrue(isTokenErrorMessage(tokenErr.error))
        assertTrue(isTokenErrorMessage("Usuário inativo ou não encontrado"))
        assertTrue(isTokenErrorMessage("Token não fornecido"))
        assertFalse(isTokenErrorMessage("Origem não autorizada."))
    }

    @Test
    fun searchAndTicketDtoParseRealApiShape() {
        val searchJson = """
            {
              "mangas": [
                {
                  "id": 5139,
                  "title": "Mate Meu Marido",
                  "cover": "/uploads/covers/cover_1795229257979.webp",
                  "status": "ongoing",
                  "genres": "Drama, Romance",
                  "chapters": [
                    {
                      "id": 347564,
                      "chapterNumber": 1.0,
                      "title": "Capítulo 1",
                      "createdAt": "2026-11-22T22:03:50.000Z"
                    }
                  ]
                }
              ],
              "hasMore": true
            }
        """.trimIndent()
        val searchDto = json.decodeFromString<SearchResponseDto>(searchJson)
        assertTrue(searchDto.hasMore)

        val ticketDto = json.decodeFromString<TicketDto>("""{"ticket":"abc123"}""")
        assertEquals("abc123", ticketDto.ticket)
    }
}
