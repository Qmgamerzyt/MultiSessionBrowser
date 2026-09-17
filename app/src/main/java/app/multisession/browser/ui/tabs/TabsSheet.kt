package app.multisession.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.session.SessionManager
import app.multisession.browser.tabs.Tab
import app.multisession.browser.tabs.TabGroup
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Name + colour dialog for tab groups. */
object GroupEditDialog {
    fun show(view: View, existing: TabGroup?, onDone: (name: String, color: Int) -> Unit) {
        val ctx = view.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_group_edit, null)
        val input = v.findViewById<EditText>(R.id.groupNameInput)
        val row = v.findViewById<LinearLayout>(R.id.colorRow)
        input.setText(existing?.name ?: "")
        var selected = existing?.color ?: SessionManager.PALETTE[(0..7).random()]
        val density = ctx.resources.displayMetrics.density
        val size = (36 * density).toInt()
        val swatches = mutableListOf<Pair<Int, View>>()
        fun render() = swatches.forEach { (c, sw) ->
            sw.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == selected) setStroke((3 * density).toInt(), Color.WHITE) }
        }
        SessionManager.PALETTE.forEach { c ->
            val sw = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * density).toInt() }; setOnClickListener { selected = c; render() } }
            row.addView(sw); swatches += c to sw
        }
        render()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.new_group else R.string.rename_group)
            .setView(v)
            .setPositiveButton(if (existing == null) R.string.create else R.string.save) { _, _ -> onDone(input.text.toString(), selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }
}

/**
 * App-owned tab manager (Chrome-style groups). Rows = group header + its tabs (unless collapsed) ..., then "Other tabs".
 * Drag the handle to reorder; dropping a tab below another group's header moves it into that group. The resulting
 * order/membership is committed with TabManager.applyOrder and persisted. Empty groups stay listed.
 */
class TabsSheet : BottomSheetDialogFragment(), TabManager.Listener {

    private val core get() = BrowserApp.core()
    private val browser get() = activity as? BrowserActivity
    private lateinit var adapter: Adapter
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var reopenButton: View
    private val sessionId: String? get() = core.sessions.activeId

