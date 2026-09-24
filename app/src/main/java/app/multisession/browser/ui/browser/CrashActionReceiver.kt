package app.multisession.browser.ui.browser

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import app.multisession.browser.R

/** Handles the Copy / Share actions of the crash-trace notification (see [CrashTraceNotifier]).
 *  The trace arrives embedded in the intent, so it works even when the app process was killed after
 *  the notification was posted. Manifest-registered, exported=false, explicit intents only. */
class CrashActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(CrashTraceNotifier.EXTRA_TRACE) ?: return
        when (intent.action) {
            CrashTraceNotifier.ACTION_COPY -> {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("crash trace", text))
                Toast.makeText(context.applicationContext, R.string.copied, Toast.LENGTH_SHORT).show()
                NotificationManagerCompat.from(context).cancel(CrashTraceNotifier.NOTIFICATION_ID)
            }
            CrashTraceNotifier.ACTION_SHARE -> {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
                NotificationManagerCompat.from(context).cancel(CrashTraceNotifier.NOTIFICATION_ID)
            }
        }
    }
}
