package eu.kanade.tachiyomi.extension.pt.roxinha

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.Normalizer

@Source
abstract class Roxinha :
    KeiSource(),
    ConfigurableSource {
    private val apiUrl = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    @Volatile
    private var cachedToken: String? = null

    override val imageFetch: Fetch get() = Fetch.IMAGE.from(baseUrl)

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::authIntercept)

    override suspend fun getPopularManga(page: Int) = getSearchMangaList(page, "", SortFilter.POPULAR)

    override suspend fun getLatestUpdates(page: Int) = getSearchMangaList(page, "", SortFilter.LATEST)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.pathSegments.getOrNull(1) ?: return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = id },
            emptyList(),
            true,
            false,
        ).manga()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val offset = (page - 1) * 24
        val url = "$apiUrl/manga/search/advanced".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "24")
            addQueryParameter("offset", offset.toString())
            addQueryParameter("mode", "default")

            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        addQueryParameter("sort", filter.selected)
                        addQueryParameter("order", filter.order)
                    }
                    is StatusFilter -> addQueryParameter("status", filter.selected)
                    is TypeFilter -> addQueryParameter("type", filter.selected)
                    else -> {}
                }
            }
        }.build()

        val dto = client.get(url, Fetch.XHR.from(baseUrl)).parseAs<SearchResponseDto>()
        val (mangas, hasMore) = dto.toMangasPage(baseUrl)
        return MangasPage(mangas, hasMore)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    override val supportRelatedMangasBySearch = true

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$apiUrl/manga/${manga.url}", Fetch.XHR.from(getMangaUrl(manga)))
        val dto = response.parseAs<MangaDto>()

        return SMangaUpdate(dto.toSManga(baseUrl), dto.toSChapters())
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/manga/chapter/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$apiUrl/manga/chapter/${chapter.url}"
        val chapterFetch = Fetch.XHR.from(getChapterUrl(chapter))

        // Pick up a fresh WebView login before hitting /access when no token is cached yet.
        if (currentToken() == null) {
            syncTokenFromWebView()
        }

        val accessRes = client.get("$chapterUrl/access", chapterFetch, ensureSuccess = false)
        if (!accessRes.isSuccessful) {
            val code = accessRes.code
            val message = accessRes.errorMessage()
            throw Exception(if (code == 401) "$message. $LOGIN_HINT" else message)
        }
        val accessDto = accessRes.parseAs<TicketDto>()
        val accessHeaders = headersBuilder().set("x-chapter-access", accessDto.ticket).build()

        val chapterRes = client.get(chapterUrl, accessHeaders, chapterFetch)
        val chapterDto = chapterRes.parseAs<ChapterDetailsDto>()
        return chapterDto.toPages(baseUrl)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        clearLegacyCredentials()
        val hasToken = !currentToken().isNullOrBlank()

        lateinit var loginPref: Preference
        lateinit var statusPref: Preference
        lateinit var clearPref: Preference

        fun updateUi(loggedIn: Boolean) {
            loginPref.title = if (loggedIn) "Abrir Roxinha" else "Entrar na Roxinha"
            loginPref.summary = if (loggedIn) {
                "Abre o site oficial da Roxinha no WebView."
            } else {
                "Abre o login oficial no WebView para autenticar sua conta."
            }
            statusPref.summary = if (loggedIn) "Conectado" else "Não conectado"
            clearPref.setPrefBoolean("setVisible", loggedIn)
        }

        loginPref = newPreference(screen.context).apply {
            key = PREF_LOGIN_ACTION
            setOnPreferenceClickListener {
                showWebViewLogin(screen.context) {
                    updateUi(!currentToken().isNullOrBlank())
                }
                true
            }
        }.also(screen::addPreference)

        statusPref = newPreference(screen.context).apply {
            key = PREF_SESSION_STATUS
            title = "Sessão Roxinha"
            setOnPreferenceClickListener { true }
            setPrefBoolean("setSelectable", false)
        }.also(screen::addPreference)

        clearPref = newPreference(screen.context).apply {
            key = PREF_LOGOUT_ACTION
            title = "Sair da conta"
            summary = "Remove a sessão salva da Roxinha neste aplicativo."
            setOnPreferenceClickListener {
                clearToken()
                clearWebViewStorage(screen.context)
                updateUi(false)
                true
            }
        }.also(screen::addPreference)

        updateUi(hasToken)
    }

    private fun newPreference(context: Context): Preference = runCatching {
        Preference::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun Preference.setPrefBoolean(methodName: String, value: Boolean) {
        runCatching {
            this::class.java.methods
                .firstOrNull { it.name == methodName && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                ?.invoke(this, value)
        }
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/api/")) {
            return chain.proceed(request)
        }

        val activeToken = currentToken()
        val authedRequest = if (activeToken != null) request.withToken(activeToken) else request
        val response = chain.proceed(authedRequest)

        if (!shouldRefreshToken(response)) {
            return response
        }

        val freshToken = syncTokenFromWebView(staleToken = activeToken)
        if (freshToken == null) {
            return response
        }

        response.close()
        return chain.proceed(request.withToken(freshToken))
    }

    private fun shouldRefreshToken(response: Response): Boolean {
        if (response.code == 401) return true
        if (response.code != 403) return false
        val body = runCatching { response.peekBody(2048).string() }.getOrNull() ?: return false
        val dto = runCatching { body.parseAs<ErrorDto>() }.getOrNull() ?: return false
        return isTokenErrorMessage(dto.error)
    }

    private fun Request.withToken(token: String) = newBuilder()
        .header("Authorization", "Bearer $token")
        .build()

    @Synchronized
    private fun currentToken(): String? {
        cachedToken?.takeIf { it.isNotBlank() }?.let { return it }
        val saved = preferences.getString(TOKEN_PREF, null)?.takeIf { it.isNotBlank() }
        cachedToken = saved
        return saved
    }

    @Synchronized
    private fun syncTokenFromWebView(staleToken: String? = null): String? {
        if (staleToken != null) {
            clearToken()
        }

        val fromStorage = runCatching {
            runBlocking { getLocalStorage(baseUrl, LOCAL_STORAGE_TOKEN_KEY) }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        if (fromStorage == null || fromStorage == staleToken) {
            return null
        }

        saveToken(fromStorage)
        return fromStorage
    }

    private fun saveToken(token: String) {
        cachedToken = token
        preferences.edit().putString(TOKEN_PREF, token).apply()
    }

    private fun clearToken() {
        cachedToken = null
        preferences.edit().remove(TOKEN_PREF).apply()
    }

    private fun clearLegacyCredentials() {
        if (preferences.contains(LEGACY_EMAIL_PREF) || preferences.contains(LEGACY_PASSWORD_PREF)) {
            preferences.edit()
                .remove(LEGACY_EMAIL_PREF)
                .remove(LEGACY_PASSWORD_PREF)
                .apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun clearWebViewStorage(context: Context) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webView.evaluateJavascript(
                    "localStorage.removeItem('$LOCAL_STORAGE_TOKEN_KEY');localStorage.removeItem('user');",
                ) {
                    webView.destroy()
                }
            }
        }
        webView.loadDataWithBaseURL(baseUrl, "<link rel=\"icon\" href=\"data:,\">", "text/html", "utf-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebViewLogin(context: Context, onDone: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var destroyed = false

        fun close(autoClosed: Boolean = false) {
            if (destroyed) return
            destroyed = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            if (dialog.isShowing) dialog.dismiss()
            webView.destroy()
            if (autoClosed) onDone()
        }

        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topBar.addView(
            TextView(context).apply {
                text = "←"
                textSize = 22f
                setPadding(0, 0, (16 * density).toInt(), 0)
                setOnClickListener { if (webView.canGoBack()) webView.goBack() else close() }
            },
        )
        topBar.addView(
            TextView(context).apply {
                text = "Roxinha"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        topBar.addView(
            TextView(context).apply {
                text = "✕"
                textSize = 20f
                setPadding((16 * density).toInt(), 0, 0, 0)
                setOnClickListener { close() }
            },
        )
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * density).toInt().coerceAtLeast(1))
        }
        root.addView(topBar)
        root.addView(progress)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        lateinit var checkToken: () -> Unit
        checkToken = {
            if (!finished && !destroyed && dialog.isShowing) {
                webView.evaluateJavascript("localStorage.getItem('$LOCAL_STORAGE_TOKEN_KEY')") { raw ->
                    if (finished || destroyed) return@evaluateJavascript
                    val synced = runCatching { raw.parseAs<String?>() }.getOrNull()?.trim()
                    if (!synced.isNullOrBlank() && synced != "null") {
                        finished = true
                        saveToken(synced)
                        close(autoClosed = true)
                    } else if (!finished && !destroyed && dialog.isShowing) {
                        handler.postDelayed({ checkToken() }, 1_000L)
                    }
                }
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                handler.removeCallbacksAndMessages(null)
                checkToken()
            }
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && webView.canGoBack()) {
                webView.goBack()
                return@setOnKeyListener true
            }
            false
        }
        dialog.setOnDismissListener {
            if (!destroyed) {
                destroyed = true
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                webView.destroy()
            }
        }
        dialog.show()
        webView.loadUrl("$baseUrl/login")
    }

    private fun Response.errorMessage(): String = use {
        runCatching { it.parseAs<ErrorDto>().error.takeIf(String::isNotBlank) }.getOrNull() ?: "Erro HTTP ${it.code}"
    }

    companion object {
        private const val TOKEN_PREF = "roxinha_token"
        private const val LEGACY_EMAIL_PREF = "email"
        private const val LEGACY_PASSWORD_PREF = "password"
        private const val PREF_LOGIN_ACTION = "roxinha_login"
        private const val PREF_SESSION_STATUS = "roxinha_session_status"
        private const val PREF_LOGOUT_ACTION = "roxinha_logout"
        private const val LOCAL_STORAGE_TOKEN_KEY = "token"
        private const val LOGIN_HINT = "Faça login abrindo a Roxinha no WebView (Configurações da extensão ou 'Abrir no WebView')"
    }
}

internal fun isTokenErrorMessage(error: String): Boolean {
    val normalized = Normalizer.normalize(error, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
    return "token nao fornecido" in normalized ||
        "token invalido" in normalized ||
        "token expirado" in normalized ||
        "usuario inativo" in normalized
}
