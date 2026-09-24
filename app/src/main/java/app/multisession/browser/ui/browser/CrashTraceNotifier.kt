package app.multisession.browser.ui.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.multisession.browser.R

/**
 * Crash-trace notification (2.1.4): replaces the former "screenshot this dialog" screen. The trace is
 * written by BrowserApp's uncaught-exception handler (filesDir/crash_trace.txt) and posted here on the
 * next launch with **Copy** and **Share** actions, so the exact throwing frame can be reported without
 * adb (BUGFIX_TABSSHEET_CRASH.md). The text is embedded in the action intents, so Copy/Share keep
 * working even after the process is killed; the file is consumed when Android accepts the notification,
 * exactly as the dialog consumed it on display. If notifications cannot be posted the caller keeps the
 * file and retries on the next launch - never a dialog.
 */
object CrashTraceNotifier {

    const val CHANNEL_ID = "crash_reports"
    const val NOTIFICATION_ID = 0xC2A5
    const val EXTRA_TRACE = "app.multisession.browser.CRASH_TRACE"
    const val ACTION_COPY = "app.multisession.browser.action.CRASH_COPY"
    const val ACTION_SHARE = "app.multisession.browser.action.CRASH_SHARE"
    private const val RC_OPEN = 0x2A5
    private const val RC_COPY = 0x2C0
    private const val RC_SHARE = 0x25A

    /** Caps the trace carried by the intents: comfortably below the ~1MB binder transaction limit. */
    private const val EXTRA_TRACE_MAX = 256_000

    /** Posts the trace notification; true only when Android actually accepted the notification. */
    fun post(context: Context, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel(context)
        if (!canPost(context)) return false
        val app = context.applicationContext
        val preview = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(150) ?: ""
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(context.getString(R.string.crash_captured_title))
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(4000)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openIntent(app))
            .addAction(
                R.drawable.ic_content_copy,
                context.getString(R.string.crash_copy),
                actionIntent(app, ACTION_COPY, text, RC_COPY)
            )
            .addAction(
                R.drawable.ic_share,
                context.getString(R.string.share),
                actionIntent(app, ACTION_SHARE, text, RC_SHARE)
            )
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            true
        } catch (t: Throwable) {
            // Never crash the app while reporting a previous crash; caller keeps the file and retries.
            false
        }
    }

    /** True when Android lets this app post notifications (POST_NOTIFICATIONS on 13+, app toggle). */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.crash_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = context.getString(R.string.crash_channel_desc)
        nm.createNotificationChannel(channel)
    }

    private fun openIntent(context: Context): PendingIntent {
        val open = Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, RC_OPEN, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, action: String, text: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CrashActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TRACE, text.take(EXTRA_TRACE_MAX))
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
