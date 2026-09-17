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
import app.multisession.browser.ui.library.SimpleListActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebExtension

/** Installed WebExtensions: enable / disable / uninstall / options; install from an https .xpi URL or a local file. */
class ExtensionsActivity : AppCompatActivity() {

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
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_extensions, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
            .onFailure { AppLog.w("Extensions", "install failed", it); snack(getString(R.string.ext_install_failed, describe(it))) }
    }

    private fun describe(t: Throwable): String {
        val ie = t as? WebExtension.InstallException ?: return t.message ?: t.javaClass.simpleName
        return when (ie.code) {
            WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> "network failure"
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_HASH -> "incorrect hash"
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> "corrupt file"
            WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS -> "file access error"
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> "the extension is not signed by Mozilla (unsigned extensions cannot be installed)"
            WebExtension.InstallException.ErrorCodes.ERROR_UNEXPECTED_ADDON_TYPE -> "unexpected add-on type"
            WebExtension.InstallException.ErrorCodes.ERROR_INCORRECT_ID -> "incorrect id"
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> "cancelled"
            WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED -> "postponed"
            else -> "error ${ie.code}"
        }
    }

    private fun snack(s: String) = Snackbar.make(findViewById(R.id.recycler), s, Snackbar.LENGTH_LONG).show()

    private fun showMore(anchor: View, ext: WebExtension) {
        val popup = PopupMenu(this, anchor)
        if (ext.metaData.optionsPageUrl != null) popup.menu.add(0, 1, 0, R.string.ext_options)
        popup.menu.add(0, 2, 1, R.string.ext_uninstall)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { setResult(RESULT_OK, Intent().putExtra(SimpleListActivity.EXTRA_OPEN_URL, ext.metaData.optionsPageUrl).putExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, true)); finish() }
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
