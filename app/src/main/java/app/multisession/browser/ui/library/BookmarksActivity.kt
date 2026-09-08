package app.multisession.browser.ui.library

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Global bookmarks + bookmarks of the active session. */
class BookmarksActivity : SimpleListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sid = core.sessions.activeId ?: run { finish(); return }
        supportActionBar?.subtitle = core.sessions.get(sid)?.name
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                core.repo.bookmarks.observeForSession(sid).collect { list ->
                    submit(list.map { b ->
                        LinkItem(
                            title = b.title.ifBlank { b.url },
                            subtitle = UrlUtils.displayHost(b.url) + if (b.sessionId == null) " · " + getString(R.string.bookmark_global_label) else "",
                            icon = R.drawable.ic_star,
                            onClick = { returnUrl(b.url) },
                            onLongClick = {
                                MaterialAlertDialogBuilder(this@BookmarksActivity)
                                    .setItems(arrayOf(getString(R.string.open_in_new_tab), getString(R.string.delete))) { _, which ->
                                        if (which == 0) returnUrl(b.url, newTab = true)
                                        else lifecycleScope.launch { core.persistNow { core.repo.bookmarks.delete(b.id) } }
                                    }.show()
                            },
                        )
                    }, getString(R.string.bookmarks_empty))
                }
            }
        }
    }
}
