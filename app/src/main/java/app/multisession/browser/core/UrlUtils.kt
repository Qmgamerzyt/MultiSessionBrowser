package app.multisession.browser.core

import android.net.Uri
import android.util.Patterns
import app.multisession.browser.engine.LocalContentLoader

object UrlUtils {
    /** Internal marker URL for the native start page (never loaded into the engine). */
    const val START_PAGE = "about:start"

    fun isStartPage(url: String?): Boolean =
        url.isNullOrBlank() || url == START_PAGE || url == "about:blank"

    fun isWebUrl(url: String?): Boolean {
        val l = url?.lowercase() ?: return false
        return l.startsWith("http://") || l.startsWith("https://")
    }

    fun isSecure(url: String?): Boolean = url?.lowercase()?.startsWith("https://") == true

    fun isLocalContent(url: String?): Boolean = LocalContentLoader.isLocalUrl(url)

    /** True for "javascript:..." input (any case, leading whitespace allowed): a bookmarklet, never a search query. */
    fun isJavaScriptUrl(input: String?): Boolean = input?.trimStart()?.lowercase()?.startsWith("javascript:") == true

    /**
     * Address-bar resolution (v2.2.0-beta-2):
     *  - javascript: -> returned unchanged; the caller executes it in the current page (never searched, never navigated)
     *  - looks like a host/IP (incl. host:port) without a scheme -> https:// (HTTPS-first; type http:// explicitly)
     *  - ANY other input containing ':' -> executable, loaded as-is (file:, http:, data:, intent:, ...).
     *    There is deliberately no per-scheme allowlist: one ':' makes the input a command/URL, never a
     *    search query. The engine still enforces its own policy per scheme (e.g. file:// is allowed
     *    only inside the projects folder - see LocalContentLoader.isAllowedLocalUri).
     *  - anything else -> configured search engine
     */
    fun resolveInput(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return START_PAGE
        if (isJavaScriptUrl(input)) return input
        val lower = input.lowercase()
        if (lower == START_PAGE || lower == "about:blank") return START_PAGE
        if (looksLikeHost(input)) return "https://$input"
        if (input.contains(':')) return input
        return Prefs.searchUrlFor(input)
    }

    fun looksLikeHost(s: String): Boolean {
        if (s.any { it.isWhitespace() }) return false
        val hostPort = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "")
        if (port.isNotEmpty() && port.toIntOrNull() == null) return false
        if (host.isEmpty()) return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (Patterns.IP_ADDRESS.matcher(host).matches()) return true
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        if (!host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return false
        val tld = host.substringAfterLast('.')
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    fun displayHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (t: Throwable) {
            url
        }
    }
}
