package keiyoushi.source

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import java.net.URI

class MirrorPreferences(
    private val preferences: SharedPreferences,
    defaultMirrors: Array<Pair<String, String>> = emptyArray(),
    private val title: String,
    mirrors: Array<Pair<String, String>> = defaultMirrors,
) : FetchableBaseUrl {
    private val prefKey = "preferred_mirror"
    private val fetchedKey = "fetchedMirrors"
    private val fetchedFromKey = "fetchedMirrorsFrom"
    private val effectiveDefaultMirrors: Array<Pair<String, String>> =
        if (defaultMirrors.isNotEmpty()) defaultMirrors else mirrors
    private val hardcodedUrls = effectiveDefaultMirrors.joinToString(",") { it.second }

    init {
        val fetchedFrom = preferences.getString(fetchedFromKey, null)
        if (fetchedFrom != null && fetchedFrom != hardcodedUrls) {
            preferences.edit()
                .remove(fetchedKey)
                .remove(fetchedFromKey)
                .remove(prefKey)
                .apply()
        }
    }

    /** The mirrors in use: the fetched urls if any, else the hardcoded ones. */
    val mirrors: List<Pair<String, String>>
        get() = preferences.getString(fetchedKey, null)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { "" to it }
            ?: effectiveDefaultMirrors.asList()

    private val index: Int
        get() = (preferences.getString(prefKey, "0")?.toIntOrNull() ?: 0)
            .coerceIn(0, (mirrors.size - 1).coerceAtLeast(0))

    val baseUrl: String
        get() = mirrors.getOrNull(index)?.second.orEmpty()

    override fun update(urls: List<String>) {
        if (urls.isEmpty()) {
            preferences.edit()
                .remove(fetchedKey)
                .remove(fetchedFromKey)
                .putString(prefKey, "0")
                .apply()
            return
        }
        // The default follows the site's order. A pick follows its url, or keeps its place when its domain moved in a
        // list of the same size, else goes back to the first mirror
        val pick = index
        val currentUrl = mirrors.getOrNull(pick)?.second
        val newIndex = when {
            pick == 0 -> 0
            currentUrl != null && currentUrl in urls -> urls.indexOf(currentUrl)
            urls.size == mirrors.size -> pick
            else -> 0
        }
        preferences.edit()
            .putString(fetchedKey, urls.joinToString(","))
            .putString(fetchedFromKey, hardcodedUrls)
            .putString(prefKey, newIndex.toString())
            .apply()
    }

    fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mirrors = mirrors
        if (mirrors.size < 2) return
        ListPreference(screen.context).apply {
            key = prefKey
            title = this@MirrorPreferences.title
            entries = mirrors.map { (label, url) ->
                label.takeIf { it.isNotBlank() }
                    ?: runCatching { URI(url).host }.getOrDefault(url)
            }.toTypedArray()
            entryValues = Array(mirrors.size) { it.toString() }
            summary = "%s"
            setDefaultValue("0")
        }.also(screen::addPreference)
    }
}
