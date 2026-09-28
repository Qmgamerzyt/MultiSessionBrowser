package app.multisession.browser.permissions

import android.Manifest
import android.net.Uri
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.data.db.SitePermissionEntity

/** Permission states. Values are identical to GeckoView's ContentPermission.VALUE_ALLOW / VALUE_DENY / VALUE_PROMPT. */
object PermissionValue {
    const val ALLOW = 1
    const val BLOCK = 2
    const val ASK = 3
}

// GeckoSession.PermissionDelegate.PERMISSION_* : the values ContentPermission.permission carries. They are
// public GeckoView API constants; spelled out here so this file compiles against every GeckoView release
// regardless of which constant *names* a release exposes.
private const val GECKO_GEOLOCATION = 0
private const val GECKO_DESKTOP_NOTIFICATION = 1
private const val GECKO_PERSISTENT_STORAGE = 2
private const val GECKO_XR = 3
private const val GECKO_AUTOPLAY_INAUDIBLE = 4
private const val GECKO_AUTOPLAY_AUDIBLE = 5
private const val GECKO_MEDIA_KEY_SYSTEM_ACCESS = 6
private const val GECKO_TRACKING = 7
private const val GECKO_STORAGE_ACCESS = 8
private const val GECKO_LOCAL_DEVICE_ACCESS = 9
private const val GECKO_LOCAL_NETWORK_ACCESS = 10

/**
 * Site permissions this browser understands.
 *  - [geckoType] is ContentPermission.permission for content permissions that arrive through
 *    PermissionDelegate.onContentPermissionRequest. Gecko persists ALLOW/DENY answers to those in its own
 *    permission manager (per origin + contextId); the app mirrors them in [SitePermissionStore] and edits
 *    Gecko's copy through StorageController.setPermission so the two never disagree.
 *  - Camera / microphone (getUserMedia) have no gecko type: they arrive through onMediaPermissionRequest,
 *    which Gecko never persists - the store is the only memory for them.
 *  - [managed] types appear in the Site permissions dialog; the others are still remembered and reset.
 */
