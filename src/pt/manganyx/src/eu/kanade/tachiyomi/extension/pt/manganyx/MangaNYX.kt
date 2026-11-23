package eu.kanade.tachiyomi.extension.pt.manganyx

import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient

@Source
abstract class MangaNYX : Aurora() {
    // Reader widget key (execute mode, no action): its token is the one /api/gate/start accepts.
    override val captchaSiteKey get() = "0x4AAAAAAFBwlZQivIZOPnMK"

    // The gate declares method "redenovax" with minWaitSeconds 0 but refuses the callback before ~9s:
    // measured too_fast at 8s and accepted from 9s, so the shared partner shortcut cannot be trusted here.
    override val gateTrustsPartnerZero get() = false

    // 12s = measured 9s floor plus margin, and below the site's own 15s default.
    override val gateFallbackSeconds get() = 12

    override val captchaUnlocksReader get() = true

    // Logcat only, names only: `adb logcat -s MangaNXY`. Values of cookies and tokens never appear here.
    override fun auroraDiagnostic(message: String) {
        val webView = runCatching {
            CookieManager.getInstance().getCookie("$baseUrl/").orEmpty()
                .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
        }.getOrElse { "?" }
        Log.i("MangaNXY", "$message webview=[$webView]")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = defaultClient()
        .addInterceptor(ReaderOriginInterceptor { baseUrl })
}
