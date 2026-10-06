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
 * Android download notifications.
 *
 * v2.2.0-beta-4 (user-requested): the system shade never carries a progress bar without numbers:
 *
 *  - running/pending -> NOTHING posted, and any lingering ongoing notification (older build or a
 *    race) is cleared on EVERY publish - it can never sit in the shade forever,
 *  - paused           -> non-ongoing notification WITH the determinate bar and "Paused · X of Y"
 *    so the shade always says how much is done out of how much, + Resume/Cancel actions,
 *  - completed/failed -> auto-cancelling result notification with Open + Close actions,
 *  - cancelled        -> the notification is removed.
 *
 * Two channels remain for the states that still post. Nothing is posted when the app may not post
 * notifications (POST_NOTIFICATIONS on 13+, app toggle).
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
        // v2.1.10 (perf): the stall check runs BEFORE the permission/channel binder calls - a
        // progress tick that would not be sent must cost nothing (the checks used to run on every
        // ~300 ms callback even when the percentage had not moved within the last second).
        val percent =
            if (d.status == DownloadStatus.RUNNING && d.totalBytes > 0)
                ((d.downloadedBytes * 100) / d.totalBytes).toInt().coerceIn(0, 100)
            else -1
        if (d.status == DownloadStatus.RUNNING) {
            val now = System.currentTimeMillis()
            val prev = lastSent[d.id]
            if (prev != null && prev.first == percent && now - prev.second < 1000L) return
        } else if (DownloadStatus.isFinished(d.status)) {
            lastSent.remove(d.id)
        }
        val manager = NotificationManagerCompat.from(app)
        val id = d.id.hashCode()
        when (d.status) {
            // v2.2.0-beta-4: NO progress notification - foreground feedback is the top download
            // card - and ALWAYS clear: a lingering ongoing bar from an older build (or a race) is
            // killed on every publish, not just the first one (the old cancel-once ALSO died at
            // the permission check below, so a denied POST_NOTIFICATIONS kept the bar forever).
            // The throttle above caps this at ~1 IPC/s. cancel() needs no permission, so this
            // runs BEFORE canPost.
            DownloadStatus.PENDING, DownloadStatus.RUNNING -> {
                lastSent.put(d.id, percent to System.currentTimeMillis())
                manager.cancel(id)
                return
            }
            else -> {}
        }
        if (!canPost(app)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannels(app)
        when (d.status) {
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
            // v2.2.0-beta-4: paused KEEPS the determinate bar (set above when the size is known)
            // and states the numbers - "how much done out of" must be readable in the shade.
            if (d.totalBytes > 0) {
                b.setContentText(
                    context.getString(
                        R.string.dl_paused_size,
                        formatBytes(context, d.downloadedBytes),
                        formatBytes(context, d.totalBytes),
                    )
                )
            } else {
                b.setContentText(context.getString(R.string.dl_status_paused))
                b.setProgress(0, 0, false)   // unknown length: no spinning bar behind "Paused"
            }
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

    /** True once both channels were probed in this process: getNotificationChannel is a binder call,
     *  and it used to run on every progress tick. (Deleting a channel in system settings while the
     *  app runs only loses notifications the user has already opted out of.) */
    @Volatile private var channelsReady = false

    private fun ensureChannels(context: Context) {
        if (channelsReady) return
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
        channelsReady = true
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
