package eu.kanade.tachiyomi.multisrc.aurora

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GateTimingTest {
    private fun response(error: String? = null) = Response.Builder()
        .request(Request.Builder().url("https://reader.example/manga/series/1" + (error?.let { "?gate_error=$it" } ?: "")).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("fixture".toResponseBody())
        .build()

    @Test
    fun declaredSecondsAreHonouredExactly() = runBlocking {
        for (declared in listOf(8, 3, 1)) {
            var elapsed = Duration.ZERO
            GateTiming().complete(declared, elapsed = { elapsed }, wait = { elapsed += it }) {
                assertEquals(declared.seconds, elapsed)
                response()
            }.close()
        }
    }

    @Test
    fun declaredZeroMeansNoCountdown() = runBlocking {
        val waits = mutableListOf<Duration>()
        GateTiming().complete(0, elapsed = { Duration.ZERO }, wait = { waits.add(it) }) { response() }.close()
        assertEquals(listOf(Duration.ZERO), waits)
    }

    @Test
    fun absentFieldFallsBackToTheSitesFifteenSeconds() = runBlocking {
        var elapsed = Duration.ZERO
        GateTiming().complete(null, elapsed = { elapsed }, wait = { elapsed += it }) {
            assertEquals(15.seconds, elapsed)
            response()
        }.close()
    }

    @Test
    fun partnerMethodSkipsTheCountdownEvenWithoutADeclaredValue() = runBlocking {
        var waited = 0
        GateTiming().complete(null, partner = true, elapsed = { Duration.ZERO }, wait = { waited++ }) { response() }.close()
        assertEquals(1, waited)
    }

    @Test
    fun gateLinkRunsWhileTheCountdownBurns() = runBlocking {
        val events = mutableListOf<String>()
        GateTiming().complete(
            15,
            elapsed = { Duration.ZERO },
            wait = { events.add("wait-start"); yield(); events.add("wait-end") },
            beforeRequest = { events.add("link") },
        ) { response() }.close()
        assertTrue(events.indexOf("link") < events.indexOf("wait-end"))
    }

    @Test
    fun zeroIsHonouredAndTooFastEscalates() = runBlocking {
        val timing = GateTiming()
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        timing.complete(0, elapsed = { elapsed }, wait = { elapsed += it }) {
            attempts += elapsed
            response(if (attempts.size == 1) "too_fast" else null)
        }.close()
        assertEquals(listOf(Duration.ZERO, 4.seconds), attempts)
    }

    @Test
    fun unverifiedPartnerZeroStartsAtTheMeasuredFallbackAndLearns() = runBlocking {
        // Mugiwaras profile: the gate declares 0 on a partner method but refuses until ~9s.
        val timing = GateTiming(fallbackSeconds = 5, trustPartnerZero = false)
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        timing.complete(0, partner = true, elapsed = { elapsed }, wait = { elapsed += it }) {
            attempts += elapsed
            response(if (attempts.size <= 2) "too_fast" else null)
        }.close()
        assertEquals(listOf(5.seconds, 9.seconds, 13.seconds), attempts)

        // The accepted countdown is reused instead of probing again.
        elapsed = Duration.ZERO
        val next = mutableListOf<Duration>()
        timing.complete(0, partner = true, elapsed = { elapsed }, wait = { elapsed += it }) {
            next += elapsed
            response()
        }.close()
        assertEquals(listOf(13.seconds), next)
    }

    @Test
    fun tooFastRecoversOnceAndIsRememberedForNextChapter() = runBlocking {
        val timing = GateTiming()
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        val result = timing.complete(0, elapsed = { elapsed }, wait = { elapsed += it }) {
            attempts += elapsed
            response(if (attempts.size == 1) "too_fast" else null)
        }
        result.use { assertEquals(null, it.request.url.queryParameter("gate_error")) }
        assertEquals(listOf(Duration.ZERO, 4.seconds), attempts)

        elapsed = Duration.ZERO
        timing.complete(0, elapsed = { elapsed }, wait = { elapsed += it }) {
            assertEquals(4.seconds, elapsed)
            response()
        }.close()
    }

    @Test
    fun serverMinimumIsRespectedAndRetriesAreBounded() = runBlocking {
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        GateTiming().complete(20, elapsed = { elapsed }, wait = { elapsed += it }) {
            attempts += elapsed
            response("too_fast")
        }.close()
        assertEquals(listOf(20.seconds, 24.seconds, 28.seconds), attempts)
    }

    @Test
    fun expiredTokenIsNotRetried() = runBlocking {
        var calls = 0
        GateTiming().complete(0, elapsed = { 30.seconds }, wait = { assertEquals(Duration.ZERO, it) }) {
            calls++
            response("expired")
        }.close()
        assertEquals(1, calls)
    }

    @Test
    fun cancellingWaitDoesNotSendCallback() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                GateTiming().complete(0, elapsed = { Duration.ZERO }, wait = { throw CancellationException() }) {
                    calls++
                    response()
                }
            }
        }
        assertEquals(0, calls)
    }
}
