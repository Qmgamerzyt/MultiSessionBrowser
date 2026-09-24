package app.multisession.browser.ui.extensions

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.extensions.AmoAddon
import app.multisession.browser.extensions.AmoApi
import app.multisession.browser.extensions.ExtensionHost
import app.multisession.browser.extensions.installErrorMessage
import app.multisession.browser.tabs.Tab
import app.multisession.browser.ui.library.SimpleListActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.text.NumberFormat

/** Installed WebExtensions: enable / disable / uninstall / options; install from an https .xpi URL, a local file
 *  or the addons.mozilla.org catalog. This screen is the [ExtensionHost] while it is in front (see [onStart]). */
class ExtensionsActivity : AppCompatActivity(), ExtensionHost {

    private val core get() = BrowserApp.core()
    private val em get() = core.extensions
    private lateinit var adapter: Adapter
    private lateinit var emptyView: TextView

    private val pickXpi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        em.installFromFile(uri) { r -> report(r) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.extensions)
        emptyView = findViewById(R.id.emptyView)
        emptyView.text = getString(R.string.ext_empty)
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recycler).apply { layoutManager = LinearLayoutManager(this@ExtensionsActivity); adapter = this@ExtensionsActivity.adapter }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                em.extensions.collect { list -> adapter.submit(list); emptyView.isVisible = list.isEmpty() }
            }
        }
        em.refresh()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onStart() {
        super.onStart()
        // Own the extension UI while this screen is in front. BrowserActivity clears itself in its
        // own onStop(), and with no host Gecko's install permission prompt is answered "deny" - so
        // every install started from here used to be cancelled before the user could approve it.
        em.host = this
    }

    override fun onStop() {
        // Only hand ownership back if we still hold it: returning to BrowserActivity, its onStart()
        // claims the host first and this guard must not clear it.
        if (em.host === this) em.host = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_extensions, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_amo_find -> amoSearch()
            R.id.action_install_url -> {
                val input = EditText(this).apply { hint = getString(R.string.ext_url_hint); setSingleLine() }
                MaterialAlertDialogBuilder(this).setTitle(R.string.ext_install_url).setView(input)
                    .setPositiveButton(R.string.import_action) { _, _ -> em.install(input.text.toString()) { r -> report(r) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            R.id.action_install_file -> pickXpi.launch(arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream", "*/*"))
            R.id.action_ext_limits -> MaterialAlertDialogBuilder(this).setTitle(R.string.ext_limits_title).setMessage(R.string.ext_limits_body).setPositiveButton(android.R.string.ok, null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun report(r: Result<WebExtension>) {
        r.onSuccess { snack(getString(R.string.ext_installed, it.metaData.name ?: it.id)) }
            .onFailure { AppLog.w("Extensions", "install failed", it); snack(getString(R.string.ext_install_failed, installErrorMessage(this, it))) }
    }

    // ================================================================== AMO browse

    private fun amoSearch() {
        val input = EditText(this).apply { hint = getString(R.string.ext_amo_hint); setSingleLine() }
        MaterialAlertDialogBuilder(this).setTitle(R.string.ext_amo_find).setView(input)
            .setPositiveButton(R.string.ext_amo_search) { _, _ -> amoResults(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun amoResults(query: String) {
        lifecycleScope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { AmoApi.search(query) } }
            if (isFinishing || isDestroyed) return@launch
            val list = res.getOrNull()
            if (list == null) {
                AppLog.w("Extensions", "AMO search failed", res.exceptionOrNull())
                snack(getString(R.string.ext_amo_error)); return@launch
            }
            if (list.isEmpty()) { snack(getString(R.string.ext_amo_empty)); return@launch }
            val labels = list.map { a -> a.name + if (a.summary.isBlank()) "" else "\n" + a.summary }.toTypedArray()
            MaterialAlertDialogBuilder(this@ExtensionsActivity).setTitle(R.string.ext_amo_find)
                .setItems(labels) { _, i -> amoDetail(list[i]) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun amoDetail(a: AmoAddon) {
        val msg = buildString {
            if (a.summary.isNotBlank()) { append(a.summary); append("\n\n") }
            if (a.version.isNotBlank()) { append(getString(R.string.ext_amo_version, a.version)); append(" · ") }
            append(getString(R.string.ext_amo_users, NumberFormat.getIntegerInstance().format(a.users)))
            if (a.permissions.isNotEmpty()) {
                append("\n\n"); append(getString(R.string.ext_amo_permissions, a.permissions.joinToString("\n") { "• $it" }))
            }
        }
        MaterialAlertDialogBuilder(this).setTitle(a.name).setMessage(msg)
            .setPositiveButton(R.string.ext_amo_install) { _, _ -> amoInstall(a) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Goes through the normal install path: Gecko fetches the .xpi and validates its signature. */
    private fun amoInstall(a: AmoAddon) {
        val url = a.xpiUrl
        if (url.isNullOrBlank()) { snack(getString(R.string.ext_amo_error)); return }
        em.install(url) { r -> report(r) }
    }

    // ================================================================== ExtensionHost
    // This screen owns the extension UI while it is open, so it must answer the prompts Gecko
    // raises for installs started here. browserAction popups and tabs an extension creates are not
    // offered on this management screen - see docs/EXTENSIONS.md.

    override fun onExtensionInstallPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, dataCollection: List<String>, onDecision: (Boolean) -> Unit) {
        var decided = false
        val decide: (Boolean) -> Unit = { if (!decided) { decided = true; onDecision(it) } }
        val perms = permissions.ifEmpty { listOf(getString(R.string.ext_permissions_none)) }.joinToString("\n") { "• $it" }
        val hosts = if (origins.isNotEmpty()) "\n\n" + getString(R.string.ext_host_permissions, origins.joinToString("\n") { "• $it" }) else ""
        val data = if (dataCollection.isNotEmpty()) "\n\nData collection:\n" + dataCollection.joinToString("\n") { "• $it" } else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ext_install_prompt_title, ext.metaData.name ?: ext.id))
            .setMessage(getString(R.string.ext_install_prompt_msg, ext.metaData.version ?: "?", perms) + hosts + data)
            .setPositiveButton(R.string.import_action) { _, _ -> decide(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> decide(false) }
            .setOnDismissListener { decide(false) }
            .show()
    }

    override fun onExtensionOptionalPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, onDecision: (Boolean) -> Unit) {
        var decided = false
        val decide: (Boolean) -> Unit = { if (!decided) { decided = true; onDecision(it) } }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ext_optional_prompt_title, ext.metaData.name ?: ext.id))
            .setMessage((permissions + origins).joinToString("\n") { "• $it" })
            .setPositiveButton(R.string.allow) { _, _ -> decide(true) }
            .setNegativeButton(R.string.block) { _, _ -> decide(false) }
            .setOnDismissListener { decide(false) }
            .show()
    }

    override fun showExtensionPopup(ext: WebExtension, popupSession: GeckoSession) {
        // No popup surface on this screen: close the session Gecko opened for it so it cannot leak.
        popupSession.close()
    }

    /** The list is a StateFlow this screen already collects - and refresh() itself invokes this
     *  callback, so re-entering it here would loop. Nothing to do. */
    override fun onExtensionsChanged() {}

    /** Creating a tab needs the browser screen; declined here exactly as it was while no host existed. */
    override fun onExtensionNewTab(ext: WebExtension, url: String?, active: Boolean): Tab? = null

    override fun onExtensionOpenOptions(ext: WebExtension, url: String) {
        setResult(RESULT_OK, Intent().putExtra(SimpleListActivity.EXTRA_OPEN_URL, url).putExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, true))
        finish()
    }

    override fun onExtensionInstallResult(r: Result<WebExtension>) = report(r)

    private fun snack(s: String) = Snackbar.make(findViewById(R.id.recycler), s, Snackbar.LENGTH_LONG).show()

    private fun showMore(anchor: View, ext: WebExtension) {
        val popup = PopupMenu(this, anchor)
        if (ext.metaData.optionsPageUrl != null) popup.menu.add(0, 1, 0, R.string.ext_options)
        // Gecko defaults this to false, which keeps an add-on out of our private sessions (they run
        // with usePrivateMode) until the user opts in here.
        popup.menu.add(0, 3, 1, R.string.ext_private).apply { isCheckable = true; isChecked = ext.metaData.allowedInPrivateBrowsing }
        popup.menu.add(0, 2, 2, R.string.ext_uninstall)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { setResult(RESULT_OK, Intent().putExtra(SimpleListActivity.EXTRA_OPEN_URL, ext.metaData.optionsPageUrl).putExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, true)); finish() }
                3 -> em.setAllowedInPrivateBrowsing(ext, !ext.metaData.allowedInPrivateBrowsing) { e -> if (e != null) snack(e.message ?: "error") }
                2 -> MaterialAlertDialogBuilder(this).setTitle(R.string.ext_uninstall).setMessage(getString(R.string.ext_uninstall_confirm, ext.metaData.name ?: ext.id))
                    .setPositiveButton(R.string.ext_uninstall) { _, _ -> em.uninstall(ext) { e -> if (e != null) snack(e.message ?: "error") } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            true
        }
        popup.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<WebExtension>()
        fun submit(list: List<WebExtension>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_extension, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val icon: ImageView = v.findViewById(R.id.extIcon)
            private val name: TextView = v.findViewById(R.id.extName)
            private val info: TextView = v.findViewById(R.id.extInfo)
            private val enabled: MaterialSwitch = v.findViewById(R.id.extEnabled)
            private val more: ImageButton = v.findViewById(R.id.extMore)
            fun bind(ext: WebExtension) {
                val md = ext.metaData
                name.text = md.name ?: ext.id
                val parts = mutableListOf<String>()
                md.version?.let { parts += "v$it" }
                md.creatorName?.let { parts += getString(R.string.ext_by, it) }
                if (!md.enabled) parts += getString(R.string.ext_disabled_label)
                info.text = (parts.joinToString(" · ") + "\n" + (md.description ?: "")).trim()
                enabled.setOnCheckedChangeListener(null)
                enabled.isChecked = md.enabled
                enabled.setOnCheckedChangeListener { _, checked -> em.setEnabled(ext, checked) { e -> if (e != null) snack(e.message ?: "error") } }
                icon.setImageResource(R.drawable.ic_extension)
                try {
                    val size = (36 * resources.displayMetrics.density).toInt()
                    md.icon.getBitmap(size).accept({ bmp: Bitmap? -> if (bmp != null) icon.setImageBitmap(bmp) }, {})
                } catch (_: Throwable) {}
                more.setOnClickListener { showMore(it, ext) }
            }
        }
    }
}