    sealed class Row {
        class Header(val group: TabGroup?) : Row()
        class TabRow(val tab: Tab) : Row()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_tabs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subtitle = view.findViewById(R.id.sheetSubtitle)
        emptyView = view.findViewById(R.id.emptyView)
        reopenButton = view.findViewById(R.id.reopenButton)
        adapter = Adapter()
        val recycler = view.findViewById<RecyclerView>(R.id.tabsRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        touchHelper.attachToRecyclerView(recycler)
        view.findViewById<View>(R.id.newTabButton).setOnClickListener { browser?.newTab(); dismiss() }
        view.findViewById<View>(R.id.newGroupButton).setOnClickListener { v ->
            val sid = sessionId ?: return@setOnClickListener
            GroupEditDialog.show(v, null) { name, color -> core.tabs.createGroup(sid, name.ifBlank { getString(R.string.group_default_name) }, color) }
        }
        reopenButton.setOnClickListener { sessionId?.let { sid -> core.tabs.reopenClosedTab(sid)?.let { t -> browser?.showTab(t); dismiss() } } }
        view.findViewById<View>(R.id.closeAllButton).setOnClickListener {
            val sid = sessionId ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.close_all).setMessage(R.string.confirm)
                .setPositiveButton(R.string.close_all) { _, _ -> browser?.closeAllTabs(sid); dismiss() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        core.tabs.addListener(this)
        rebuild()
    }

    override fun onDestroyView() {
        core.tabs.removeListener(this)
        super.onDestroyView()
    }

    override fun onTabsChanged(sessionId: String) { if (sessionId == this.sessionId && ::adapter.isInitialized) rebuild() }
    override fun onTabUpdated(tab: Tab) { if (tab.sessionId == sessionId && ::adapter.isInitialized) adapter.refreshTab(tab) }

    private fun rebuild() {
        val sid = sessionId ?: return
        val rows = mutableListOf<Row>()
        val groups = core.tabs.groupsFor(sid)
        groups.forEach { g ->
            rows += Row.Header(g)
            if (!g.collapsed) core.tabs.tabsInGroup(g.id).forEach { rows += Row.TabRow(it) }
        }
        val ungrouped = core.tabs.ungroupedTabs(sid)
        if (groups.isNotEmpty()) rows += Row.Header(null)
        ungrouped.forEach { rows += Row.TabRow(it) }
        adapter.submit(rows)
        val count = core.tabs.countFor(sid)
        subtitle.text = (core.sessions.get(sid)?.name ?: "") + " · " + resources.getString(if (count == 1) R.string.tab_count_fmt else R.string.tabs_count_fmt, count)
        emptyView.isVisible = count == 0 && groups.isEmpty()
        reopenButton.isVisible = core.tabs.hasRecentlyClosed(sid)
    }

    /** Reads the displayed order back into the model (hidden tabs of collapsed groups keep their group). */
    private fun commitOrder() {
        val sid = sessionId ?: return
        val ordered = mutableListOf<Pair<String, String?>>()
        var current: TabGroup? = null
        var sawHeader = false
        adapter.rows.forEach { r ->
            when (r) {
                is Row.Header -> { current = r.group; sawHeader = true }
                is Row.TabRow -> ordered += r.tab.id to (if (sawHeader) current?.id else r.tab.groupId)
            }
        }
        val seen = ordered.map { it.first }.toSet()
        core.tabs.tabsFor(sid).filter { it.id !in seen }.forEach { ordered += it.id to it.groupId }
        core.tabs.applyOrder(sid, ordered)
    }

    // ------------------------------------------------------------------ drag & swipe

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (vh !is TabVH) return 0
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }
        override fun isLongPressDragEnabled() = true
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val f = from.bindingAdapterPosition; val t = to.bindingAdapterPosition
            if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
            if (t == 0 && adapter.rows[0] is Row.Header) return false   // nothing above the first group header
            adapter.move(f, t); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val row = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.TabRow ?: return
            browser?.closeTab(row.tab) ?: core.tabs.closeTab(row.tab.id)
            rebuild()
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            if (adapter.dirty) { adapter.dirty = false; commitOrder() }
        }
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.85f
        }
    })

    // ------------------------------------------------------------------ menus

    private fun showTabMenu(anchor: View, tab: Tab) {
        val sid = tab.sessionId
        val groups = core.tabs.groupsFor(sid)
        val popup = PopupMenu(requireContext(), anchor)
        val menu = popup.menu
        menu.add(0, 1, 0, R.string.add_to_group)
        if (tab.groupId != null) menu.add(0, 2, 1, R.string.remove_from_group)
        menu.add(0, 3, 2, R.string.close_tab)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val names = groups.map { it.name } + getString(R.string.new_group)
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.move_to_group).setItems(names.toTypedArray()) { _, which ->
                        if (which < groups.size) core.tabs.setTabGroup(tab.id, groups[which].id)
                        else GroupEditDialog.show(anchor, null) { name, color ->
                            val g = core.tabs.createGroup(sid, name.ifBlank { getString(R.string.group_default_name) }, color)
                            core.tabs.setTabGroup(tab.id, g.id)
                        }
                    }.show()
                }
                2 -> core.tabs.setTabGroup(tab.id, null)
                3 -> { browser?.closeTab(tab) ?: core.tabs.closeTab(tab.id); rebuild() }
            }
            true
        }
        popup.show()
    }

    private fun showGroupMenu(anchor: View, g: TabGroup) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_group_item, popup.menu)
        val groups = core.tabs.groupsFor(g.sessionId)
        val idx = groups.indexOfFirst { it.id == g.id }
        popup.menu.findItem(R.id.action_group_up).isVisible = idx > 0
        popup.menu.findItem(R.id.action_group_down).isVisible = idx in 0 until groups.size - 1
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_group_open -> openGroup(g)
                R.id.action_group_new_tab -> newTabInGroup(g)
                R.id.action_group_rename -> GroupEditDialog.show(anchor, g) { name, color -> core.tabs.renameGroup(g.id, name, color) }
                R.id.action_group_up -> core.tabs.moveGroup(g.sessionId, idx, idx - 1)
                R.id.action_group_down -> core.tabs.moveGroup(g.sessionId, idx, idx + 1)
                R.id.action_group_close_tabs -> { val closed = core.tabs.closeGroupTabs(g.id); browser?.onTabsClosed(closed) }
                R.id.action_group_ungroup -> core.tabs.ungroup(g.id)
                R.id.action_group_delete -> MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_group_and_tabs)
                    .setMessage(getString(R.string.delete_group_confirm, g.name, core.tabs.tabsInGroup(g.id).size))
                    .setPositiveButton(R.string.delete) { _, _ -> val closed = core.tabs.tabsInGroup(g.id); core.tabs.deleteGroupWithTabs(g.id); browser?.onTabsClosed(closed) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            true
        }
        popup.show()
    }

    private fun openGroup(g: TabGroup) {
        core.tabs.setGroupCollapsed(g.id, false)
        val first = core.tabs.tabsInGroup(g.id).firstOrNull()
        if (first != null) { browser?.showTab(first); dismiss() } else newTabInGroup(g)
    }

    private fun newTabInGroup(g: TabGroup) {
        val b = browser ?: return
        b.newTab(groupId = g.id)
        dismiss()
    }

    // ------------------------------------------------------------------ adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows = mutableListOf<Row>()
        var dirty = false

        fun submit(list: List<Row>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
        fun move(from: Int, to: Int) { rows.add(to, rows.removeAt(from)); notifyItemMoved(from, to); dirty = true }
        fun refreshTab(tab: Tab) { val i = rows.indexOfFirst { it is Row.TabRow && it.tab.id == tab.id }; if (i >= 0) notifyItemChanged(i) }

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_tab_group, parent, false)) else TabVH(inf.inflate(R.layout.item_tab, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row.group)
                is Row.TabRow -> (holder as TabVH).bind(row.tab)
            }
        }
    }

    private inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val toggle: ImageView = v.findViewById(R.id.groupToggle)
        private val dot: View = v.findViewById(R.id.groupDot)
        private val name: TextView = v.findViewById(R.id.groupName)
        private val count: TextView = v.findViewById(R.id.groupCount)
        private val add: ImageButton = v.findViewById(R.id.groupAdd)
        private val more: ImageButton = v.findViewById(R.id.groupMore)
        fun bind(g: TabGroup?) {
            if (g == null) {
                name.text = getString(R.string.ungrouped_tabs); count.text = ""
                toggle.isVisible = false; dot.isVisible = false; add.isVisible = false; more.isVisible = false
                itemView.setOnClickListener(null); return
            }
            toggle.isVisible = true; dot.isVisible = true; add.isVisible = true; more.isVisible = true
            name.text = g.name
            val n = core.tabs.tabsInGroup(g.id).size
            count.text = if (n == 0) getString(R.string.group_empty) else n.toString()
            toggle.setImageResource(if (g.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(g.color) }
            itemView.setOnClickListener { core.tabs.setGroupCollapsed(g.id, !g.collapsed) }
            add.setOnClickListener { newTabInGroup(g) }
            more.setOnClickListener { showGroupMenu(it, g) }
        }
    }

    private inner class TabVH(v: View) : RecyclerView.ViewHolder(v) {
        private val drag: View = v.findViewById(R.id.tabDrag)
        private val bar: View = v.findViewById(R.id.tabGroupBar)
        private val thumb: ImageView = v.findViewById(R.id.tabThumbnail)
        private val title: TextView = v.findViewById(R.id.tabTitle)
        private val url: TextView = v.findViewById(R.id.tabUrl)
        private val live: View = v.findViewById(R.id.tabLive)
        private val more: View = v.findViewById(R.id.tabMore)
        private val close: View = v.findViewById(R.id.tabClose)
        fun bind(tab: Tab) {
            title.text = tab.displayTitle(itemView.context)
            url.text = if (tab.isStartPage) getString(R.string.start_page) else UrlUtils.displayHost(tab.url).ifBlank { tab.url }
            live.isVisible = tab.isLive
            val g = core.tabs.group(tab.groupId)
            bar.isVisible = g != null
            if (g != null) bar.setBackgroundColor(g.color)
            if (tab.thumbnail != null) thumb.setImageBitmap(tab.thumbnail) else thumb.setImageResource(if (tab.isStartPage) R.drawable.ic_search else R.drawable.ic_language)
            val isCurrent = browser?.currentTabIdOrNull() == tab.id
            itemView.setBackgroundColor(if (isCurrent) 0x22448AFF else Color.TRANSPARENT)
            itemView.setOnClickListener { browser?.showTab(tab); dismiss() }
            close.setOnClickListener { browser?.closeTab(tab) ?: core.tabs.closeTab(tab.id); rebuild() }
            more.setOnClickListener { showTabMenu(it, tab) }
            drag.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this); false }
        }
    }
}
