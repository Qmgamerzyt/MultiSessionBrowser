package app.multisession.browser.downloads

import android.Manifest
import android.app.Notification
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
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.data.db.DownloadEntity
import app.multisession.browser.ui.downloads.DownloadsActivity

/**
 * Android download notifications (v2.1.7, issue E).
 *
 * The browser used to report downloads only with a Snackbar that vanished while the app was in the
 * foreground; leaving the app gave no feedback at all. Every state change published by
 * [AppDownloadManager] now also lands here:
 *
 *  - running        -> one ongoing notification per download with a progress bar (indeterminate
 *    while the server did not send a length), Pause + Cancel actions and a tap that opens the
 *    Downloads screen (v2.1.10 A9: Pause lets you stop a transfer without killing it),
 *  - pending        -> the same, Cancel only (nothing to pause before the transfer starts),
 *  - paused           -> the same notification, no longer ongoing, with Resume + Cancel actions
 *    (v2.1.10 A9: Cancel alongside Resume - a paused download could not be discarded from here),
 *  - completed/failed -> replaced by an auto-cancelling result notification (v2.1.10 A9: the
 *    completed one carries Open + Close actions instead of only a tap target),
 *  - cancelled        -> the ongoing notification is simply removed.
 *
 * Two channels: progress is LOW (no sound, no badge, no heads-up) and finished downloads are DEFAULT.
 * Nothing is posted when the app may not post notifications (POST_NOTIFICATIONS on 13+, app toggle).
 */
object DownloadNotifier {

    const val ACTION_CANCEL = "app.multisession.browser.action.DOWNLOAD_CANCEL"
    const val ACTION_RESUME = "app.multisession.browser.action.DOWNLOAD_RESUME"
    const val ACTION_PAUSE = "app.multisession.browser.action.DOWNLOAD_PAUSE"
    const val ACTION_DISMISS = "app.multisession.browser.action.DOWNLOAD_DISMISS"
    const val EXTRA_DOWNLOAD_ID = "app.multisession.browser.download_id"

    private const val CHANNEL_ACTIVE = "downloads_active"
    private const val CHANNEL_FINISHED = "downloads_finished"
    private const val RC_OPEN = 0xD01
    private const val RC_ACTION = 0xD02
    private const val RC_OPEN_FILE = 0xD03

