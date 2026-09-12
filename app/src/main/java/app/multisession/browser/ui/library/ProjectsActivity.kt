package app.multisession.browser.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.projects.ProjectManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Local HTML projects: import a ZIP (index.html + css/js/images), import a single HTML file,
 * or paste HTML. Projects are opened by the GeckoView engine from the app's private storage.
 * Export = ZIP you can drop into app/src/main/assets/www/ to ship as a standalone APK via GitHub Actions.
 */
class ProjectsActivity : SimpleListActivity() {

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { askName(it, zip = true) } }
    private val pickHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { askName(it, zip = false) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_projects, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_zip -> pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            R.id.action_import_html -> pickHtml.launch(arrayOf("text/html", "text/plain"))
            R.id.action_new_html -> newFromHtml()
            R.id.action_apk_help -> showApkHelp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refresh() {
        val projects = core.projects.list()
        submit(projects.map { p ->
            LinkItem(
                title = p.name,
                subtitle = getString(R.string.project_subtitle, p.entry, p.fileCount),
                icon = R.drawable.ic_folder,
                onClick = { returnUrl(p.url) },
                onLongClick = { showProjectMenu(p) },
            )
        }, getString(R.string.projects_empty))
    }

    private fun showProjectMenu(p: ProjectManager.Project) {
        val actions = arrayOf(getString(R.string.open_in_new_tab), getString(R.string.export_zip), getString(R.string.delete))
        MaterialAlertDialogBuilder(this).setTitle(p.name).setItems(actions) { _, which ->
            when (which) {
                0 -> returnUrl(p.url, newTab = true)
                1 -> lifecycleScope.launch {
                    try {
                        val file = core.projects.exportZip(p)
                        val uri = FileProvider.getUriForFile(this@ProjectsActivity, "$packageName.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(share, getString(R.string.export_zip)))
                        Snackbar.make(recycler, getString(R.string.exported_size, Formatter.formatFileSize(this@ProjectsActivity, file.length())), Snackbar.LENGTH_LONG).show()
                    } catch (t: Throwable) {
                        AppLog.e("Projects", "export failed", t)
                        Snackbar.make(recycler, R.string.export_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
                2 -> MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(getString(R.string.delete_project_confirm, p.name))
                    .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { core.projects.delete(p); refresh() } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    private fun askName(uri: Uri, zip: Boolean) {
        val input = EditText(this).apply { hint = getString(R.string.project_name); setText(uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "") }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_project)
            .setView(input)
            .setPositiveButton(R.string.import_action) { _, _ ->
                lifecycleScope.launch {
                    try {
                        if (zip) core.projects.importZip(uri, input.text.toString()) else core.projects.importHtml(uri, input.text.toString())
                        refresh()
                        Snackbar.make(recycler, R.string.project_imported, Snackbar.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        AppLog.e("Projects", "import failed", t)
                        Snackbar.make(recycler, getString(R.string.import_failed, t.message ?: ""), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newFromHtml() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_html, null)
        val name = view.findViewById<EditText>(R.id.htmlName)
        val html = view.findViewById<EditText>(R.id.htmlBody)
        html.setText(getString(R.string.html_template))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_html_page)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                lifecycleScope.launch {
                    val p = core.projects.createFromHtml(name.text.toString(), html.text.toString())
                    refresh()
                    returnUrl(p.url, newTab = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showApkHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apk_help_title)
            .setMessage(R.string.apk_help_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
