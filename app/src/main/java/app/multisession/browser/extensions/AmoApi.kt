package app.multisession.browser.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One add-on as reported by the official AMO (addons.mozilla.org) v5 REST API. */
data class AmoAddon(
    val slug: String,
    val name: String,
    val summary: String,
    val version: String,
    val users: Long,
    val permissions: List<String>,
    /** Direct .xpi download URL (`current_version.file.url`) - what [ExtensionManager.install] takes. */
    val xpiUrl: String?,
    val iconUrl: String?,
    val pageUrl: String,
)

/**
 * Minimal, dependency-free client for the official addons.mozilla.org (AMO) v5 REST API.
 *
 * Why it exists: add-on detail pages only expose their "Add to Firefox" button to top-level AMO
 * content with `navigator.mozAddonManager`, and GeckoView does not expose that API here, so the
 * button can render disabled. This offers the same install through the documented public API
 * instead - and ONLY through it: the .xpi still goes to [ExtensionManager.install], where Gecko
 * validates the manifest and the Mozilla signature. Nothing is spoofed (no fabricated Firefox
 * identifiers, honest requests) and no security check is bypassed.
 *
 * Endpoints (both verified against the live API while implementing v2.1.6):
 *  - GET https://addons.mozilla.org/api/v5/addons/search/?q=&lt;query&gt;&platform=android&lang=en-US&page=1
 *  - GET https://addons.mozilla.org/api/v5/addons/addon/&lt;slug&gt;/?platform=android&lang=en-US
 *
 * All functions are blocking (socket + JSON) and throw on failure: call them from
 * `Dispatchers.IO` inside `runCatching`, never from the main thread.
 */
object AmoApi {

    private const val TAG = "AmoApi"
    private const val BASE = "https://addons.mozilla.org/api/v5/addons"
    private const val LANG = "en-US"
    private const val TIMEOUT_MS = 15_000

    private val SLUG = Regex("^[a-z0-9-]+$")
    private val SLUG_PATH = Regex("""/addon/([a-z0-9-]+)/?$""")

    /** Blocking GET that parses a JSON object; throws on HTTP or parse failure (callers wrap it). */
    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("AMO returned HTTP $code")
            return conn.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking: one add-on by slug (fresh .xpi URL, permissions, description). */
    fun detail(slug: String): AmoAddon? {
        if (!SLUG.matches(slug)) return null
        return get("$BASE/addon/$slug/?platform=android&lang=$LANG").toAddon()
    }

    /**
     * The add-on slug when [url] is an addons.mozilla.org add-on detail page (with an optional
     * locale and `firefox`/`android` path segment), else null. Used to offer "Install add-on from
     * this page" on AMO pages whose own install button is disabled.
     */
    fun slugFromPage(url: String?): String? {
        val u = url ?: return null
        if (!u.startsWith("https://addons.mozilla.org/")) return null
        val path = u.substringBefore('?').substringBefore('#')
        return SLUG_PATH.find(path)?.groupValues?.get(1)?.lowercase()
    }

    private fun JSONObject.toAddon(): AmoAddon? {
        val slug = optString("slug")
        if (slug.isEmpty()) return null
        val locale = optString("default_locale", "en-US")
        val version = optJSONObject("current_version")
        val file = version?.optJSONObject("file")
        val permissions = file?.optJSONArray("permissions").stringList() + file?.optJSONArray("host_permissions").stringList()
        return AmoAddon(
            slug = slug,
            name = optJSONObject("name").localized(locale).ifEmpty { slug },
            summary = optJSONObject("summary").localized(locale),
            version = version?.optString("version") ?: "",
            users = optLong("average_daily_users"),
            permissions = permissions,
            xpiUrl = file?.optString("url")?.takeIf { it.isNotBlank() },
            iconUrl = optString("icon_url").takeIf { it.isNotBlank() },
            pageUrl = optString("url"),
        )
    }

    /** AMO returns name/summary/description as `{locale: text}`; prefer the requested locale, then
     *  the add-on's default locale, then whatever the API gave us. */
    private fun JSONObject?.localized(defaultLocale: String): String {
        this ?: return ""
        optString(defaultLocale).takeIf { it.isNotBlank() }?.let { return it }
        val keys = keys()
        while (keys.hasNext()) optString(keys.next()).takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun JSONArray?.stringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
