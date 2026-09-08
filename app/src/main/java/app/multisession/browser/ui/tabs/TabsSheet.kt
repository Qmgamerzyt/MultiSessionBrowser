package app.multisession.browser.ui.tabs

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.tabs.Tab
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView

/** Chrome-like tab overview for the active session: tap to switch, X or swipe to close, long-press drag to reorder. */
class TabsSheet : BottomSheetDialogFragment() {

    private val core get() = BrowserApp.core()
    private val browser get() = activity as? BrowserActivity
    private lateinit var adapter: TabsAdapter
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private var sessionId: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_tabs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionId = core.sessions.activeId ?: run { dismiss(); return }
        val session = core.sessions.get(sessionId)
        view.findViewById<TextView>(R.id.sheetTitle).text = session?.name ?: ""
        countView = view.findViewById(R.id.sheetSubtitle)
        emptyView = view.findViewById(R.id.emptyView)

        val activeId = core.sessions.get(sessionId)?.activeTabId
        adapter = TabsAdapter(activeId, onClick = { tab -> browser?.showTab(tab); dismiss() }, onClose = { tab -> close(tab) })
        val recycler = view.findViewById<RecyclerView>(R.id.tabsRecycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        recycler.adapter = adapter
        ItemTouchHelper(TouchCallback()).attachToRecyclerView(recycler)

        view.findViewById<View>(R.id.newTabButton).setOnClickListener { browser?.newTab(); dismiss() }
        view.findViewById<View>(R.id.closeAllButton).setOnClickListener {
            core.tabs.closeAllTabs(sessionId)
            browser?.let { b -> b.showTab(core.tabs.createTab(sessionId, select = true)) }
            dismiss()
        }
        val reopen = view.findViewById<View>(R.id.reopenButton)
        reopen.isVisible = core.tabs.hasRecentlyClosed(sessionId)
        reopen.setOnClickListener { core.tabs.reopenClosedTab(sessionId)?.let { browser?.showTab(it) }; dismiss() }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val height = (resources.displayMetrics.heightPixels * 0.85).toInt()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.layoutParams?.height = height
        dialog.behavior.peekHeight = height
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun refresh() {
        val tabs = core.tabs.tabsFor(sessionId)
        adapter.submit(tabs)
        countView.text = resources.getQuantityString(R.plurals.tabs_count, tabs.size, tabs.size)
        emptyView.isVisible = tabs.isEmpty()
    }

    private fun close(tab: Tab) {
        val b = browser
        if (b != null) b.closeTab(tab) else core.tabs.closeTab(tab.id)
        refresh()
    }

    private inner class TouchCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            core.tabs.moveTab(sessionId, from, to)
            adapter.move(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            adapter.itemAt(viewHolder.bindingAdapterPosition)?.let { close(it) }
        }

        override fun isLongPressDragEnabled() = true
    }

    class TabsAdapter(
        private val activeTabId: String?,
        private val onClick: (Tab) -> Unit,
        private val onClose: (Tab) -> Unit,
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {
        private val items = mutableListOf<Tab>()

        fun submit(list: List<Tab>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        fun itemAt(pos: Int): Tab? = items.getOrNull(pos)

        fun move(from: Int, to: Int) {
            val t = items.removeAt(from); items.add(to, t); notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab = items[position]
            val ctx = holder.itemView.context
            holder.title.text = tab.displayTitle(ctx)
            holder.url.text = if (tab.isStartPage) ctx.getString(R.string.start_page) else UrlUtils.displayHost(tab.url)
            val thumb: Bitmap? = tab.thumbnail
            if (thumb != null) holder.thumbnail.setImageBitmap(thumb) else holder.thumbnail.setImageResource(R.drawable.ic_language)
            holder.thumbnail.scaleType = if (thumb != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER
            val fav = tab.favicon
            if (fav != null) holder.favicon.setImageBitmap(fav) else holder.favicon.setImageResource(R.drawable.ic_language)
            val active = tab.id == activeTabId
            holder.card.strokeWidth = if (active) (3 * ctx.resources.displayMetrics.density).toInt() else 0
            holder.liveBadge.isVisible = tab.isLive
            holder.itemView.setOnClickListener { onClick(tab) }
            holder.close.setOnClickListener { onClose(tab) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v as MaterialCardView
            val title: TextView = v.findViewById(R.id.tabTitle)
            val url: TextView = v.findViewById(R.id.tabUrl)
            val thumbnail: ImageView = v.findViewById(R.id.tabThumbnail)
            val favicon: ImageView = v.findViewById(R.id.tabFavicon)
            val close: View = v.findViewById(R.id.tabClose)
            val liveBadge: View = v.findViewById(R.id.tabLive)
        }
    }
}
