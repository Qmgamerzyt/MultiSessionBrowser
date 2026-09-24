package app.multisession.browser.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Single access point for app configuration (backed by the default SharedPreferences,
 * which is also what the Settings screen edits). Nothing sensitive is stored here.
 */
object Prefs {
    const val KEY_SEARCH_ENGINE = "search_engine"
    const val KEY_CUSTOM_SEARCH = "custom_search_url"
    const val KEY_HOMEPAGE = "homepage"
    const val KEY_JAVASCRIPT = "javascript_enabled"
    const val KEY_THIRD_PARTY_COOKIES = "third_party_cookies"
    const val KEY_UA_MODE = "user_agent_mode"
    const val KEY_CUSTOM_UA = "custom_user_agent"
    const val KEY_THEME = "theme"
    const val KEY_ASK_DOWNLOAD = "ask_before_download"
    const val KEY_AUTOPLAY = "media_autoplay"
    const val KEY_ZOOM = "zoom_enabled"
    const val KEY_SAFE_BROWSING = "safe_browsing"
    const val KEY_OPEN_EXTERNAL_APPS = "open_external_apps"
    const val KEY_LIVE_TAB_LIMIT = "live_tab_limit"
    const val KEY_ACTIVE_SESSION = "active_session_id"
    const val KEY_HUD_VISIBLE = "hud_visible"
    const val KEY_HUD_ITEMS = "hud_items"
    const val KEY_WEBRTC_COMPAT = "webrtc_compat"
    const val KEY_IDLE_HIBERNATE = "idle_hibernate_minutes"

    private const val DDG = "https://duckduckgo.com/?q=%s"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun searchUrlFor(query: String): String {
        val template = when (sp.getString(KEY_SEARCH_ENGINE, "google")) {
            "google" -> "https://www.google.com/search?q=%s"
            "bing" -> "https://www.bing.com/search?q=%s"
            "custom" -> sp.getString(KEY_CUSTOM_SEARCH, null)?.takeIf { it.contains("%s") } ?: DDG
            else -> DDG
        }
        return template.replace("%s", Uri.encode(query))
    }

    val homepage: String
        get() = sp.getString(KEY_HOMEPAGE, "")?.trim()?.takeIf { it.isNotEmpty() } ?: UrlUtils.START_PAGE

    val javaScriptEnabled: Boolean get() = sp.getBoolean(KEY_JAVASCRIPT, true)
    val thirdPartyCookies: Boolean get() = sp.getBoolean(KEY_THIRD_PARTY_COOKIES, true)
    val uaMode: String get() = sp.getString(KEY_UA_MODE, "default") ?: "default"
    val customUserAgent: String get() = sp.getString(KEY_CUSTOM_UA, "") ?: ""
    val askBeforeDownload: Boolean get() = sp.getBoolean(KEY_ASK_DOWNLOAD, true)
    val mediaAutoplay: Boolean get() = sp.getBoolean(KEY_AUTOPLAY, false)
    val zoomEnabled: Boolean get() = sp.getBoolean(KEY_ZOOM, true)
    val safeBrowsing: Boolean get() = sp.getBoolean(KEY_SAFE_BROWSING, true)
    val openExternalApps: Boolean get() = sp.getBoolean(KEY_OPEN_EXTERNAL_APPS, true)
    /** WebRTC device compatibility prefs for Gecko (audio output enumeration / full-duplex audio). Default on. */
    val webrtcCompat: Boolean get() = sp.getBoolean(KEY_WEBRTC_COMPAT, true)

    /** Maximum number of open GeckoSessions kept at once (active tab included). */
    val liveTabLimit: Int
        get() = (sp.getString(KEY_LIVE_TAB_LIMIT, "6") ?: "6").toIntOrNull()?.coerceIn(1, 30) ?: 6

    /** Background tabs unused for this many minutes release their GeckoSession (0 = only the live-tab limit applies). */
    val idleHibernateMinutes: Int
        get() = (sp.getString(KEY_IDLE_HIBERNATE, "30") ?: "30").toIntOrNull()?.coerceIn(0, 720) ?: 30

    var activeSessionId: String?
        get() = sp.getString(KEY_ACTIVE_SESSION, null)
        set(value) = sp.edit().putString(KEY_ACTIVE_SESSION, value).apply()

    var hudVisible: Boolean
        get() = sp.getBoolean(KEY_HUD_VISIBLE, false)
        set(value) = sp.edit().putBoolean(KEY_HUD_VISIBLE, value).apply()

    /** Comma separated HUD item keys in display order (see ui.browser.HudController).
     *  Values are filtered to [DEFAULT_HUD_KEYS] so preferences saved by older versions (2.1.2 and
     *  earlier, which still contained toolbar/menu duplicates) cannot bring the redundant actions back. */
    var hudItems: List<String>
        get() = (sp.getString(KEY_HUD_ITEMS, null) ?: DEFAULT_HUD)
            .split(',').map { it.trim() }
            .filter { it in DEFAULT_HUD_KEYS }
            .ifEmpty { DEFAULT_HUD_KEYS }
        set(value) = sp.edit().putString(KEY_HUD_ITEMS, value.joinToString(",")).apply()

    /** The customizable HUD set: only actions NOT already reachable from the toolbar or the app menu
     *  (bug fix: de-duplicated). "fullscreen" (the app's toolbar toggle) is excluded from the pill but
     *  the full original option set is still shown by the fullscreen nub popup (see HudController). */
    const val DEFAULT_HUD = "top,bottom,closetab"
    val DEFAULT_HUD_KEYS: List<String> get() = DEFAULT_HUD.split(',')

    fun nightMode(): Int = when (sp.getString(KEY_THEME, "system")) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
}