enum class SitePermissionType(
    val key: String,
    val geckoType: Int?,
    val labelRes: Int,
    val managed: Boolean,
    val androidPermissions: List<String>,
) {
    CAMERA("camera", null, R.string.perm_label_camera, true, listOf(Manifest.permission.CAMERA)),
    MICROPHONE("microphone", null, R.string.perm_label_microphone, true, listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION(
        "location", GECKO_GEOLOCATION, R.string.perm_label_location, true,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    NOTIFICATIONS("notifications", GECKO_DESKTOP_NOTIFICATION, R.string.perm_label_notifications, true, emptyList()),
    AUTOPLAY_AUDIBLE("autoplay_audible", GECKO_AUTOPLAY_AUDIBLE, R.string.perm_label_autoplay, true, emptyList()),
    DRM("drm", GECKO_MEDIA_KEY_SYSTEM_ACCESS, R.string.perm_label_drm, true, emptyList()),
    XR("xr", GECKO_XR, R.string.perm_label_xr, false, emptyList()),
    STORAGE_ACCESS("storage_access", GECKO_STORAGE_ACCESS, R.string.perm_label_storage_access, false, emptyList()),
    LOCAL_DEVICE_ACCESS("local_device", GECKO_LOCAL_DEVICE_ACCESS, R.string.perm_label_local_device, false, emptyList()),
    LOCAL_NETWORK_ACCESS("local_network", GECKO_LOCAL_NETWORK_ACCESS, R.string.perm_label_local_network, false, emptyList()),
    /** Not a web permission: a per-site "always load as desktop site" rule (ALLOW = desktop UA/viewport, BLOCK = force mobile). Applied in TabDelegates.onLoadRequest. */
    DESKTOP_SITE("desktop_site", null, R.string.perm_label_desktop_site, true, emptyList()),
    /**
     * Not a web permission: a per-site page zoom. The stored value *is* the percent (50..200) rather than
     * ALLOW/BLOCK, and ASK (the default "no rule" value) means "follow the global default in Settings" —
     * which is why `set(..., ASK)` deleting the row is exactly the reset. Never managed in the Site
     * permissions dialog: zoom is offered as its own app-menu control (engine/PageScale).
     */
    PAGE_SCALE("page_scale", null, R.string.perm_label_page_scale, false, emptyList());

    companion object {
        /** Content permission types answered without any user decision (see BrowserActivity.onContentPermissionRequest). */
        const val GECKO_AUTOPLAY_INAUDIBLE_TYPE = GECKO_AUTOPLAY_INAUDIBLE
        const val GECKO_PERSISTENT_STORAGE_TYPE = GECKO_PERSISTENT_STORAGE
        const val GECKO_TRACKING_TYPE = GECKO_TRACKING

        val MANAGED: List<SitePermissionType> = entries.filter { it.managed }

        fun fromGecko(type: Int): SitePermissionType? = entries.firstOrNull { it.geckoType == type }
        fun fromKey(key: String): SitePermissionType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Persistent Allow/Block decisions per (browser session, site origin, permission). Backed by Room
 * (site_permissions table, cascades with the session) and fully cached in memory, so lookups from the
 * engine delegates are synchronous on the main thread. Main-thread only.
 */
class SitePermissionStore(private val core: BrowserCore) {

    private val cache = HashMap<String, Int>()

    private fun key(sessionId: String, origin: String, type: SitePermissionType) = "$sessionId|$origin|${type.key}"

    suspend fun load() {
        val rows = core.repo.sitePermissions.getAll()
        cache.clear()
        rows.forEach { r -> SitePermissionType.fromKey(r.permission)?.let { cache[key(r.sessionId, r.origin, it)] = r.value } }
        AppLog.i(TAG, "Loaded ${rows.size} site permission rules")
    }

    /** [PermissionValue.ALLOW], [PermissionValue.BLOCK] or [PermissionValue.ASK] (default / no rule). */
    fun get(sessionId: String, origin: String, type: SitePermissionType): Int =
        cache[key(sessionId, origin, type)] ?: PermissionValue.ASK

    fun set(sessionId: String, origin: String, type: SitePermissionType, value: Int) {
        val k = key(sessionId, origin, type)
        if (value == PermissionValue.ASK) {
            if (cache.remove(k) == null) return
            core.persist { core.repo.sitePermissions.delete(sessionId, origin, type.key) }
        } else {
            if (cache[k] == value) return
            cache[k] = value
            val row = SitePermissionEntity(sessionId, origin, type.key, value, System.currentTimeMillis())
            core.persist { core.repo.sitePermissions.upsert(row) }
        }
        AppLog.i(TAG, "Permission ${type.key}=$value for $origin in session ${sessionId.take(8)}")
        // Camera / microphone BLOCK must end streams that are running right now, not only future requests:
        // every live page of this site in this session is reloaded (its MediaStream tracks die with the document).
        if (value == PermissionValue.BLOCK && (type == SitePermissionType.CAMERA || type == SitePermissionType.MICROPHONE)) {
            core.tabs.revokeMedia(sessionId, origin)
        }
    }

    /** Back to "Ask" for every permission of [origin] in this session. */
    fun reset(sessionId: String, origin: String) {
        val prefix = "$sessionId|$origin|"
        cache.keys.removeAll { it.startsWith(prefix) }
        core.persist { core.repo.sitePermissions.deleteForOrigin(sessionId, origin) }
    }

    /** Called when a session's site data is cleared or the session is deleted. */
    fun clearSession(sessionId: String) {
        val prefix = "$sessionId|"
        cache.keys.removeAll { it.startsWith(prefix) }
        core.persist { core.repo.sitePermissions.deleteForSession(sessionId) }
    }

    companion object {
        private const val TAG = "SitePerms"

        /** "https://host[:port]" for http(s) URLs; null for anything that cannot own permissions (about:, file:, data:). */
        fun originOf(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val port = uri.port
            val isDefault = port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            return if (isDefault) "$scheme://$host" else "$scheme://$host:$port"
        }
    }
}
