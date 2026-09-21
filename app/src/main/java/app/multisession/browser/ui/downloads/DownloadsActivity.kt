package app.multisession.browser.ui.downloads

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.DownloadEntity
import app.multisession.browser.downloads.DownloadKind
import app.multisession.browser.downloads.DownloadStatus
import app.multisession.browser.downloads.DownloadTypes
import app.multisession.browser.ui.library.SimpleListActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * The browser's own Downloads page: active + finished downloads with progress, pause/resume/cancel/retry, open, share,
 * delete, copy link, open source page. Data comes from AppDownloadManager (Room-backed) so it survives restarts.
 */
class DownloadsActivity : AppCompatActivity() {

    private val core get() = BrowserApp.core()
    private val dm get() = core.downloads
    private lateinit var adapter: Adapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.downloads)
        emptyView = findViewById(R.id.emptyView)
        emptyView.text = getString(R.string.dl_empty)
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recycler).apply { layoutManager = LinearLayoutManager(this@DownloadsActivity); adapter = this@DownloadsActivity.adapter }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dm.downloads.collect { list ->
                    adapter.submit(list)
                    emptyView.isVisible = list.isEmpty()
                    val active = list.count { DownloadStatus.isActive(it.status) }
                    supportActionBar?.subtitle = if (active > 0) getString(R.string.dl_active_fmt, active) else null
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_downloads, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_finished -> dm.clearFinished()
            R.id.action_open_folder -> try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (e: ActivityNotFoundException) { toast(getString(R.string.no_app_found)) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ actions

    private fun open(d: DownloadEntity) {
        if (d.status != DownloadStatus.COMPLETED) return
        if (!dm.fileExists(d)) { toast(getString(R.string.dl_file_missing)); return }
        val kind = DownloadTypes.kindOf(d.fileName, d.mimeType)
        when (kind) {
            DownloadKind.APK -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dl_apk_confirm_title)
                .setMessage(getString(R.string.dl_apk_confirm_msg, d.fileName, UrlUtils.displayHost(d.url).ifBlank { d.url }))
                .setPositiveButton(R.string.dl_install) { _, _ -> launchOpen(d) }   // hands over to the system installer; never silent
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            DownloadKind.ARCHIVE -> { if (!launchOpen(d, quiet = true)) toast(getString(R.string.dl_archive_hint)) }
            else -> launchOpen(d)
        }
    }

    private fun launchOpen(d: DownloadEntity, quiet: Boolean = false): Boolean {
        val intent = dm.openIntent(d) ?: run { toast(getString(R.string.dl_file_missing)); return false }
        return try { startActivity(intent); true } catch (e: ActivityNotFoundException) {
            // Unknown type: offer the generic chooser instead of failing.
            try { startActivity(Intent.createChooser(intent.setDataAndType(intent.data, "*/*"), getString(R.string.dl_open))); true }
            catch (e2: ActivityNotFoundException) { if (!quiet) toast(getString(R.string.dl_no_app)); false }
        }
    }

    private fun share(d: DownloadEntity) {
        val intent = dm.shareIntent(d) ?: run { toast(getString(R.string.dl_file_missing)); return }
        startActivity(Intent.createChooser(intent, getString(R.string.dl_share)))
    }

    private fun showMore(anchor: View, d: DownloadEntity) {
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        val finished = DownloadStatus.isFinished(d.status)
        if (d.status == DownloadStatus.COMPLETED) { m.add(0, 1, 0, R.string.dl_open); m.add(0, 2, 1, R.string.dl_share) }
        if (DownloadStatus.isActive(d.status)) m.add(0, 3, 2, R.string.dl_pause)
        if (d.status == DownloadStatus.PAUSED) m.add(0, 4, 3, R.string.dl_resume)
        if (d.status == DownloadStatus.FAILED || d.status == DownloadStatus.CANCELLED) m.add(0, 5, 4, R.string.dl_retry)
        if (!finished) m.add(0, 6, 5, R.string.dl_cancel)
        m.add(0, 7, 6, R.string.dl_copy_link)
        if (!d.sourcePage.isNullOrBlank()) m.add(0, 8, 7, R.string.dl_open_source)
        m.add(0, 9, 8, R.string.dl_delete)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(d); 2 -> share(d); 3 -> dm.pause(d.id); 4 -> dm.resume(d.id); 5 -> dm.retry(d.id); 6 -> dm.cancel(d.id)
                7 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", d.url)); toast(getString(R.string.copied)) }
                8 -> { setResult(RESULT_OK, Intent().putExtra(SimpleListActivity.EXTRA_OPEN_URL, d.sourcePage).putExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, true)); finish() }
                9 -> confirmDelete(d)
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(d: DownloadEntity) {
        if (d.status != DownloadStatus.COMPLETED || !dm.fileExists(d)) { dm.delete(d.id, deleteFile = true); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(d.fileName)
            .setMessage(R.string.dl_delete_confirm)
            .setPositiveButton(R.string.dl_delete_file) { _, _ -> dm.delete(d.id, deleteFile = true) }
            .setNegativeButton(R.string.dl_delete) { _, _ -> dm.delete(d.id, deleteFile = false) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(s: String) = Snackbar.make(findViewById(R.id.recycler), s, Snackbar.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ adapter

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<DownloadEntity>()
        fun submit(list: List<DownloadEntity>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val icon: ImageView = v.findViewById(R.id.dlIcon)
            private val name: TextView = v.findViewById(R.id.dlName)
            private val info: TextView = v.findViewById(R.id.dlInfo)
            private val primary: ImageButton = v.findViewById(R.id.dlPrimary)
            private val more: ImageButton = v.findViewById(R.id.dlMore)
            private val progress: ProgressBar = v.findViewById(R.id.dlProgress)

            fun bind(d: DownloadEntity) {
                val kind = DownloadTypes.kindOf(d.fileName, d.mimeType)
                icon.setImageResource(kind.iconRes)
                name.text = d.fileName
                val status = when (d.status) {
                    DownloadStatus.PENDING -> getString(R.string.dl_status_pending)
                    DownloadStatus.RUNNING -> getString(R.string.dl_status_running)
                    DownloadStatus.PAUSED -> getString(R.string.dl_status_paused)
                    DownloadStatus.COMPLETED -> getString(R.string.dl_status_completed)
                    DownloadStatus.FAILED -> getString(R.string.dl_status_failed)
                    else -> getString(R.string.dl_status_cancelled)
                }
                val size = when {
                    d.status == DownloadStatus.COMPLETED -> Formatter.formatFileSize(itemView.context, d.downloadedBytes)
                    d.totalBytes > 0 -> getString(R.string.dl_size_fmt, Formatter.formatFileSize(itemView.context, d.downloadedBytes), Formatter.formatFileSize(itemView.context, d.totalBytes))
                    else -> Formatter.formatFileSize(itemView.context, d.downloadedBytes)
                }
                val line1 = listOf(status, size, getString(kind.labelRes), d.mimeType).joinToString(" · ")
                val src = getString(R.string.dl_source_fmt, UrlUtils.displayHost(d.sourcePage ?: d.url).ifBlank { d.url.substringBefore(':') + ":" }) +
                    " · " + DateUtils.getRelativeTimeSpanString(d.createdAt)
                info.text = if (d.status == DownloadStatus.FAILED && !d.error.isNullOrBlank()) "$line1\n${d.error}" else "$line1\n$src"
                val active = DownloadStatus.isActive(d.status)
                progress.isVisible = active || d.status == DownloadStatus.PAUSED
                progress.isIndeterminate = active && d.totalBytes <= 0
                if (d.totalBytes > 0) progress.progress = ((d.downloadedBytes * 1000) / d.totalBytes).toInt().coerceIn(0, 1000)
                when {
                    active -> { primary.setImageResource(R.drawable.ic_pause); primary.contentDescription = getString(R.string.dl_pause); primary.setOnClickListener { dm.pause(d.id) } }
                    d.status == DownloadStatus.PAUSED -> { primary.setImageResource(R.drawable.ic_play); primary.contentDescription = getString(R.string.dl_resume); primary.setOnClickListener { dm.resume(d.id) } }
                    d.status == DownloadStatus.COMPLETED -> { primary.setImageResource(R.drawable.ic_open_in_new); primary.contentDescription = getString(R.string.dl_open); primary.setOnClickListener { open(d) } }
                    else -> { primary.setImageResource(R.drawable.ic_refresh); primary.contentDescription = getString(R.string.dl_retry); primary.setOnClickListener { dm.retry(d.id) } }
                }
                more.setOnClickListener { showMore(it, d) }
                itemView.setOnClickListener { if (d.status == DownloadStatus.COMPLETED) open(d) else showMore(more, d) }
            }
        }
    }
}