    /** Progress is published every ~300 ms; this keeps the notification update at one IPC per second
     *  (and never re-sends an unchanged percentage). Finished states always go through immediately. */
    private val lastSent = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /** Called by [AppDownloadManager] for every state/progress change. Safe from any thread. */
    fun onDownloadChanged(context: Context, d: DownloadEntity) {
        val app = context.applicationContext
        if (!canPost(app)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannels(app)
        val manager = NotificationManagerCompat.from(app)
        val id = d.id.hashCode()
        if (d.status == DownloadStatus.RUNNING) {
            val percent = if (d.totalBytes > 0) ((d.downloadedBytes * 100) / d.totalBytes).toInt().coerceIn(0, 100) else -1
            val now = System.currentTimeMillis()
            val prev = lastSent[d.id]
            if (prev != null && prev.first == percent && now - prev.second < 1000L) return
            lastSent[d.id] = percent to now
        } else if (DownloadStatus.isFinished(d.status)) {
            lastSent.remove(d.id)
        }
        when (d.status) {
            DownloadStatus.PENDING, DownloadStatus.RUNNING ->
                manager.notify(id, active(app, d, ongoing = true))
            DownloadStatus.PAUSED ->
                manager.notify(id, active(app, d, ongoing = false))
            DownloadStatus.COMPLETED -> {
                manager.cancel(id)
                manager.notify(id, finished(app, d, ok = true))
            }
            DownloadStatus.FAILED -> {
                manager.cancel(id)
                manager.notify(id, finished(app, d, ok = false))
            }
            DownloadStatus.CANCELLED -> manager.cancel(id)
        }
    }

    // ------------------------------------------------------------------ builders

    private fun active(context: Context, d: DownloadEntity, ongoing: Boolean): Notification {
        val paused = d.status == DownloadStatus.PAUSED
        val b = NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(
                if (paused) d.fileName else context.getString(R.string.dl_notif_downloading, d.fileName)
            )
            .setContentIntent(openDownloads(context))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
        if (d.totalBytes > 0) {
            val percent = ((d.downloadedBytes * 100) / d.totalBytes).toInt().coerceIn(0, 100)
            b.setProgress(100, percent, false)
            b.setContentText(context.getString(R.string.dl_size_fmt, formatBytes(context, d.downloadedBytes), formatBytes(context, d.totalBytes)))
        } else {
            b.setProgress(0, 0, true)   // unknown length: indeterminate, never a fake 0%
        }
        if (paused) {
            b.setContentText(context.getString(R.string.dl_status_paused))
            b.setProgress(0, 0, false)
            b.addAction(R.drawable.ic_play, context.getString(R.string.dl_resume), action(context, ACTION_RESUME, d.id))
        } else if (d.status == DownloadStatus.RUNNING) {
            // v2.1.10 (A9): pause without losing the transfer. Pending has no Pause - nothing is
            // running yet, and pause() only accepts active (pending/running) states it can honor.
            b.addAction(R.drawable.ic_pause, context.getString(R.string.dl_pause), action(context, ACTION_PAUSE, d.id))
        }
        b.addAction(R.drawable.ic_close, context.getString(R.string.dl_cancel), action(context, ACTION_CANCEL, d.id))
        return b.build()
    }

    private fun finished(context: Context, d: DownloadEntity, ok: Boolean): Notification {
        val reason = d.error?.takeIf { it.isNotBlank() }?.take(200)
        val b = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(if (ok) R.drawable.ic_download else R.drawable.ic_error)
            .setContentTitle(
                context.getString(if (ok) R.string.dl_notif_done else R.string.dl_notif_failed, d.fileName)
            )
            .setContentText(if (!ok && reason != null) reason else context.getString(R.string.dl_notif_text))
            .setContentIntent(openDownloads(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (ok) {
            // v2.1.10 (A9): actionable result - open the file directly, or dismiss the card.
            // Open is an activity PendingIntent (no receiver hop); it is skipped when the file
            // is already gone (openIntent -> null). Close only cancels this notification.
            val openIntent = BrowserApp.core().downloads.openIntent(d)
            if (openIntent != null) {
                b.addAction(
                    R.drawable.ic_open_in_new,
                    context.getString(R.string.dl_open),
                    PendingIntent.getActivity(
                        context, RC_OPEN_FILE + d.id.hashCode(), openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            b.addAction(
                R.drawable.ic_close,
                context.getString(R.string.dl_close),
                action(context, ACTION_DISMISS, d.id),
            )
        }
        return b.build()
    }

    // ------------------------------------------------------------------ plumbing

    private fun formatBytes(context: Context, bytes: Long): String =
        android.text.format.Formatter.formatFileSize(context, bytes)

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ACTIVE) == null) {
            val c = NotificationChannel(CHANNEL_ACTIVE, context.getString(R.string.dl_channel_progress), NotificationManager.IMPORTANCE_LOW)
            c.description = context.getString(R.string.dl_channel_progress_desc)
            nm.createNotificationChannel(c)
        }
        if (nm.getNotificationChannel(CHANNEL_FINISHED) == null) {
            val c = NotificationChannel(CHANNEL_FINISHED, context.getString(R.string.dl_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
            c.description = context.getString(R.string.dl_channel_done_desc)
            nm.createNotificationChannel(c)
        }
    }

    private fun openDownloads(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_OPEN,
            Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(context: Context, action: String, downloadId: String): PendingIntent =
        PendingIntent.getBroadcast(
            // Request code must differ per download: two PendingIntents that differ only in extras are
            // matched as "the same" by Android, and FLAG_UPDATE_CURRENT would then overwrite the id.
            context, RC_ACTION + downloadId.hashCode(),
            Intent(context, DownloadActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
