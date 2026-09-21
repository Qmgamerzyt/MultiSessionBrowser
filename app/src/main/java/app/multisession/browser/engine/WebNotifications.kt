package app.multisession.browser.engine

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

/**
 * Web Notifications (the Notification API) for GeckoView. Gecko only produces the notification data; without a
 * [WebNotificationDelegate] on the runtime every notification a site sends is silently dropped - which is what
 * happened before v2.1.2 although the app already let users grant the "notifications" site permission.
 *
 * Each web notification becomes one Android notification (channel "web_notifications"), keyed by Gecko's tag so
 * a site re-using a tag replaces its earlier notification, exactly like Firefox. Tapping it brings the browser to
 * the front and reports the click to the page ([WebNotification.click]); Gecko closing it (notification.close(),
 * page gone) removes it ([onCloseNotification]). Main-thread only (Gecko calls the delegate on the UI thread).
 */
class WebNotifications(private val app: Application) : WebNotificationDelegate {

    /** Live notifications by tag; needed to deliver click()/dismiss() back to Gecko. */
    private val shown = LinkedHashMap<String, WebNotification>()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, app.getString(R.string.notif_channel_web), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = app.getString(R.string.notif_channel_web_desc)
        nm.createNotificationChannel(channel)
    }

    /** True when Android lets this app post notifications (channel not blocked, POST_NOTIFICATIONS granted on 13+). */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    override fun onShowNotification(notification: WebNotification) {
        val tag = notification.tag
        shown.remove(tag)
        shown[tag] = notification
        while (shown.size > MAX_TRACKED) shown.remove(shown.keys.first())
        if (!canPost()) {
            AppLog.i(TAG, "Web notification not shown: notifications are disabled for the app")
            return
        }
        ensureChannel()
        val open = Intent(ACTION_CLICK)
            .setClassName(app, BROWSER_ACTIVITY)
            .putExtra(EXTRA_TAG, tag)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(app, tag.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = notification.text ?: ""
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_language)
            .setContentTitle(notification.title ?: app.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSilent(notification.silent)
            .setContentIntent(pending)
        try {
            NotificationManagerCompat.from(app).notify(tag, NOTIFICATION_ID, builder.build())
        } catch (t: SecurityException) {
            AppLog.w(TAG, "notify() refused", t)
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        shown.remove(notification.tag)
        NotificationManagerCompat.from(app).cancel(notification.tag, NOTIFICATION_ID)
    }

    /** The user tapped the Android notification: tell the page (fires the notification's onclick). */
    fun click(tag: String) {
        NotificationManagerCompat.from(app).cancel(tag, NOTIFICATION_ID)
        val n = shown.remove(tag) ?: return
        try {
            n.click()
        } catch (t: Throwable) {
            AppLog.w(TAG, "click() failed", t)
        }
    }

    companion object {
        private const val TAG = "WebNotifications"
        const val CHANNEL_ID = "web_notifications"
        const val EXTRA_TAG = "app.multisession.browser.WEB_NOTIFICATION_TAG"
        const val ACTION_CLICK = "app.multisession.browser.action.WEB_NOTIFICATION_CLICK"
        private const val BROWSER_ACTIVITY = "app.multisession.browser.ui.browser.BrowserActivity"
        private const val NOTIFICATION_ID = 0x5EB
        private const val MAX_TRACKED = 50
    }
}
