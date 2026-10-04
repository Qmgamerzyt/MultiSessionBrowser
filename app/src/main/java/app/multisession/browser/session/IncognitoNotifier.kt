package app.multisession.browser.session

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
import app.multisession.browser.data.db.SessionEntity

/**
 * Persistent "Incognito session active" notification (v2.1.7, issue N).
 *
 * Why it exists: an Incognito session looks like any other one in the UI, so it was easy to keep
 * browsing privately without noticing. As long as the *active* session is Incognito, Android shows one
 * low-importance, non-ongoing notification (no heads-up, no sound) explaining what Incognito does and
 * offering a single action that closes the session - which deletes the session, its tabs and its
 * profile data (cookies / site data) exactly like deleting it from the sessions drawer.
 *
 * It is cancelled the moment the active session stops being Incognito, and by the BrowserActivity
 * when the task is finished, so a stale notification can never outlive the state it describes.
 * Nothing is posted when notifications are not permitted (POST_NOTIFICATIONS on 13+, app toggle).
 */
object IncognitoNotifier {

    const val ACTION_CLOSE = "app.multisession.browser.action.INCOGNITO_CLOSE"
    const val EXTRA_SESSION_ID = "app.multisession.browser.session_id"

    private const val CHANNEL_ID = "incognito_session"
    private const val NOTIFICATION_ID = 0x1C0
    private const val RC_CLOSE = 0x1C1

    /** Session id whose notification is currently posted (null = none). Main-thread only.
     *  v2.1.10 (perf): the active-session flow emits on every session touch, and every emission used
     *  to cost a cancel binder call (non-private, the common case) or a full rebuild + notify. */
    private var postedForId: String? = null

    /** Shows or hides the notification for the current active session. */
    fun update(context: Context, active: SessionEntity?) {
        val app = context.applicationContext
        if (active?.isPrivate != true) {
            if (postedForId == null) return   // nothing was ever posted: skip the cancel IPC
            postedForId = null
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
            return
        }
        // Already handled for this session (content only depends on the id). A swipe-dismiss is
        // accepted: this is an advisory notification, it re-appears on the next session change.
        if (postedForId == active.id) return
        if (!canPost(app)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel(app)
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_incognito)
            .setContentTitle(app.getString(R.string.incognito_active_title))
            .setContentText(app.getString(R.string.incognito_active_msg))
            .addAction(R.drawable.ic_close, app.getString(R.string.incognito_close), closeIntent(app, active.id))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(false)
        appOpenIntent(app)?.let { builder.setContentIntent(it) }
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        postedForId = active.id
    }

    /** Hides it immediately (Activity leaving for good). */
    fun cancel(context: Context) {
        postedForId = null
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    // ------------------------------------------------------------------ plumbing

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val c = NotificationChannel(CHANNEL_ID, context.getString(R.string.incognito_channel), NotificationManager.IMPORTANCE_LOW)
            c.description = context.getString(R.string.incognito_channel_desc)
            nm.createNotificationChannel(c)
        }
    }

    /** Tapping the body returns to the browser (it never closes anything by itself). */
    private fun appOpenIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun closeIntent(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, RC_CLOSE,
            Intent(context, IncognitoCloseReceiver::class.java)
                .setAction(ACTION_CLOSE)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
