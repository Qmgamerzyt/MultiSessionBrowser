package app.multisession.browser.ui.browser

import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import app.multisession.browser.R
import org.mozilla.geckoview.BasicSelectionActionDelegate

/**
 * Selection toolbar for v2.1.7 (issue D).
 *
 * Gecko's own [BasicSelectionActionDelegate] already provides Cut / Copy / Paste / Select all and the
 * system "process text" entries (translate, define, ...). This subclass only APPENDS the two actions a
 * browser selection menu needs and Gecko does not ship: **Share** and **Search the web**.
 *
 * Both extra actions are app-private ids, handled before delegating: every built-in id still falls
 * through to `super`, so Gecko keeps implementing it unchanged. The class relies solely on the
 * protected, non-final hooks verified against the GeckoView 155 API
 * (`getAllActions()`, `isActionAvailable(String)`, `prepareAction(String, MenuItem)`,
 * `performAction(String, MenuItem)`, `getSelection()`).
 */
class AppSelectionActionDelegate(
    activity: AppCompatActivity,
    private val onShare: (String) -> Unit,
    private val onSearch: (String) -> Unit,
) : BasicSelectionActionDelegate(activity) {

    /** Selected text, or "" when Gecko already dropped the selection. */
    private fun selectedText(): String = selection?.text.orEmpty()

    override fun getAllActions(): Array<String> {
        val base = super.getAllActions().toMutableList()
        if (ACTION_SHARE !in base) base += ACTION_SHARE
        if (ACTION_SEARCH !in base) base += ACTION_SEARCH
        return base.toTypedArray()
    }

    override fun isActionAvailable(action: String): Boolean =
        action == ACTION_SHARE || action == ACTION_SEARCH || super.isActionAvailable(action)

    override fun prepareAction(action: String, item: MenuItem) {
        when (action) {
            ACTION_SHARE -> item.setTitle(R.string.share)
            ACTION_SEARCH -> item.setTitle(R.string.search_web)
            else -> super.prepareAction(action, item)
        }
    }

    override fun performAction(action: String, item: MenuItem): Boolean = when (action) {
        ACTION_SHARE -> { selectedText().takeIf { it.isNotEmpty() }?.let(onShare); true }
        ACTION_SEARCH -> { selectedText().takeIf { it.isNotEmpty() }?.let(onSearch); true }
        else -> super.performAction(action, item)
    }

    private companion object {
        const val ACTION_SHARE = "app.multisession.browser.selection.share"
        const val ACTION_SEARCH = "app.multisession.browser.selection.search"
    }
}
