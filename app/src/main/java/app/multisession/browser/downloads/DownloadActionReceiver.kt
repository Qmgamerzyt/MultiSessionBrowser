package app.multisession.browser.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.multisession.browser.BrowserApp

/**
 * Actions of the download notification (v2.1.7, issue E): Cancel and Resume. v2.1.10 (A9) adds
 * Pause (running -> stop the transfer without losing it) and Dismiss (Close on the finished
 * notification: cancels only the card, the entry stays in Downloads). Manifest-registered with
 * exported=false and explicit intents only, so nothing outside the app can drive it. The receiver
 * runs in the default process, where BrowserApp.onCreate() has already built
 * [app.multisession.browser.BrowserCore]. "Open file" is deliberately NOT here: it is an activity
 * PendingIntent built by DownloadNotifier, so no receiver hop (and no ActivityNotFoundException
 * handling) is needed to launch the viewer.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadNotifier.EXTRA_DOWNLOAD_ID) ?: return
        val core = BrowserApp.core()
        when (intent.action) {
            DownloadNotifier.ACTION_CANCEL -> {
                core.downloads.cancel(id)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            }
            DownloadNotifier.ACTION_RESUME -> core.downloads.resume(id)
            DownloadNotifier.ACTION_PAUSE -> core.downloads.pause(id)
            DownloadNotifier.ACTION_DISMISS -> NotificationManagerCompat.from(context).cancel(id.hashCode())
            else -> Unit
        }
    }
}
