package eu.kanade.tachiyomi.extension.pt.noindexscan

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class HanamiHeaven :
    MadaraNoAjax(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val authClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private val sessionManager by lazy {
        HanamiSessionManager(
            baseUrl = baseUrl,
            credentials = object : HanamiCredentialStore {
                override fun getEmail(): String = preferences.getString(PREF_EMAIL, "").orEmpty()
                override fun getPassword(): String = preferences.getString(PREF_PASSWORD, "").orEmpty()
            },
            cookieStore = AndroidCookieStore(),
            httpClientProvider = { authClient },
            baseHeadersProvider = { headers },
        )
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_EMAIL || key == PREF_PASSWORD) {
            sessionManager.onCredentialsChanged()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3, 2.seconds)
        addInterceptor(sessionManager::intercept)
    }

    override val mangaSubString = "todas-as-obras"
    override val orderQueryParameter = "ordem"
    override val searchQueryParameter = "busca"
    override fun nextPageSelector() = ".mch-catalogue__pages a.next"

    override fun archiveUrlBuilder(page: Int, order: String, path: String, query: String) = super.archiveUrlBuilder(1, order, path, query).apply {
        if (page > 1) addQueryParameter("pagina", page.toString())
    }

    override val completedStatus = super.completedStatus + "completa"
    override val hiatusStatus = super.hiatusStatus + "em espera"
    override val cancelledStatus = super.cancelledStatus + "cancelada"

    // Novels have text chapters, which the reader cannot show
    override fun archiveSelector() = "li.mch-catalogue-card:not(:has(.mch-catalogue-card__meta span:contains(Novel)))"
    override val archiveUrlSelector = ".mch-catalogue-card__title a"
    override fun Element.postId() = attr("data-work-id")

    override val mangaDetailsSelectorTitle = "#mch-work-title"
    override val mangaDetailsSelectorAuthor = metadataSelector("Autor", "a")
    override val mangaDetailsSelectorArtist = metadataSelector("Artista", "a")
    override val mangaDetailsSelectorStatus = ".mch-work__status"
    override val mangaDetailsSelectorDescription = "#mch-work-synopsis"
    override val mangaDetailsSelectorThumbnail = ".mch-work__cover img"
    override val mangaDetailsSelectorGenre = metadataSelector("Gêneros", "a")
    override val mangaDetailsSelectorTag = metadataSelector("Tags", "a")
    override val seriesTypeSelector = metadataSelector("Tipo", "dd")
    override val altNameSelector = metadataSelector("Nome alternativo", "dd")

    override fun getHomeUrl() = "$baseUrl/$mangaSubString/"

    override suspend fun getPopularManga(page: Int) = archivePage(page, "rating")

    override suspend fun getLatestUpdates(page: Int) = archivePage(page, "")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = getHomeUrl().toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<UrlFilter>().forEach { it.addTo(this) }
        }.build()
        return archivePage(page, "", url.toString(), query)
    }

    // Chapter pages have no post id
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val isChapter = segments.size > 2 && segments[0] == "manga"
        return super.getMangaByUrl(if (isChapter) url.newBuilder().encodedPath("/manga/${segments[1]}/").build() else url)
    }

    // Genre links carry the slug in ?genero=
    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga = super.parseDetails(document, id, preserveUrl).apply {
        val genres = document.select(mangaDetailsSelectorGenre).mapNotNull { link ->
            link.attr("abs:href").toHttpUrl().queryParameter("genero")?.let { GenreRoute(link.text(), it, "/$mangaSubString/?genero[]=$it") }
        }
        memo = JsonObject(memo + ("genres" to genres.toJsonElement()))
    }

    override fun chapterFromElement(element: Element, mangaPath: String) = super.chapterFromElement(element, mangaPath)?.apply {
        date_upload = Instant.tryParse(element.selectFirst("time")?.attr("datetime"))
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(getHomeUrl(), Fetch.DOCUMENT).asJsoup()
        return document.select("input[name=genero[]]").map {
            GenreRoute(it.parent()!!.text(), it.attr("value"), "")
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreRoute>>().orEmpty()
        return FilterList(
            buildList {
                add(SelectFilter("Ordenar por", "ordem", SORT_OPTIONS))
                add(SelectFilter("Tipo", "tipo", TYPE_OPTIONS))
                add(SelectFilter("Status", "status", STATUS_OPTIONS))
                add(SelectFilter("Quantidade de capítulos", "capitulos", CHAPTER_COUNT_OPTIONS))
                add(YearFilter("Ano de lançamento"))
                if (genres.isNotEmpty()) add(GenreFilter("Gêneros", genres))
            },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        val warning = "⚠️ Os dados inseridos nesta seção serão usados somente para realizar o login automático na fonte."
        val message = "Insira %s para acessar obras disponíveis na sua conta."

        EditTextPreference(context).apply {
            key = PREF_EMAIL
            title = "E-mail"
            summary = "E-mail ou usuário de acesso"
            dialogMessage = buildString {
                appendLine(message.format("seu e-mail ou usuário"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            setOnPreferenceChangeListener { _, _ ->
                sessionManager.onCredentialsChanged()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Senha"
            summary = "Senha de acesso"
            dialogMessage = buildString {
                appendLine(message.format("sua senha"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                attachPasswordVisibilityToggle(editText)
            }
            setOnPreferenceChangeListener { _, _ ->
                sessionManager.onCredentialsChanged()
                true
            }
        }.let(screen::addPreference)
    }

    private inner class AndroidCookieStore : HanamiCookieStore {
        private fun getWebCookieManager(): CookieManager? = try {
            CookieManager.getInstance()
        } catch (_: Throwable) {
            null
        }

        override fun loadSessionCookies(url: HttpUrl): List<Cookie> = client.cookieJar.loadForRequest(url)

        override fun syncFromWebView(url: HttpUrl): Boolean {
            val cookieManager = getWebCookieManager() ?: return false
            val cookieHeader = cookieManager.getCookie(url.toString())
                ?: cookieManager.getCookie("${url.scheme}://${url.host}/")
                ?: return false
            if (cookieHeader.isBlank()) return false

            val parsed = parseCookieHeader(url, cookieHeader)
            if (parsed.isEmpty()) return false
            client.cookieJar.saveFromResponse(url, parsed)
            cookieManager.flush()
            return parsed.any {
                it.name.startsWith(HanamiSessionManager.WP_LOGGED_IN_PREFIX) &&
                    !it.value.equals("deleted", ignoreCase = true)
            }
        }

        override fun saveFromHeaders(url: HttpUrl, setCookieHeaders: List<String>) {
            val cookieManager = getWebCookieManager()
            val parsed = setCookieHeaders.mapNotNull { header ->
                cookieManager?.setCookie(url.toString(), header)
                Cookie.parse(url, header)
            }
            if (parsed.isNotEmpty()) {
                client.cookieJar.saveFromResponse(url, parsed)
            }
            cookieManager?.flush()
        }

        override fun clearSessionCookies(url: HttpUrl) {
            val cookieManager = getWebCookieManager()
            val existing = client.cookieJar.loadForRequest(url)
            val webNames = cookieManager?.getCookie(url.toString())
                ?.split(';')
                ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                .orEmpty()
            val namesToExpire = (existing.map { it.name } + webNames)
                .distinct()
                .filter {
                    it.startsWith("wordpress_") ||
                        it.startsWith("wp-settings") ||
                        it == "_lscache_vary"
                }

            val expired = namesToExpire.map { name ->
                Cookie.Builder()
                    .domain(url.host)
                    .path("/")
                    .name(name)
                    .value("")
                    .expiresAt(0L)
                    .build()
            }
            if (expired.isNotEmpty()) {
                client.cookieJar.saveFromResponse(url, expired)
            }
            namesToExpire.forEach { name ->
                cookieManager?.setCookie(url.toString(), "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                cookieManager?.setCookie(url.toString(), "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=${url.host}; Path=/")
            }
            cookieManager?.flush()
        }

        override fun evictHttpCache() {
            client.cache?.let { cache ->
                try {
                    cache.evictAll()
                } catch (e: java.io.IOException) {
                    android.util.Log.w("HanamiHeaven", "Falha ao limpar cache HTTP", e)
                }
            }
        }
    }

    private fun metadataSelector(label: String, value: String) = ".mch-work__metadata div:has(> dt:contains($label)) $value"

    companion object {
        private const val PREF_EMAIL = "hanami_email"
        private const val PREF_PASSWORD = "hanami_password"
    }
}

internal fun attachPasswordVisibilityToggle(editText: EditText) {
    editText.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    editText.transformationMethod = PasswordTransformationMethod.getInstance()
    val endPos = editText.text?.length ?: 0
    editText.setSelection(endPos)

    val parent = editText.parent as? ViewGroup ?: return
    if (parent.tag == PASSWORD_CONTAINER_TAG) return

    val context = editText.context
    val density = context.resources.displayMetrics.density
    val iconSize = (40 * density).toInt()
    val iconPad = (8 * density).toInt()
    val eye = HanamiEyeDrawable(
        density = density,
        color = (editText.currentTextColor and 0x00FFFFFF) or 0x99000000.toInt(),
    )

    val index = parent.indexOfChild(editText)
    val originalParams = editText.layoutParams
    parent.removeViewAt(index)

    val container = FrameLayout(context).apply {
        tag = PASSWORD_CONTAINER_TAG
        layoutParams = originalParams
    }

    editText.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    editText.setPaddingRelative(
        editText.paddingStart,
        editText.paddingTop,
        maxOf(editText.paddingEnd, iconSize + iconPad),
        editText.paddingBottom,
    )
    container.addView(editText)

    val toggle = ImageView(context).apply {
        setImageDrawable(eye)
        contentDescription = "Mostrar senha"
        isClickable = true
        isFocusable = true
        setPadding(iconPad, iconPad, iconPad, iconPad)
        setOnClickListener {
            val showPassword = !eye.isPasswordVisible
            eye.isPasswordVisible = showPassword
            contentDescription = if (showPassword) "Ocultar senha" else "Mostrar senha"
            applyPasswordVisibility(editText, showPassword)
        }
    }
    val toggleParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        marginEnd = (4 * density).toInt()
    }
    container.addView(toggle, toggleParams)
    parent.addView(container, index)
}

internal fun applyPasswordVisibility(editText: EditText, visible: Boolean) {
    val start = editText.selectionStart.coerceAtLeast(0)
    val end = editText.selectionEnd.coerceAtLeast(0)
    editText.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
    editText.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
    val len = editText.text?.length ?: 0
    editText.setSelection(start.coerceIn(0, len), end.coerceIn(0, len))
}

private const val PASSWORD_CONTAINER_TAG = "hanami_password_container"

private class HanamiEyeDrawable(
    private val density: Float,
    private val color: Int,
) : Drawable() {
    var isPasswordVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@HanamiEyeDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@HanamiEyeDrawable.color
        style = Paint.Style.FILL
    }

    override fun getIntrinsicWidth(): Int = (24 * density).toInt()
    override fun getIntrinsicHeight(): Int = (24 * density).toInt()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val w = 18f * density
        val h = 10f * density

        val path = Path().apply {
            moveTo(cx - w / 2, cy)
            quadTo(cx, cy - h, cx + w / 2, cy)
            quadTo(cx, cy + h, cx - w / 2, cy)
            close()
        }
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(cx, cy, 2.5f * density, pupilPaint)

        if (!isPasswordVisible) {
            canvas.drawLine(cx - w / 2, cy - h / 1.5f, cx + w / 2, cy + h / 1.5f, strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        pupilPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        pupilPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun parseCookieHeader(url: HttpUrl, cookieHeader: String): List<Cookie> = cookieHeader.split(';')
    .mapNotNull { entry ->
        val trimmed = entry.trim()
        val sep = trimmed.indexOf('=')
        if (sep <= 0) return@mapNotNull null
        val name = trimmed.substring(0, sep).trim()
        val value = trimmed.substring(sep + 1).trim()
        if (name.isEmpty() || value.isEmpty() || value.equals("deleted", ignoreCase = true)) return@mapNotNull null
        Cookie.Builder()
            .domain(url.host)
            .path("/")
            .name(name)
            .value(value)
            .build()
    }
