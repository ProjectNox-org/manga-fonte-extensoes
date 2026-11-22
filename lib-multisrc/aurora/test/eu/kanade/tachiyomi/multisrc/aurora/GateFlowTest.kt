package eu.kanade.tachiyomi.multisrc.aurora

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Ignore
import org.junit.Test

private const val CHAPTER_URL = "https://reader.example/manga/series/54.2"
private const val CHAPTER_PATH = "/manga/series/54.2"
private const val TOKEN = "37247fa8-d40d-457f-a784-500fe4a4f1f4"
private const val LINK_URL = "https://reader.example/gate/link/abc"
private const val CALLBACK_URL = "https://reader.example/gate/callback?token=$TOKEN"

// Same flight shape as ReaderTest; only the image URLs are synthetic.
private const val CALLBACK_FLIGHT =
    """5:["$","L20",null,{"pages":[{"id":1,"url":"/pages/1","width":800,"height":1200,"isDouble":false}],"capId":"54.2"}]"""

class GateFlowTest {
    private class Stub(
        var linkStatus: Int = 200,
        var linkLocation: String? = null,
    ) {
        val urls = mutableListOf<String>()
        var linkReferer: String? = null
    }

    private fun response(request: Request, code: Int, body: String, contentType: String, location: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .apply { location?.let { header("Location", it) } }
            .body(body.toResponseBody(null))
            .header("Content-Type", contentType)
            .build()

    private fun client(stub: Stub): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            stub.urls += url.toString()
            when (url.encodedPath) {
                "/gate/link/abc" -> {
                    stub.linkReferer = request.header("Referer")
                    response(request, stub.linkStatus, "", "text/html", stub.linkLocation)
                }
                "/gate/callback" -> response(request, 200, CALLBACK_FLIGHT, "text/x-component")
                else -> response(request, 404, "", "text/plain")
            }
        }
        .build()

    private fun noRedirect(client: OkHttpClient) = client.newBuilder().followRedirects(false).build()

    private fun unlock(
        client: OkHttpClient,
        gateClient: OkHttpClient = noRedirect(client),
        gate: GateData = GateData(TOKEN, CHAPTER_PATH, 10, LINK_URL),
    ): Response = runBlocking {
        completeGate(gate, CHAPTER_URL, client, gateClient, Headers.headersOf("User-Agent", "TestAgent"), GateTiming(), {})
    }

    @Ignore("HTTP await is an android stub on the JVM test classpath (see issue #180 notes)")
    @Test
    fun gateLinkIsRequestedBeforeTheCallback() {
        val stub = Stub()
        val client = client(stub)

        unlock(client).use {
            assertEquals(listOf(LINK_URL, CALLBACK_URL), stub.urls)
            assertEquals(CHAPTER_URL, stub.linkReferer)
            assertEquals("navigate", it.request.header("Sec-Fetch-Mode"))
        }
    }

    @Ignore("HTTP await is an android stub on the JVM test classpath (see issue #180 notes)")
    @Test
    fun gateLinkRedirectsAreNotFollowed() {
        val stub = Stub(linkStatus = 302, linkLocation = "https://affiliate.example/out?target=abc")
        val client = client(stub)

        unlock(client).close()

        assertFalse(stub.urls.any { it.contains("affiliate.example") })
        assertEquals(listOf(LINK_URL, CALLBACK_URL), stub.urls)
    }

    @Ignore("HTTP await is an android stub on the JVM test classpath (see issue #180 notes)")
    @Test
    fun gateLinkFailureDoesNotAbortTheChapter() {
        val stub = Stub(linkStatus = 500)
        val client = client(stub)

        unlock(client).close()

        assertEquals(listOf(LINK_URL, CALLBACK_URL), stub.urls)
    }

    @Ignore("HTTP await is an android stub on the JVM test classpath (see issue #180 notes)")
    @Test
    fun missingGateUrlSkipsTheLinkRequest() {
        val stub = Stub()
        val client = client(stub)

        unlock(client, gate = GateData(TOKEN, CHAPTER_PATH, 10, null)).close()

        assertEquals(listOf(CALLBACK_URL), stub.urls)
    }

    @Test
    fun gateForAnotherChapterIsRejectedBeforeAnyRequest() {
        val stub = Stub()
        val client = client(stub)

        val error = runCatching { unlock(client, gate = GateData(TOKEN, "/manga/other/1", 10, LINK_URL)) }
        assertEquals(IllegalArgumentException::class.java, error.exceptionOrNull()!!::class.java)
        assertEquals(emptyList<String>(), stub.urls)
    }
}
