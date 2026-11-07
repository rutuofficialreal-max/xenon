package com.chromium.xenon

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for every user preference in Xenon.
 *
 * Backed by SharedPreferences so reads are cheap and the whole settings screen
 * can be wired without any extra dependency.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** One of [ENGINE_GOOGLE], [ENGINE_DUCKDUCKGO], [ENGINE_BRAVE], [ENGINE_STARTPAGE]. */
    var searchEngine: String
        get() = sp.getString(KEY_SEARCH_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE
        set(value) {
            sp.edit().putString(KEY_SEARCH_ENGINE, value).apply()
        }

    /** A real URL, or [HOME_INTERNAL] for the bundled Xenon start page. */
    var homepage: String
        get() = sp.getString(KEY_HOMEPAGE, HOME_INTERNAL) ?: HOME_INTERNAL
        set(value) {
            sp.edit().putString(KEY_HOMEPAGE, value).apply()
        }

    var javaScriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JAVASCRIPT, true)
        set(value) {
            sp.edit().putBoolean(KEY_JAVASCRIPT, value).apply()
        }

    var desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            sp.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    /** URL template with a `%s` placeholder for the encoded query. */
    val searchUrlTemplate: String
        get() = when (searchEngine) {
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/?q=%s"
            ENGINE_BRAVE -> "https://search.brave.com/search?q=%s"
            ENGINE_STARTPAGE -> "https://www.startpage.com/sp/search?query=%s"
            else -> "https://www.google.com/search?q=%s"
        }

    /** Human readable engine name, used by the start page and settings UI. */
    val searchEngineLabel: String
        get() = when (searchEngine) {
            ENGINE_DUCKDUCKGO -> "DuckDuckGo"
            ENGINE_BRAVE -> "Brave Search"
            ENGINE_STARTPAGE -> "Startpage"
            else -> "Google"
        }

    fun resetToDefaults() {
        sp.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "xenon_prefs"

        const val ENGINE_GOOGLE = "google"
        const val ENGINE_DUCKDUCKGO = "duckduckgo"
        const val ENGINE_BRAVE = "brave"
        const val ENGINE_STARTPAGE = "startpage"

        /** Sentinel homepage value meaning "use the bundled start page". */
        const val HOME_INTERNAL = "xenon://home"

        /** Used when the user explicitly sets a homepage but leaves it blank. */
        const val DEFAULT_HOMEPAGE_URL = "https://www.google.com"

        /** Desktop Chrome user agent used when "Request desktop site" is enabled. */
        const val UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_HOMEPAGE = "homepage"
        private const val KEY_JAVASCRIPT = "javascript_enabled"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
    }
}
