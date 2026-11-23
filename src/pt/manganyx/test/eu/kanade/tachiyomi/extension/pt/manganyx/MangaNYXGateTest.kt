package eu.kanade.tachiyomi.extension.pt.manganyx

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaNYXGateTest {
    private class TestSource : MangaNYX() {
        override val name get() = "Manga NXY"
        override val lang get() = "pt-BR"
        override val id get() = 1L
        override val baseUrl get() = "https://manganyx.org"

        val siteKey get() = captchaSiteKey
        val unlocksReader get() = captchaUnlocksReader
        val fallback get() = gateFallbackSeconds
        val trustsPartnerZero get() = gateTrustsPartnerZero
        fun configured(builder: OkHttpClient.Builder) = builder.configureClient()
    }

    // HttpSource's constructor is a compile-only stub in JVM tests, so allocate without running it.
    private fun testSource(): TestSource {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(TestSource::class.java) as TestSource
    }

    @Test
    fun gateUsesTheSiteTurnstileKeyAndTheMeasuredFallback() {
        val source = testSource()
        assertEquals("0x4AAAAAAFBwlZQivIZOPnMK", source.siteKey)
        // redenovax declares 0 but refused an 8s callback and accepted 9s, so partner zero is not trusted.
        assertEquals(false, source.trustsPartnerZero)
        assertEquals(12, source.fallback)
        assertTrue(source.unlocksReader)
    }

    @Test
    fun configureClientStillInstallsTheReaderOriginInterceptor() {
        val seen = mutableListOf<Request>()
        val client = testSource().configured(OkHttpClient.Builder())
            .addInterceptor { chain ->
                seen += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(
            Request.Builder()
                .url("https://manganyx.org/api/gate/start")
                .post("{}".toRequestBody())
                .build(),
        ).execute().close()

        assertEquals("https://manganyx.org", seen.single().header("Origin"))
        assertEquals("POST", seen.single().method)
    }
}
