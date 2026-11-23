package eu.kanade.tachiyomi.extension.all.manhuarm

import android.content.SharedPreferences
import eu.kanade.tachiyomi.multisrc.manhuarm.Language
import eu.kanade.tachiyomi.multisrc.manhuarm.interceptors.ComposedImageInterceptor
import eu.kanade.tachiyomi.multisrc.manhuarm.interceptors.TranslationInterceptor
import eu.kanade.tachiyomi.multisrc.manhuarm.translator.TranslatorEngine
import eu.kanade.tachiyomi.multisrc.manhuarm.translator.bing.BingTranslator
import eu.kanade.tachiyomi.multisrc.manhuarm.translator.google.GoogleTranslator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import eu.kanade.tachiyomi.multisrc.manhuarm.Manhuarm as BaseManhuarm

@Source
abstract class Manhuarm : BaseManhuarm() {

    private val localPrefs: SharedPreferences by getPreferencesLazy()

    private val localLanguage: Language
        get() = when (lang) {
            "ar" -> Language(lang, disableFontSettings = true)
            "fr", "id" -> Language(lang, supportNativeTranslation = true)
            "pt-BR" -> Language(lang, "pt", supportNativeTranslation = true)
            else -> Language(lang)
        }

    private val localSettings: Language
        get() {
            val base = localLanguage
            val fontName = localPrefs.getString(FONT_NAME_PREF, base.fontName) ?: base.fontName
            return base.copy(
                fontSize = localPrefs.getString(FONT_SIZE_PREF, DEFAULT_FONT_SIZE)?.toIntOrNull()
                    ?: DEFAULT_FONT_SIZE.toInt(),
                fontName = fontName,
                dialogBoxScale = localPrefs.getString(DIALOG_BOX_SCALE_PREF, base.dialogBoxScale.toString())
                    ?.toFloatOrNull() ?: base.dialogBoxScale,
                disableWordBreak = localPrefs.getBoolean(DISABLE_WORD_BREAK_PREF, base.disableWordBreak),
                disableTranslator = localPrefs.getBoolean(DISABLE_TRANSLATOR_PREF, base.disableTranslator),
                translateSynopsis = localPrefs.getBoolean(TRANSLATE_SYNOPSIS_PREF, base.translateSynopsis),
                disableFontSettings = base.disableFontSettings || fontName == DEVICE_FONT,
            )
        }

    private val localBingTranslator by lazy { BingTranslator(client) { headers } }
    private val localGoogleTranslator by lazy { GoogleTranslator(client) { headers } }

    private val localTranslator: TranslatorEngine
        get() = when (localPrefs.getString(TRANSLATOR_PROVIDER_PREF, "Bing")) {
            "Google" -> localGoogleTranslator
            else -> localBingTranslator
        }

    private val v10Reader by lazy {
        ManhuarmV10Reader(
            httpClientProvider = { client },
            baseHeadersProvider = { headers },
        )
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        localPrefs.getString(CUSTOM_UA_PREF, "")?.trim()?.takeIf(String::isNotEmpty)?.let {
            set("User-Agent", it)
        }
        // Browsers omit Origin on same-origin GETs; BrowserHeadersInterceptor adds it on POST XHRs.
        removeAll("Origin")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(1.minutes)
        .readTimeout(2.minutes)
        .addInterceptor(TranslationInterceptor({ localSettings }, { localTranslator }))
        .addInterceptor(ComposedImageInterceptor { localSettings })
        .addInterceptor(v10Reader::intercept)
        .rateLimit(3, 2.seconds) { it.host == BingTranslator.HOST || it.host == GoogleTranslator.HOST }
        .rateLimit(2)

    override suspend fun getPageList(chapter: SChapter): List<Page> = v10Reader.getPageListOrNull(getChapterUrl(chapter), localSettings)
        ?: super.getPageList(chapter)

    companion object {
        private const val FONT_SIZE_PREF = "fontSizePref"
        private const val FONT_NAME_PREF = "fontNamePref"
        private const val DIALOG_BOX_SCALE_PREF = "dialogBoxScalePref"
        private const val DISABLE_WORD_BREAK_PREF = "disableWordBreakPref"
        private const val DISABLE_TRANSLATOR_PREF = "disableTranslatorPref"
        private const val TRANSLATE_SYNOPSIS_PREF = "translateSynopsisPref"
        private const val TRANSLATOR_PROVIDER_PREF = "translatorProviderPref"
        private const val CUSTOM_UA_PREF = "customUserAgentPref"
        private const val DEFAULT_FONT_SIZE = "28"
    }
}
