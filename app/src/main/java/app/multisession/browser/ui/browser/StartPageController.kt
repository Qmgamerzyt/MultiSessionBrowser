package app.multisession.browser.ui.browser

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.multisession.browser.R
import app.multisession.browser.core.BrowserCore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Native start page: search box, session chips, bookmarks and recent history of the current session. */
class StartPageController(val view: View, private val core: BrowserCore, private val callbacks: Callbacks) {

    interface Callbacks {
        fun openUrl(url: String, newTab: Boolean = false)
        fun focusUrlBar()
        fun switchSession(sessionId: String)
        fun openSessions()
    }

    private val context get() = view.context
    private val subtitle: TextView = view.findViewById(R.id.startSubtitle)
    private val isolationNote: TextView = view.findViewById(R.id.startIsolation)
    private val chips: ChipGroup = view.findViewById(R.id.sessionChips)
    private val bookmarksList: LinearLayout = view.findViewById(R.id.bookmarksList)
    private val bookmarksEmpty: TextView = view.findViewById(R.id.bookmarksEmpty)
    private val recentList: LinearLayout = view.findViewById(R.id.recentList)
    private val recentEmpty: TextView = view.findViewById(R.id.recentEmpty)

    init {
        view.findViewById<View>(R.id.startSearch).setOnClickListener { callbacks.focusUrlBar() }
        view.findViewById<View>(R.id.manageSessions).setOnClickListener { callbacks.openSessions() }
    }

    suspend fun refresh(sessionId: String) {
        val session = core.sessions.get(sessionId)
        subtitle.text = context.getString(R.string.start_session_hint, session?.name ?: "")
        isolationNote.text = core.isolation.describe(context)

        chips.removeAllViews()
        core.sessions.sessions.value.forEach { s ->
            val chip = Chip(context)
            chip.text = s.name
            chip.isCheckable = true
            chip.isChecked = s.id == sessionId
            chip.chipIcon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(s.color)
                val size = (12 * context.resources.displayMetrics.density).toInt()
                setSize(size, size)
            }
            chip.isChipIconVisible = true
            chip.setOnClickListener { if (s.id != sessionId) callbacks.switchSession(s.id) }
            chips.addView(chip)
        }

        val bookmarks = core.repo.bookmarks.forSession(sessionId, 8)
        fill(bookmarksList, bookmarksEmpty, bookmarks.map { Row(it.title, it.url, R.drawable.ic_star) })
        val recent = core.repo.history.recent(sessionId, 10)
        fill(recentList, recentEmpty, recent.map { Row(it.title, it.url, R.drawable.ic_history) })
    }

    private data class Row(val title: String, val url: String, val icon: Int)

    private fun fill(container: LinearLayout, empty: TextView, rows: List<Row>) {
        container.removeAllViews()
        empty.isVisible = rows.isEmpty()
        val inflater = LayoutInflater.from(context)
        rows.forEach { row ->
            val item = inflater.inflate(R.layout.item_link, container, false)
            item.findViewById<TextView>(R.id.linkTitle).text = row.title.ifBlank { row.url }
            item.findViewById<TextView>(R.id.linkSubtitle).text = row.url
            item.findViewById<ImageView>(R.id.linkIcon).setImageResource(row.icon)
            item.setOnClickListener { callbacks.openUrl(row.url) }
            item.setOnLongClickListener { callbacks.openUrl(row.url, newTab = true); true }
            container.addView(item)
        }
    }
}
