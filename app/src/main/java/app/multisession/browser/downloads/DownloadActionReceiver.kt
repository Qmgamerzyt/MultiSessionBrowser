package app.multisession.browser.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.multisession.browser.BrowserApp

/**
 * Actions of the download notification (v2.1.7, issue E): Cancel and Resume. Manifest-registered with
 * exported=false and explicit intents only, so nothing outside the app can drive it. The receiver runs
 * in the default process, where BrowserApp.onCreate() has already built [app.multisession.browser.BrowserCore].
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
            else -> Unit
        }
    }
}
