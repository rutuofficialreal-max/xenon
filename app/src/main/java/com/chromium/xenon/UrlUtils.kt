package com.chromium.xenon

import java.net.URLEncoder

/**
 * Turns whatever the user typed into something the WebView can load.
 *
 * Rules:
 *  - `http://` / `https://` links pass through untouched.
 *  - Anything that looks like a host name (`example.com`, `192.168.1.1`, `localhost:8080`)
 *    gets `https://` prepended.
 *  - Any other scheme the user typed by hand (`javascript:`, `file:`, `content:`, `data:`)
 *    is downgraded to a search query. Those schemes are script/system surfaces, not
 *    browsing destinations, and honouring them from the address bar is a classic
 *    self-XSS vector.
 *  - Everything else is a search, using the configured engine template.
 *
 * Schemes that arrive from *web pages* (mailto:, tel:, intent:) never go through here —
 * [MainActivity] forwards those to the OS instead.
 */
object UrlUtils {

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun normalize(input: String, searchTemplate: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return ""

        val lower = raw.lowercase()
        if (lower == "about:blank") return lower
        if (lower.startsWith(Prefs.HOME_INTERNAL)) return Prefs.HOME_INTERNAL

        if (SCHEME_REGEX.containsMatchIn(raw)) {
            val scheme = lower.substringBefore(':')
            return if (scheme == "http" || scheme == "https") {
                raw
            } else {
                searchUrl(raw, searchTemplate)
            }
        }

        return if (looksLikeHost(raw)) "https://$raw" else searchUrl(raw, searchTemplate)
    }

    /**
     * Upgrades a homepage typed by the user into a loadable URL.
     * Returns null when the text is not URL-like at all.
     */
    fun normalizeHomepage(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return raw
        if (looksLikeHost(raw)) return "https://$raw"
        return null
    }

    fun searchUrl(query: String, template: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (template.contains("%s")) template.replace("%s", encoded) else template + encoded
    }

    /** True when the text is plausibly a host name rather than a phrase to search. */
    private fun looksLikeHost(value: String): Boolean {
        if (value.contains(' ')) return false

        val authority = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')

        if (authority.isEmpty()) return false

        val host = authority.substringBefore(':')
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (IPV4_REGEX.matches(host)) return true
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.')) return false

        val tld = host.substringAfterLast('.').lowercase()
        return tld.length >= 2 && tld.all { it.isLetter() }
    }
}
