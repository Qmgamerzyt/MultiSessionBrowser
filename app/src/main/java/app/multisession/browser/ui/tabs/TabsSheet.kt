package app.multisession.browser.ui.tabs

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.session.SessionManager
import app.multisession.browser.tabs.Tab
import app.multisession.browser.tabs.TabGroup
import app.multisession.browser.tabs.TabManager
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
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
 * Chrome-style tab grid (v2.1.0). Sections: "Pinned" cards, one header + cards per group (collapsible), then
 * "Other tabs". Cards show thumbnail / title / domain / close / active stroke. Long-press drags a card: dropping it
 * under another header moves it into that group; pinned cards only move inside the pinned section and normal cards
 * never enter it (reordering can never unpin). The result is committed with TabManager.applyOrder.
 * "Select" enters multi-select mode with a compact bulk action bar. Opening the grid never touches GeckoSessions.
 */
class TabsSheet : BottomSheetDialogFragment(), TabManager.Listener {

    private val core get() = BrowserApp.core()
    private val browser get() = activity as? BrowserActivity
    private lateinit var adapter: Adapter
    private lateinit var recycler: RecyclerView
    private lateinit var subtitle: TextView
    private lateinit var emptyView: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var selectButton: ImageButton
    private lateinit var pinButton: TextView
    private lateinit var selectAllButton: TextView
    private val sessionId: String? get() = core.sessions.activeId
    private val selected = LinkedHashSet<String>()
    private var selectionMode = false
    private var spanCount = 2

    enum class Section { PINNED, GROUP, OTHER }

