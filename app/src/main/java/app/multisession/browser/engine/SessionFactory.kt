package app.multisession.browser.engine

import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.Prefs
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.tabs.Tab
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Creates fully configured (but NOT yet opened) GeckoSessions for tabs.
 * Order matters: the session's contextId / private mode are immutable after open(), so they are
 * fixed in the settings builder; user-agent / viewport can be changed later via [applyTabSettings].
 */
object SessionFactory {
    private const val TAG = "SessionFactory"

    fun create(core: BrowserCore, tab: Tab, session: SessionEntity): GeckoSession {
        val desktop = tab.desktopMode || session.desktopMode
        val builder = GeckoSessionSettings.Builder()
            // *** Session isolation ***: partitions cookies / storage / cache / permissions per browser session.
            .contextId(core.isolation.contextId(session))
            .usePrivateMode(session.isPrivate)
            .useTrackingProtection(true)
            .suspendMediaWhenInactive(false)     // background tabs keep playing audio/voice (Discord calls)
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        customUserAgent()?.let { builder.userAgentOverride(it) }

        val gs = GeckoSession(builder.build())
        attachDelegates(core, tab, gs)
        AppLog.d(TAG, "GeckoSession configured tab=${tab.id.take(8)} context=${core.isolation.contextId(session).take(16)} private=${session.isPrivate}")
        return gs
    }

    /** Wires every engine callback of [gs] to [tab]. Also used for popup sessions handed to us by Gecko. */
    fun attachDelegates(core: BrowserCore, tab: Tab, gs: GeckoSession) {
        val d = TabDelegates(core, tab)
        gs.navigationDelegate = d
        gs.progressDelegate = d
        gs.contentDelegate = d
        gs.permissionDelegate = d
        gs.mediaSessionDelegate = d       // play/pause state -> tabs playing media are not hibernated automatically
        gs.scrollDelegate = d             // scroll offset -> pull-to-refresh only at the very top (v2.1.7)
        gs.promptDelegate = BrowserPromptDelegate(core, tab)
        core.extensions.attachToSession(gs, tab)   // browser.tabs.remove/update + per-tab actions for installed extensions
    }

    /** Re-applies per-tab settings that may change at runtime (desktop mode toggle, UA preference). */
    fun applyTabSettings(tab: Tab, session: SessionEntity) {
        val gs = tab.geckoSession ?: return
        val desktop = tab.desktopMode || session.desktopMode
        gs.settings.userAgentMode = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        gs.settings.viewportMode = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        gs.settings.userAgentOverride = customUserAgent()
    }

    private fun customUserAgent(): String? =
        if (Prefs.uaMode == "custom" && Prefs.customUserAgent.isNotBlank()) Prefs.customUserAgent else null
}
