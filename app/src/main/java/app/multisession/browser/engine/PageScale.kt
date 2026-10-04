package app.multisession.browser.engine

import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.permissions.PermissionValue
import app.multisession.browser.permissions.SitePermissionStore
import app.multisession.browser.permissions.SitePermissionType
import app.multisession.browser.tabs.Tab

/**
 * Page zoom, two levels deep:
 *  - a **global default** (Settings -> "Page scale", `Prefs.pageScaleDefault`, 100% out of the box)
 *  - an optional **per-site rule** stored in the existing `site_permissions` table under
 *    [SitePermissionType.PAGE_SCALE], keyed by `(sessionId, origin)` exactly like the desktop-site
 *    rule. The value *is* the percent (50..200); "no rule" is [PermissionValue.ASK], which is what
 *    makes `set(..., ASK)` a delete and gives you the global default again. No Room migration.
 *
 * GeckoView 155 exposes no page-zoom API to Java (the only knobs are `GeckoRuntimeSettings
 * .setFontSizeFactor`, runtime-wide and text-only, and read-only zoom state), so v2.1.10 applies the
 * percent through the patched omni.js: [TabManager.setPageScale] issues a `moz-scale:<percent>` URI
 * that `GeckoViewNavigation` intercepts and turns into `browsingContext.fullZoom` - the same
 * per-document full zoom desktop Firefox uses, applied natively.
 *
 * Why not the previous CSS-`zoom` approach (a `javascript:` load through [TabManager.runScript])?
 * Because that load runs INSIDE the page, where the Content-Security-Policy applies: Discord's
 * `script-src` has no `'unsafe-inline'`, so the zoom silently did nothing there - the Plan-2 "scale
 * does nothing on some sites" root cause. `moz-scale:` never enters the content process, so no page policy can
 * block it, and it creates no history entry and no load callbacks (no progress-bar flash, no feedback
 * loop): [Tab.appliedScale] is the only record of what was set.
 *
 * The engine's zoom PERSISTS on the browsing context across documents - unlike in-page CSS, which
 * every fresh document starts without - so [applyFor] re-issues explicitly whenever [Tab.appliedScale]
 * is null (fresh document, restored session, navigation) or the wanted percent changed, and it pushes
 * 100% onto documents that must not be scaled at all (start page, error page, privileged shells).
 */
object PageScale {

    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val STEP = 10
    const val DEFAULT_PERCENT = 100

    private const val TAG = "PageScale"

    /** 50, 60, 70 … 200 — the single source of truth for the picker and for Settings. */
    val PERCENTS: List<Int> = generateSequence(MIN_PERCENT) { it + STEP }.takeWhile { it <= MAX_PERCENT }.toList()

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    private val SKIPPED_SCHEMES = setOf("about", "resource", "moz-extension", "javascript", "view-source", "chrome")

    /**
     * Documents the zoom must not touch: the start page and the native error page are app-owned
     * UI, and `about:` / `resource://` / `moz-extension://` are privileged shells with their own layout.
     * `http`, `https`, `file` and `data` documents are scaled. (Anything not scalable is explicitly
     * brought to [DEFAULT_PERCENT] instead of left at whatever zoom the previous document had.)
     */
    fun isScalable(tab: Tab): Boolean {
        val url = tab.url
        if (url.isBlank() || tab.isStartPage || tab.error != null) return false
        return url.substringBefore(':', "").lowercase() !in SKIPPED_SCHEMES
    }

    /** The percent this tab wants: its per-site rule when it has one, otherwise the global default. */
    fun percentFor(core: BrowserCore, tab: Tab): Int {
        val origin = SitePermissionStore.originOf(tab.url)
        val rule = origin?.let { core.sitePermissions.get(tab.sessionId, it, SitePermissionType.PAGE_SCALE) } ?: 0
        // Values outside 50..200 are the permission sentinels (ALLOW/BLOCK/ASK), i.e. "no rule".
        return clamp(if (rule in MIN_PERCENT..MAX_PERCENT) rule else Prefs.pageScaleDefault)
    }

    /**
     * Brings [tab]'s browsing context to its wanted percent (see the class KDoc for why that is an
     * explicit set, not a diff). @return true when a set was actually issued.
     */
    fun applyFor(core: BrowserCore, tab: Tab): Boolean {
        // While the page is still loading, touching it now would race that load's own start callback
        // (see TabDelegates.onPageStart): the document is about to settle anyway, so onPageStop does it.
        if (tab.isLoading) return false
        val want = if (isScalable(tab)) percentFor(core, tab) else DEFAULT_PERCENT
        // null = "unknown or expired": the context may still carry the previous document's zoom, so an
        // explicit set is required even when `want` is the default.
        val have = tab.appliedScale
        if (have != null && have == want) return false
        if (!core.tabs.setPageScale(tab, want)) return false
        tab.appliedScale = want
        AppLog.i(TAG, "scale=$want% for ${SitePermissionStore.originOf(tab.url) ?: "internal page"}")
        return true
    }

    /**
     * Re-applies the effective scale to whichever tab is on screen — used after the global default
     * changes in Settings, where there is no tab to point at. Tabs in the background are brought up to
     * date when they become displayed (TabManager.setDisplayed).
     */
    fun applyDisplayed(core: BrowserCore) {
        val tab = core.tabs.displayedTabOrNull() ?: return
        applyFor(core, tab)
    }

    /** Remembers [percent] as this site's rule for [tab]'s session. */
    fun setPerSite(core: BrowserCore, tab: Tab, percent: Int) {
        val origin = SitePermissionStore.originOf(tab.url) ?: return
        core.sitePermissions.set(tab.sessionId, origin, SitePermissionType.PAGE_SCALE, clamp(percent))
    }

    /** Back to the global default for this site: writing ASK deletes the row (see SitePermissionStore). */
    fun clearPerSite(core: BrowserCore, tab: Tab) {
        val origin = SitePermissionStore.originOf(tab.url) ?: return
        core.sitePermissions.set(tab.sessionId, origin, SitePermissionType.PAGE_SCALE, PermissionValue.ASK)
    }
}