    sealed class Row(val key: String) {
        class Header(val section: Section, val group: TabGroup?) : Row("h:" + (group?.id ?: section.name))
        class Card(val tab: Tab) : Row("t:" + tab.id)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_tabs, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subtitle = view.findViewById(R.id.sheetSubtitle)
        emptyView = view.findViewById(R.id.emptyView)
        selectionBar = view.findViewById(R.id.selectionBar)
        selectionCount = view.findViewById(R.id.selectionCount)
        selectButton = view.findViewById(R.id.selectButton)
        pinButton = view.findViewById(R.id.bulkPin)
        selectAllButton = view.findViewById(R.id.bulkSelectAll)
        adapter = Adapter()
        recycler = view.findViewById(R.id.tabsRecycler)
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || resources.configuration.smallestScreenWidthDp >= 600
        spanCount = if (wide) 3 else 2
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.rows.getOrNull(position) is Row.Header) spanCount else 1
            }
        }
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        recycler.setItemViewCacheSize(12)
        touchHelper.attachToRecyclerView(recycler)
        // v2.1.7 (issues O/P): observe the gesture for swipe + hold-to-group without changing how the
        // events are delivered (always false -> RecyclerView/ItemTouchHelper see every event as before).
        recycler.setOnTouchListener(dragFingerListener)

        view.findViewById<View>(R.id.newTabButton).setOnClickListener { browser?.newTab(); dismiss() }
        view.findViewById<View>(R.id.newGroupButton).setOnClickListener { v ->
            val sid = sessionId ?: return@setOnClickListener
            GroupEditDialog.show(v, null) { name, color -> core.tabs.createGroup(sid, name.ifBlank { getString(R.string.group_default_name) }, color) }
        }
        selectButton.setOnClickListener { setSelectionMode(!selectionMode) }
        view.findViewById<View>(R.id.moreButton).setOnClickListener { showOverflow(it) }

        selectAllButton.setOnClickListener { toggleSelectAll() }
        view.findViewById<View>(R.id.bulkClose).setOnClickListener { closeSelected() }
        view.findViewById<View>(R.id.bulkMove).setOnClickListener { moveSelected(it) }
        view.findViewById<View>(R.id.bulkGroup).setOnClickListener { groupSelected(it) }
        view.findViewById<View>(R.id.bulkUngroup).setOnClickListener { withSelection { core.tabs.setTabsGroup(it, null) } }
        view.findViewById<View>(R.id.bulkArchive).setOnClickListener { archiveSelected() }
        pinButton.setOnClickListener { withSelection { ids -> core.tabs.setPinned(ids, !ids.all { core.tabs.get(it)?.pinned == true }) } }
        view.findViewById<View>(R.id.bulkDone).setOnClickListener { setSelectionMode(false) }

        core.tabs.addListener(this)
        rebuild()
        scrollToCurrent()
    }

    override fun onDestroyView() {
        core.tabs.removeListener(this)
        super.onDestroyView()
    }

    override fun onTabsChanged(sessionId: String) { if (sessionId == this.sessionId && ::adapter.isInitialized) rebuild() }
    override fun onTabUpdated(tab: Tab) { if (tab.sessionId == sessionId && ::adapter.isInitialized) adapter.refreshTab(tab) }

    // ------------------------------------------------------------------ rows

    private fun rebuild() {
        val sid = sessionId ?: return
        val rows = mutableListOf<Row>()
        val pinned = core.tabs.pinnedTabs(sid)
        val groups = core.tabs.groupsFor(sid)
        if (pinned.isNotEmpty()) { rows += Row.Header(Section.PINNED, null); pinned.forEach { rows += Row.Card(it) } }
        groups.forEach { g ->
            rows += Row.Header(Section.GROUP, g)
            if (!g.collapsed) core.tabs.tabsInGroup(g.id).filter { !it.pinned }.forEach { rows += Row.Card(it) }
        }
        val ungrouped = core.tabs.ungroupedTabs(sid)
        if (groups.isNotEmpty() || pinned.isNotEmpty()) rows += Row.Header(Section.OTHER, null)
        ungrouped.forEach { rows += Row.Card(it) }
        adapter.submit(rows)
        val count = core.tabs.countFor(sid)
        val archived = core.tabs.archivedCountFor(sid)
        var text = (core.sessions.get(sid)?.name ?: "") + " · " + resources.getString(if (count == 1) R.string.tab_count_fmt else R.string.tabs_count_fmt, count)
        if (archived > 0) text += " · " + getString(R.string.archived_count_fmt, archived)
        subtitle.text = text
        emptyView.isVisible = count == 0 && groups.isEmpty()
        if (selectionMode) {
            val visible = adapter.rows.filterIsInstance<Row.Card>().map { it.tab.id }.toSet()
            selected.retainAll(visible)
            updateSelectionBar()
        }
    }

    private fun scrollToCurrent() {
        val cur = browser?.currentTabIdOrNull() ?: return
        val i = adapter.rows.indexOfFirst { it is Row.Card && it.tab.id == cur }
        if (i > 0) recycler.post { (recycler.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(i, 0) }
    }

    /** Reads the displayed order back into the model (hidden tabs of collapsed groups keep their group). */
    private fun commitOrder() {
        val sid = sessionId ?: return
        val ordered = mutableListOf<Pair<String, String?>>()
        var current: Row.Header? = null
        adapter.rows.forEach { r ->
            when (r) {
                is Row.Header -> current = r
                is Row.Card -> ordered += r.tab.id to when (current?.section) {
                    Section.GROUP -> current?.group?.id
                    Section.OTHER -> null
                    Section.PINNED -> r.tab.groupId      // pinned cards keep their membership
                    null -> null
                }
            }
        }
        val seen = ordered.map { it.first }.toSet()
        core.tabs.tabsFor(sid).filter { it.id !in seen }.forEach { ordered += it.id to it.groupId }
        core.tabs.applyOrder(sid, ordered)
    }

    private fun sectionAt(list: List<Row>, index: Int): Section {
        for (i in index - 1 downTo 0) (list[i] as? Row.Header)?.let { return it.section }
        return Section.OTHER
    }

    // ------------------------------------------------------------------ drag & drop / swipe (v2.1.7)

    /** Finger tracking while a drag runs (issue P: ItemTouchHelper reports positions, never "on vs beside"). */
    private var dragHolder: RecyclerView.ViewHolder? = null
    private var fingerSeen = false
    private var fingerX = 0f
    private var fingerY = 0f
    private var groupTargetId: String? = null
    private var groupTargetCard: MaterialCardView? = null
    private val holdGroupRunnable = Runnable { evaluateGroupTarget() }
    private val swipePaint = Paint().apply { color = 0x33E53935 }   // translucent red behind a swiping card

    private fun resetHoldTimer() { recycler.removeCallbacks(holdGroupRunnable); recycler.postDelayed(holdGroupRunnable, HOLD_GROUP_MS) }
    private fun cancelHoldTimer() { recycler.removeCallbacks(holdGroupRunnable) }
    private fun clearGroupTarget() { groupTargetCard?.let { it.strokeWidth = 0 }; groupTargetCard = null; groupTargetId = null }

    /**
     * Observes the gesture while ItemTouchHelper is dragging and re-arms the 600 ms hold timer as soon
     * as the finger moves. Always returns false: RecyclerView keeps the events exactly as before
     * (ItemTouchHelper installs its own OnItemTouchListener, not an OnTouchListener).
     */
    private val dragFingerListener = View.OnTouchListener { _, ev ->
        if (dragHolder != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.abs(ev.x - fingerX) > 6 || kotlin.math.abs(ev.y - fingerY) > 6
                    fingerX = ev.x; fingerY = ev.y; fingerSeen = true
                    if (moved) { clearGroupTarget(); resetHoldTimer() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cancelHoldTimer(); clearGroupTarget() }
                else -> Unit
            }
        }
        false
    }

    /** The hold elapsed: is the finger resting on a DIFFERENT tab? Then that tab is the group target. */
    private fun evaluateGroupTarget() {
        val dragged = dragHolder ?: return
        if (!fingerSeen) return
        val under = recycler.findChildViewUnder(fingerX, fingerY) ?: return
        if (under === dragged.itemView) return                 // resting on its own slot = ordinary reorder
        val vh = recycler.getChildViewHolder(under) as? CardVH ?: return
        val pos = vh.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val card = adapter.rows.getOrNull(pos) as? Row.Card ?: return
        if (card.tab.id == groupTargetId) return
        clearGroupTarget()
        groupTargetId = card.tab.id
        (under as? MaterialCardView)?.let { c ->
            c.strokeColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
            c.strokeWidth = (3 * resources.displayMetrics.density).toInt()
            groupTargetCard = c
        }
    }

    /**
     * v2.1.7 (issue P): dropping a tab after holding it over another tab groups the two. The target
     * already having a group wins (the dragged tab joins it); otherwise the dragged tab's group is
     * reused; only then is a new group created (same default name/colour as the group dialog).
     */
    private fun groupByDrag(draggedId: String, targetId: String) {
        val sid = sessionId ?: return
        val dragged = core.tabs.get(draggedId) ?: return
        val target = core.tabs.get(targetId) ?: return
        if (dragged.id == target.id) return
        val gid = target.groupId ?: dragged.groupId
            ?: core.tabs.createGroup(sid, getString(R.string.group_default_name), 0xFF2962FF.toInt()).id
        core.tabs.setTabsGroup(listOf(draggedId, targetId), gid)
        adapter.dirty = false
        rebuild()
        browser?.snack(getString(R.string.group_created))
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (vh !is CardVH || selectionMode) return 0
            val card = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Card
            // v2.1.7 (issue O): swipe-to-close, but never for a pinned tab - those must not vanish by accident.
            val swipe = if (card?.tab?.pinned == true) 0 else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, swipe)
        }
        override fun isLongPressDragEnabled() = !selectionMode
        override fun isItemViewSwipeEnabled() = !selectionMode
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            // Any reorder cancels the pending hold: the user is moving, not resting on a tab.
            cancelHoldTimer(); clearGroupTarget()
            val f = from.bindingAdapterPosition; val t = to.bindingAdapterPosition
            if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
            if (t == 0 && adapter.rows[0] is Row.Header) return false   // nothing above the first header
            val card = adapter.rows[f] as? Row.Card ?: return false
            val copy = adapter.rows.toMutableList(); copy.add(t, copy.removeAt(f))
            val targetSection = sectionAt(copy, t)
            if ((targetSection == Section.PINNED) != card.tab.pinned) return false   // pinned stays pinned, normal stays normal
            adapter.move(f, t); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val card = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Card
            cancelHoldTimer(); clearGroupTarget(); dragHolder = null; fingerSeen = false
            // v2.1.7 (issue O): a swipe closes the tab immediately and offers Undo - no dialog, the
            // gesture itself is the confirmation (Chrome/Firefox Android behave the same).
            if (card != null) {
                val b = browser
                if (b != null) b.closeTab(card.tab, undo = true) else core.tabs.closeTab(card.tab.id)
            }
            rebuild()
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f; vh.itemView.scaleX = 1f; vh.itemView.scaleY = 1f
            val pos = vh.bindingAdapterPosition
            val draggedId = if (pos == RecyclerView.NO_POSITION) null else (adapter.rows.getOrNull(pos) as? Row.Card)?.tab?.id
            val targetId = groupTargetId
            cancelHoldTimer(); clearGroupTarget(); dragHolder = null; fingerSeen = false
            when {
                targetId != null && draggedId != null && draggedId != targetId -> groupByDrag(draggedId, targetId)
                adapter.dirty -> { adapter.dirty = false; commitOrder() }
            }
        }
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragHolder = vh; fingerSeen = false
                clearGroupTarget(); resetHoldTimer()
                vh?.itemView?.apply { alpha = 0.9f; scaleX = 1.04f; scaleY = 1.04f }
            }
        }
        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                c.drawRect(0f, vh.itemView.top.toFloat(), rv.width.toFloat(), vh.itemView.bottom.toFloat(), swipePaint)
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }
    })

    // ------------------------------------------------------------------ selection / bulk actions

    private fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selected.clear()
        selectionBar.isVisible = on
        selectButton.alpha = if (on) 1f else 0.7f
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun toggleSelection(tab: Tab) {
        if (!selected.remove(tab.id)) selected += tab.id
        adapter.refreshTab(tab)
        updateSelectionBar()
    }

    private fun toggleSelectAll() {
        val visible = adapter.rows.filterIsInstance<Row.Card>().map { it.tab.id }
        if (selected.size == visible.size) selected.clear() else { selected.clear(); selected += visible }
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        if (!selectionMode) return
        selectionCount.text = getString(R.string.selected_fmt, selected.size)
        val visible = adapter.rows.count { it is Row.Card }
        selectAllButton.text = getString(if (selected.isNotEmpty() && selected.size == visible) R.string.clear_selection else R.string.select_all)
        val allPinned = selected.isNotEmpty() && selected.all { core.tabs.get(it)?.pinned == true }
        pinButton.text = getString(if (allPinned) R.string.unpin else R.string.pin)
    }

    private inline fun withSelection(block: (List<String>) -> Unit) {
        val ids = selected.toList()
        if (ids.isEmpty()) { browser?.snack(getString(R.string.nothing_selected)); return }
        block(ids)
        setSelectionMode(false)
    }

    private fun closeSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) { browser?.snack(getString(R.string.nothing_selected)); return }
        val doClose = {
            val closed = core.tabs.closeTabs(ids)
            browser?.onTabsClosed(closed)
            browser?.offerUndo(closed)          // v2.1.7 (issue I): confirmation stays, Undo added
            setSelectionMode(false)
        }
        if (ids.size == 1) doClose() else MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.close_tabs_fmt, ids.size)).setMessage(R.string.confirm)
            .setPositiveButton(R.string.close_tab) { _, _ -> doClose() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun archiveSelected() = withSelection { ids ->
        val archived = core.tabs.archiveTabs(ids)
        browser?.onTabsClosed(archived)
        offerArchiveUndo(archived)
    }

    /**
     * Archiving is fully reversible, so v2.1.7 (issue I) swaps the plain toast for a Snackbar with
     * Undo: [TabManager.unarchiveTabs] brings the same tabs back, they stay in their group and pinned
     * state, and their session is untouched. The sheet refreshes itself through [onTabsChanged].
     */
    private fun offerArchiveUndo(archived: List<Tab>) {
        if (archived.isEmpty()) return
        val ids = archived.map { it.id }
        // Both messages are resolved NOW, while the fragment is still attached: the Undo action runs
        // later, when the sheet may already be dismissed (Fragment.getString() needs a context).
        val message = getString(R.string.tabs_archived_fmt, archived.size)
        val restored = getString(R.string.tabs_unarchived_fmt, ids.size)
        browser?.snackUndo(message) {
            core.tabs.unarchiveTabs(ids)
            browser?.snack(restored)
        }
    }

    private fun moveSelected(anchor: View) {
        val sid = sessionId ?: return
        val ids = selected.toList()
        if (ids.isEmpty()) { browser?.snack(getString(R.string.nothing_selected)); return }
        pickGroup(anchor, sid) { gid -> core.tabs.setTabsGroup(ids, gid); setSelectionMode(false) }
    }

    private fun groupSelected(anchor: View) {
        val sid = sessionId ?: return
        val ids = selected.toList()
        if (ids.isEmpty()) { browser?.snack(getString(R.string.nothing_selected)); return }
        GroupEditDialog.show(anchor, null) { name, color ->
            val g = core.tabs.createGroup(sid, name.ifBlank { getString(R.string.group_default_name) }, color)
            core.tabs.setTabsGroup(ids, g.id)
            setSelectionMode(false)
        }
    }

    /** Group chooser: existing groups, "No group", "New group…". */
    private fun pickGroup(anchor: View, sid: String, onPicked: (String?) -> Unit) {
        val groups = core.tabs.groupsFor(sid)
        val names = groups.map { it.name } + getString(R.string.no_group) + getString(R.string.new_group)
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.move_to_group).setItems(names.toTypedArray()) { _, which ->
            when {
                which < groups.size -> onPicked(groups[which].id)
                which == groups.size -> onPicked(null)
                else -> GroupEditDialog.show(anchor, null) { name, color ->
                    onPicked(core.tabs.createGroup(sid, name.ifBlank { getString(R.string.group_default_name) }, color).id)
                }
            }
        }.show()
    }

    // ------------------------------------------------------------------ menus

    private fun showOverflow(anchor: View) {
        val sid = sessionId ?: return
        val b = browser ?: return
        val popup = PopupMenu(requireContext(), anchor)
        val m = popup.menu
        m.add(0, 1, 0, R.string.recently_closed).isEnabled = core.tabs.hasRecentlyClosed(sid)
        val archived = core.tabs.archivedCountFor(sid)
        m.add(0, 2, 1, getString(R.string.archived_tabs) + if (archived > 0) " ($archived)" else "")
        m.add(0, 3, 2, R.string.workspaces)
        m.add(0, 4, 3, R.string.close_all)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> TabDialogs.recentlyClosed(b, core, sid) { t -> if (t != null) { b.showTab(t); dismiss() } }
                2 -> TabDialogs.archived(b, core, sid) { restored -> restored.firstOrNull()?.let { b.showTab(it) }; if (restored.isNotEmpty()) dismiss() }
                3 -> TabDialogs.workspaces(b, core, sid) { t -> if (t != null) { b.showTab(t); dismiss() } }
                4 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.close_all).setMessage(R.string.confirm)
                    .setPositiveButton(R.string.close_all) { _, _ -> b.closeAllTabs(sid); dismiss() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            true
        }
        popup.show()
    }

    private fun showTabMenu(anchor: View, tab: Tab) {
        val sid = tab.sessionId
        val popup = PopupMenu(requireContext(), anchor)
        val menu = popup.menu
        menu.add(0, 1, 0, if (tab.pinned) R.string.unpin_tab else R.string.pin_tab)
        menu.add(0, 2, 1, if (tab.groupId == null) R.string.add_to_group else R.string.move_to_group)
        if (tab.groupId != null) menu.add(0, 3, 2, R.string.remove_from_group)
        menu.add(0, 4, 3, R.string.archive_tab)
        menu.add(0, 5, 4, R.string.select_tabs)
        menu.add(0, 6, 5, R.string.close_tab)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> core.tabs.setPinned(listOf(tab.id), !tab.pinned)
                2 -> pickGroup(anchor, sid) { gid -> core.tabs.setTabGroup(tab.id, gid) }
                3 -> core.tabs.setTabGroup(tab.id, null)
                4 -> { val a = core.tabs.archiveTabs(listOf(tab.id)); browser?.onTabsClosed(a); offerArchiveUndo(a) }
                5 -> { setSelectionMode(true); toggleSelection(tab) }
                6 -> { browser?.closeTab(tab, undo = true) ?: core.tabs.closeTab(tab.id); rebuild() }
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
        popup.menu.add(0, ID_GROUP_ARCHIVE, 100, R.string.archive_group_tabs)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_group_open -> openGroup(g)
                R.id.action_group_new_tab -> newTabInGroup(g)
                R.id.action_group_rename -> GroupEditDialog.show(anchor, g) { name, color -> core.tabs.renameGroup(g.id, name, color) }
                R.id.action_group_up -> core.tabs.moveGroup(g.sessionId, idx, idx - 1)
                R.id.action_group_down -> core.tabs.moveGroup(g.sessionId, idx, idx + 1)
                R.id.action_group_close_tabs -> { val closed = core.tabs.closeGroupTabs(g.id); browser?.onTabsClosed(closed); browser?.offerUndo(closed) }
                R.id.action_group_ungroup -> core.tabs.ungroup(g.id)
                ID_GROUP_ARCHIVE -> { val a = core.tabs.archiveTabs(core.tabs.tabsInGroup(g.id).map { it.id }); browser?.onTabsClosed(a); offerArchiveUndo(a) }
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

        init { setHasStableIds(true) }

        fun submit(list: List<Row>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
        fun move(from: Int, to: Int) { rows.add(to, rows.removeAt(from)); notifyItemMoved(from, to); dirty = true }
        fun refreshTab(tab: Tab) { val i = rows.indexOfFirst { it is Row.Card && it.tab.id == tab.id }; if (i >= 0) notifyItemChanged(i) }

        override fun getItemId(position: Int): Long = rows[position].key.hashCode().toLong()
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_tab_group, parent, false)) else CardVH(inf.inflate(R.layout.item_tab, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row)
                is Row.Card -> (holder as CardVH).bind(row.tab)
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
        fun bind(h: Row.Header) {
            val g = h.group
            when (h.section) {
                Section.PINNED -> {
                    name.text = getString(R.string.pinned_tabs); count.text = core.tabs.pinnedTabs(sessionId ?: "").size.toString()
                    toggle.isVisible = true; toggle.setImageResource(R.drawable.ic_pin)
                    dot.isVisible = false; add.isVisible = false; more.isVisible = false
                    itemView.setOnClickListener(null)
                }
                Section.OTHER -> {
                    name.text = getString(R.string.ungrouped_tabs); count.text = ""
                    toggle.isVisible = false; dot.isVisible = false; add.isVisible = false; more.isVisible = false
                    itemView.setOnClickListener(null)
                }
                Section.GROUP -> {
                    g ?: return
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
        }
    }

    private inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card = v as MaterialCardView
        private val bar: View = v.findViewById(R.id.tabGroupBar)
        private val thumb: ImageView = v.findViewById(R.id.tabThumbnail)
        private val placeholder: ImageView = v.findViewById(R.id.tabPlaceholder)
        private val title: TextView = v.findViewById(R.id.tabTitle)
        private val url: TextView = v.findViewById(R.id.tabUrl)
        private val live: View = v.findViewById(R.id.tabLive)
        private val pin: View = v.findViewById(R.id.tabPinned)
        private val more: View = v.findViewById(R.id.tabMore)
        private val close: View = v.findViewById(R.id.tabClose)
        private val check: CheckBox = v.findViewById(R.id.tabCheck)
        private val strokeColor = MaterialColors.getColor(v, androidx.appcompat.R.attr.colorPrimary)
        private val strokePx = (2 * v.resources.displayMetrics.density).toInt()

        fun bind(tab: Tab) {
            title.text = tab.displayTitle(itemView.context)
            url.text = if (tab.isStartPage) getString(R.string.start_page) else UrlUtils.displayHost(tab.url).ifBlank { tab.url }
            live.isVisible = tab.isLive
            pin.isVisible = tab.pinned
            val g = core.tabs.group(tab.groupId)
            bar.isVisible = g != null
            if (g != null) bar.setBackgroundColor(g.color)
            val bmp = tab.thumbnail
            if (bmp != null && !bmp.isRecycled) { thumb.setImageBitmap(bmp); thumb.isVisible = true; placeholder.isVisible = false }
            else { thumb.setImageDrawable(null); thumb.isVisible = false; placeholder.isVisible = true; placeholder.setImageResource(if (tab.isStartPage) R.drawable.ic_search else R.drawable.ic_language) }
            val isCurrent = browser?.currentTabIdOrNull() == tab.id
            card.strokeWidth = if (isCurrent) strokePx else 0
            card.strokeColor = strokeColor
            val sel = selectionMode && tab.id in selected
            check.isVisible = selectionMode
            check.isChecked = sel
            close.isVisible = !selectionMode
            more.isVisible = !selectionMode
            card.isChecked = sel
            itemView.setOnClickListener { if (selectionMode) toggleSelection(tab) else { browser?.showTab(tab); dismiss() } }
            check.setOnClickListener { toggleSelection(tab) }
            close.setOnClickListener { browser?.closeTab(tab, undo = true) ?: core.tabs.closeTab(tab.id); rebuild() }
            more.setOnClickListener { showTabMenu(it, tab) }
        }
    }

    private companion object {
        const val ID_GROUP_ARCHIVE = 9101
        /** v2.1.7 (issue P): how long the finger must rest on another tab before a release groups them. */
        const val HOLD_GROUP_MS = 600L
    }
}
