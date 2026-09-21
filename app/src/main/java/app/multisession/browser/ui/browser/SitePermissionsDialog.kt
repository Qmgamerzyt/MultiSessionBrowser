package app.multisession.browser.ui.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.permissions.PermissionValue
import app.multisession.browser.permissions.SitePermissionStore
import app.multisession.browser.permissions.SitePermissionType
import app.multisession.browser.tabs.Tab
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import java.util.EnumMap

/**
 * Compact permissions manager for the site shown in [tab]: one row per supported permission with an
 * Allow / Block / Ask selector, plus Reset. Every change is written to BOTH stores - the app's
 * SitePermissionStore (authoritative when Gecko asks us) and Gecko's own permission manager for this
 * origin + contextId (StorageController.setPermission) - so a site Gecko already remembered as allowed
 * really is blocked after the user flips it here.
 */
class SitePermissionsDialog(private val activity: BrowserActivity, private val core: BrowserCore, private val tab: Tab) {

    private val store get() = core.sitePermissions
    private val spinners = EnumMap<SitePermissionType, Spinner>(SitePermissionType::class.java)
    private var geckoPerms: List<ContentPermission> = emptyList()
    private var updating = false
    private var dialog: AlertDialog? = null
    private lateinit var session: SessionEntity
    private lateinit var origin: String
    private lateinit var androidHint: TextView

    fun show() {
        session = core.sessions.get(tab.sessionId) ?: return
        origin = SitePermissionStore.originOf(tab.url) ?: run { activity.snack(activity.getString(R.string.perm_no_site)); return }
        geckoPerms = tab.sitePermissions.filter { SitePermissionStore.originOf(it.uri) == origin }

        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_site_permissions, null)
        view.findViewById<TextView>(R.id.permOrigin).text = origin
        androidHint = view.findViewById(R.id.permAndroidHint)
        val rows = view.findViewById<LinearLayout>(R.id.permRows)
        val states = activity.resources.getStringArray(R.array.perm_states)   // Allow, Block, Ask == values 1, 2, 3

        SitePermissionType.MANAGED.forEach { type ->
            val row = inflater.inflate(R.layout.item_site_permission, rows, false)
            row.findViewById<TextView>(R.id.permLabel).setText(type.labelRes)
            val spinner = row.findViewById<Spinner>(R.id.permState)
            spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, states)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(store.get(session.id, origin, type) - 1, false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (updating) return
                    val value = position + 1
                    if (store.get(session.id, origin, type) == value) return
                    apply(type, value)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            spinners[type] = spinner
            rows.addView(row)
        }

        val d = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.site_permissions)
            .setView(view)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.perm_reset) { _, _ -> resetAll() }
            .setNeutralButton(R.string.perm_android_settings) { _, _ -> openAppSettings() }
            .create()
        dialog = d
        d.show()
        refreshAndroidHint()
        loadGeckoPermissions()
    }

    private fun apply(type: SitePermissionType, value: Int) {
        store.set(session.id, origin, type, value)
        type.geckoType?.let { g -> geckoPerms.filter { it.permission == g }.forEach { core.engine.setSitePermission(it, value) } }
        refreshAndroidHint()
    }

    private fun resetAll() {
        store.reset(session.id, origin)
        geckoPerms.forEach { core.engine.setSitePermission(it, ContentPermission.VALUE_PROMPT) }
        AppLog.i(TAG, "Site permissions reset for $origin")
        activity.snack(activity.getString(R.string.perm_reset_done))
    }

    /** Pulls what Gecko has stored for this origin/context so a decision Gecko remembers is shown (and mirrored). */
    private fun loadGeckoPermissions() {
        val rt = core.engine.runtimeOrNull ?: return
        try {
            rt.storageController.getPermissions(tab.url, core.isolation.contextId(session), session.isPrivate).accept({ list ->
                if (list == null || dialog?.isShowing != true) return@accept
                geckoPerms = list.filter { SitePermissionStore.originOf(it.uri) == origin }
                updating = true
                geckoPerms.forEach { p ->
                    val type = SitePermissionType.fromGecko(p.permission) ?: return@forEach
                    if (p.value != ContentPermission.VALUE_ALLOW && p.value != ContentPermission.VALUE_DENY) return@forEach
                    if (store.get(session.id, origin, type) == PermissionValue.ASK) store.set(session.id, origin, type, p.value)
                    spinners[type]?.setSelection(p.value - 1, false)
                }
                updating = false
                refreshAndroidHint()
            }, { AppLog.w(TAG, "getPermissions failed", it) })
        } catch (t: Throwable) {
            AppLog.w(TAG, "getPermissions not available", t)
        }
    }

    /** A site may be allowed while the APP lacks the Android permission: say so and offer the system settings. */
    private fun refreshAndroidHint() {
        val missing = SitePermissionType.MANAGED.filter { t ->
            t.androidPermissions.isNotEmpty() &&
                store.get(session.id, origin, t) == PermissionValue.ALLOW &&
                t.androidPermissions.none { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }
        }
        androidHint.isVisible = missing.isNotEmpty()
        if (missing.isNotEmpty()) {
            androidHint.text = activity.getString(R.string.perm_android_missing, missing.joinToString(", ") { activity.getString(it.labelRes) })
        }
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isVisible = missing.isNotEmpty()
    }

    private fun openAppSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
        } catch (t: Throwable) {
            AppLog.w(TAG, "Cannot open app settings", t)
        }
    }

    private companion object {
        const val TAG = "SitePermsDialog"
    }
}
