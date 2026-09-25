package app.multisession.browser.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.multisession.browser.BrowserApp
import kotlinx.coroutines.launch

/**
 * "Tap to close" action of the Incognito notification (v2.1.7, issue N). Manifest-registered with
 * exported=false and explicit intents only. It deletes the Incognito session exactly like the sessions
 * drawer does (SessionManager.delete -> destroySession + profile data), which is what the notification
 * text promises; the active-session StateFlow then hides the notification through [IncognitoNotifier.update].
 */
class IncognitoCloseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncognitoNotifier.ACTION_CLOSE) return
        val id = intent.getStringExtra(IncognitoNotifier.EXTRA_SESSION_ID) ?: return
        val core = BrowserApp.core()
        if (core.sessions.activeId != id) return     // already switched away: nothing to close
        core.scope.launch { core.sessions.delete(id) }
    }
}
