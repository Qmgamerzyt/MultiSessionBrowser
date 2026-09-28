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
 * GeckoView 155 has no page-zoom API at all — the only knobs are `GeckoRuntimeSettings
 * .setFontSizeFactor` (runtime-wide and text-only, so it would ignore images and break layout) and
 * read-only zoom state — so the percent is applied in-page as CSS `zoom` on `document.documentElement`
 * through the same `javascript:` loader the bookmarklets use ([TabManager.runInternalScript]).
 * `zoom` (not `transform: scale`) keeps real layout: media queries, reflow and hit-testing all follow.
 *
 * Three properties matter for correctness and are enforced by [Tab.internalScript] /
 * [Tab.internalLoad] in [TabDelegates]:
 *  1. **No reload.** Nothing but the in-page script runs; the document, its scroll and its history
 *     are untouched.
 *  2. **No interference.** The scale load has its own slot, so a bookmarklet queued at the same
 *     moment can never consume it (or be consumed by it), and page-initiated `javascript:` stays denied.
 *  3. **No feedback loop.** The load's own start/progress/stop callbacks are swallowed, so
 *     `onPageStop` cannot apply the scale again, and the progress bar never flashes.
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
     * Documents the zoom script must never touch: the start page and the native error page are app-owned
     * UI, and `about:` / `resource://` / `moz-extension://` are privileged shells with their own layout.
     * `http`, `https`, `file` and `data` documents are scaled.
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

    /** The in-page script for [percent]. Single line, no `#`, no newlines — [TabManager] encodes it. */
    private fun script(percent: Int): String {
        val z = clamp(percent) / 100.0
        return "var d=document.documentElement;if(d){var o=parseFloat(d.style.zoom)||1;var n=$z;if(o!==n){" +
            "var x=window.scrollX||0,y=window.scrollY||0;d.style.zoom=n;window.scrollTo(x*n/o,y*n/o);}}"
    }

    /**
     * Brings [tab]'s document to its wanted percent, issuing an in-page load only when the document is
     * not already there. @return true when a script was actually issued.
     */
    fun applyFor(core: BrowserCore, tab: Tab): Boolean {
        if (!isScalable(tab)) return false
        // While the page is still loading, touching it now would race that load's own start callback
        // (see TabDelegates.onPageStart): the document is about to settle anyway, so onPageStop does it.
        if (tab.isLoading) return false
        val want = percentFor(core, tab)
        // A document with no recorded scale is at the CSS default of 100%: nothing to do for the
        // default, and a single (idempotent) script for anything else.
        val have = tab.appliedScale ?: DEFAULT_PERCENT
        if (have == want) return false
        if (!core.tabs.runInternalScript(tab, script(want))) return false
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
        val tab = core.tabs.displayedTab ?: return
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
