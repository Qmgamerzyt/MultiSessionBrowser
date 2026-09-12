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

    /**
     * Address-bar resolution:
     *  - explicit scheme -> load as-is (javascript: and file: are refused)
     *  - looks like a host/IP -> https:// (HTTPS-first; user can type http:// explicitly)
     *  - anything else -> configured search engine
     */
    fun resolveInput(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return START_PAGE
        val lower = input.lowercase()
        if (lower == START_PAGE || lower == "about:blank") return START_PAGE
        if (lower.startsWith("file:")) {
            return Prefs.searchUrlFor(input)
        }
        val passThrough = listOf("http://", "https://", "content://", "data:", "about:", "intent:")
        if (passThrough.any { lower.startsWith(it) }) return input
        if (looksLikeHost(input)) return "https://$input"
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
