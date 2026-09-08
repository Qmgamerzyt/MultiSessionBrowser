package app.multisession.browser.ui.library

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Per-session history (the active session). History from other sessions is never mixed in. */
class HistoryActivity : SimpleListActivity() {

    private val sessionId: String? get() = core.sessions.activeId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sid = sessionId ?: run { finish(); return }
        supportActionBar?.subtitle = core.sessions.get(sid)?.name
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                core.repo.history.observe(sid).collect { entries ->
                    submit(entries.map { e ->
                        LinkItem(
                            title = e.title.ifBlank { e.url },
                            subtitle = DateUtils.getRelativeTimeSpanString(e.visitedAt) .toString() + " · " + UrlUtils.displayHost(e.url),
                            icon = R.drawable.ic_history,
                            onClick = { returnUrl(e.url) },
                            onLongClick = {
                                MaterialAlertDialogBuilder(this@HistoryActivity)
                                    .setItems(arrayOf(getString(R.string.open_in_new_tab), getString(R.string.delete))) { _, which ->
                                        if (which == 0) returnUrl(e.url, newTab = true)
                                        else lifecycleScope.launch { core.persistNow { core.repo.history.delete(e.id) } }
                                    }.show()
                            },
                        )
                    }, getString(R.string.history_empty))
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_history) {
            val sid = sessionId ?: return true
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear) { _, _ -> lifecycleScope.launch { core.persistNow { core.repo.history.clear(sid) } } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
