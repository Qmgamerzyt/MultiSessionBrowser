package app.multisession.browser.projects.editor

import android.content.Intent
import android.os.Bundle
import android.view.GravityCompat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.projects.ProjectManager
import app.multisession.browser.ui.browser.BrowserActivity
import app.multisession.browser.ui.library.SimpleListActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * In-app editor for local HTML projects (v2.1.10, Plan 3).
 *
 * Hosts the system WebView on the bundled CodeMirror page (assets/editor/editor.html) and
 * bridges it to disk through [EditorBridge] (`window.Bridge`). The native side owns the file
 * tree drawer, the flush points (Preview / back), and rotation state; the JS side owns the
 * 800 ms autosave debounce. Preview delivers [SimpleListActivity.EXTRA_OPEN_URL] to
 * [BrowserActivity] so the browser opens the page while the editor stays on the back stack.
 */
class EditorActivity : AppCompatActivity() {

    private val core get() = BrowserApp.core()
    private var project: ProjectManager.Project? = null
    private lateinit var bridge: EditorBridge
    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private val rowAdapter = RowAdapter()
    private val expanded = mutableSetOf<String>()
    private var entries: List<EditorBridge.TreeEntry> = emptyList()
    private var currentPath: String? = null
    private var isDirty = false
    private var seeded = false
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        val drawerList = findViewById<RecyclerView>(R.id.drawerList)
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = rowAdapter

        val p = core.projects.get(intent.getStringExtra(EXTRA_PROJECT_ID) ?: "")
        if (p == null) { finish(); return }
        project = p
        toolbar.title = p.name
        findViewById<TextView>(R.id.drawerTitle).text = p.name
        bridge = EditorBridge(this, p) { dirty ->
            isDirty = dirty
            updateSubtitle()
        }
        currentPath = savedInstanceState?.getString(STATE_PATH)
            ?: intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() } ?: p.entry
        updateSubtitle()

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/editor/")

            override fun onPageFinished(view: WebView, url: String) {
                if (pageLoaded) return
                pageLoaded = true
                loadInJs(currentPath ?: p.entry)
            }
        }
        webView.addJavascriptInterface(bridge, "Bridge")
        webView.loadUrl(EDITOR_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })
        refreshTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, currentPath)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- toolbar

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_new_file -> { newFileDialog(); true }
        R.id.action_find -> { webView.evaluateJavascript("find()", null); true }
        R.id.action_preview -> { preview(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Flush, then hand the project URL to the browser; this activity stays on the back stack. */
    private fun preview() {
        webView.evaluateJavascript("flushSave()") {
            val p = project ?: return@evaluateJavascript
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(SimpleListActivity.EXTRA_OPEN_URL, p.url)
            )
        }
    }

    private fun leaveEditor() {
        if (!pageLoaded || !isDirty) { finish(); return }
        webView.evaluateJavascript("flushSave()") { finish() }
    }

    private fun updateSubtitle() {
        val path = currentPath ?: return
        toolbar.subtitle = if (isDirty) "$path \u2022" else path
    }

    // ---------------------------------------------------------------- editor page plumbing

    private fun loadInJs(path: String) {
        currentPath = path
        updateSubtitle()
        webView.evaluateJavascript("loadFile(" + JSONObject.quote(path) + ")", null)
    }

    // ---------------------------------------------------------------- file tree

    private fun refreshTree() {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { bridge.list() }
            entries = fresh
            if (!seeded) { fresh.filter { it.dir }.forEach { expanded += it.path }; seeded = true }
            rowAdapter.submit(flatten())
        }
    }

    private fun flatten(): List<TreeRow> {
        val byParent = entries.groupBy { it.path.substringBeforeLast('/', "") }
        val out = mutableListOf<TreeRow>()
        fun emit(parent: String, depth: Int) {
            val kids = byParent[parent] ?: return
            kids.sortedWith(compareBy({ !it.dir }, { it.path.substringAfterLast('/') })).forEach { e ->
                out += TreeRow(e.path, e.path.substringAfterLast('/'), depth, e.dir)
                if (e.dir && expanded.contains(e.path)) emit(e.path, depth + 1)
            }
        }
        emit("", 0)
        return out
    }

    private fun onRowClick(row: TreeRow) {
        if (row.isDir) {
            if (!expanded.remove(row.path)) expanded.add(row.path)
            rowAdapter.submit(flatten())
        } else {
            drawerLayout.closeDrawer(GravityCompat.START)
            loadInJs(row.path)
        }
    }

    private fun onRowLongClick(row: TreeRow) {
        if (row.isDir) return
        val actions = arrayOf(getString(R.string.editor_rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this).setTitle(row.path).setItems(actions) { _, which ->
            when (which) {
                0 -> renameDialog(row.path)
                1 -> deleteDialog(row.path)
            }
        }.show()
    }

    private fun newFileDialog() {
        val input = EditText(this).apply { hint = getString(R.string.editor_new_file_hint) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_new_file).setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val path = input.text.toString().trim().removePrefix("/")
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.create(path) }
                    if (ok) { refreshTree(); loadInJs(path) }
                    else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameDialog(path: String) {
        val input = EditText(this).apply { setText(path) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_rename).setView(input)
            .setPositiveButton(R.string.editor_rename) { _, _ ->
                val to = input.text.toString().trim().removePrefix("/")
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.rename(path, to) }
                    if (ok) {
                        refreshTree()
                        if (currentPath == path) loadInJs(to)
                    } else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteDialog(path: String) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(path)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.delete(path) }
                    if (ok) {
                        refreshTree()
                        val entry = project?.entry ?: return@launch
                        if (currentPath == path) loadInJs(entry)
                    } else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- tree rows

    private data class TreeRow(val path: String, val name: String, val depth: Int, val isDir: Boolean)

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var data: List<TreeRow> = emptyList()

        fun submit(rows: List<TreeRow>) { data = rows; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_editor_row, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            holder.name.text = row.name
            holder.icon.setImageResource(if (row.isDir) R.drawable.ic_folder else R.drawable.ic_document)
            holder.itemView.setPadding(
                ((row.depth * 16) + 8).dp(), holder.itemView.paddingTop,
                holder.itemView.paddingEnd, holder.itemView.paddingBottom
            )
            holder.itemView.setOnClickListener { onRowClick(row) }
            holder.itemView.setOnLongClickListener { onRowLongClick(row); true }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val name: TextView = v.findViewById(R.id.rowName)
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_ID = "app.multisession.browser.EDITOR_PROJECT"
        const val EXTRA_FILE = "app.multisession.browser.EDITOR_FILE"
        private const val EDITOR_URL = "file:///android_asset/editor/editor.html"
        private const val STATE_PATH = "editor_path"
    }
}
