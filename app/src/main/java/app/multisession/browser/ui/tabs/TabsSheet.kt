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
 * "Other tabs". Cards show thumbnail / title / domain / close / active stroke. Long-press drags - the grid stays
 * STATIC while the finger moves (v2.1.10), and the release decides everything, Chrome-style: over another tab's
 * card = the two group immediately (unless both pinned, either pinned, or already in the same group - then it is
 * a plain reorder); over a group header = join that group; anywhere else = insert at the nearest card's nearer
 * edge. GROUP headers drag too: the header moves with its visible members as one block, so groups reorder.
 * Pinned cards only move inside the pinned section and normal cards never enter it (reordering can never unpin).
 * The result is committed with TabManager.applyOrder (cards) / applyGroupOrder (headers).
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
        // v2.1.7 (issue O) / v2.1.10 (A9): observe the gesture without changing how the events are
        // delivered (always false -> RecyclerView/ItemTouchHelper see every event as before); the
        // listener only records the finger so clearView can decide where the drop landed.
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
    override fun onTabUpdated(tab: Tab) {
        if (tab.sessionId != sessionId || !::adapter.isInitialized) return
        // v2.1.10 (A9): any adapter notification while a drag runs can cancel it mid-gesture
        // (layout detaches the dragged holder -> clearView sees NO_POSITION -> drop lost).
        // Defer both: rebuild() runs it after the drop, this just sets the same flag.
        if (dragHolder != null) { dragPendingRebuild = true; return }
        adapter.refreshTab(tab)
    }

    // ------------------------------------------------------------------ rows

    private fun rebuild() {
        if (dragHolder != null) { dragPendingRebuild = true; return }
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

    // ------------------------------------------------------------------ drag & drop / swipe (v2.1.7, reworked v2.1.10)

    /** Finger tracking while a drag runs (issue P: ItemTouchHelper reports positions, never "on vs beside"). */
    private var dragHolder: RecyclerView.ViewHolder? = null
    /** The row currently dragged (card or GROUP header), captured at drag start - stable even while positions shift. */
    private var draggedRow: Row? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var groupTargetId: String? = null
    private var headerTarget: Row.Header? = null
    private var headerTargetView: View? = null
    /** A rebuild/tab refresh arrived while a drag ran; consumed right after the drop dispatch. */
    private var dragPendingRebuild = false
    private val swipePaint = Paint().apply { color = 0x33E53935 }   // translucent red behind a swiping card

    /**
     * v2.1.10 (A9): clears both drop highlights. The card stroke is restored through refreshTab -
     * a plain `strokeWidth = 0` would also erase the current-tab stroke that bind() applies.
     */
    private fun clearDropTarget() {
        val prevId = groupTargetId
        headerTargetView?.alpha = 1f
        headerTargetView = null
        headerTarget = null
        groupTargetId = null
        if (prevId != null) core.tabs.get(prevId)?.let { adapter.refreshTab(it) }
    }

    private fun setCardTarget(card: MaterialCardView?, id: String) {
        if (id == groupTargetId) return
        clearDropTarget()
        groupTargetId = id
        card?.strokeColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        card?.strokeWidth = (3 * resources.displayMetrics.density).toInt()
    }

    private fun setHeaderTarget(view: View, header: Row.Header) {
        if (header === headerTarget) return
        clearDropTarget()
        headerTarget = header
        headerTargetView = view
        view.alpha = 0.55f
    }

    /**
     * Recomputes where the finger is, on every gesture event: over another tab's card (both
     * unpinned, not the same group) = grouping target; over a GROUP header while dragging a card
     * (card ungrouped or in another group) = join target; anything else clears the highlights.
     */
    private fun updateDropTarget() {
        val draggedVh = dragHolder ?: return
        val under = recycler.findChildViewUnder(fingerX, fingerY)
        if (under == null || under === draggedVh.itemView) { clearDropTarget(); return }
        val vh = recycler.getChildViewHolder(under)
        val src = draggedRow
        val pos = vh.bindingAdapterPosition
        val row = if (pos == RecyclerView.NO_POSITION) null else adapter.rows.getOrNull(pos)
        when {
            vh is CardVH && src is Row.Card -> {
                val target = row as? Row.Card
                // Group on drop unless same group (both ungrouped DOES group - a new one is created),
                // same id, or either side pinned (pinned tabs are reordered in place, never grouped).
                val differentGroup = target != null &&
                    (target.tab.groupId == null || target.tab.groupId != src.tab.groupId)
                if (target != null && target.tab.id != src.tab.id && !target.tab.pinned &&
                    !src.tab.pinned && differentGroup
                ) {
                    setCardTarget(under as? MaterialCardView, target.tab.id)
                } else clearDropTarget()
            }
            vh is HeaderVH && src is Row.Card -> {
                val header = row as? Row.Header
                if (header != null && header.section == Section.GROUP && !src.tab.pinned &&
                    header.group?.id != src.tab.groupId
                ) {
                    setHeaderTarget(under, header)
                } else clearDropTarget()
            }
            else -> clearDropTarget()
        }
    }

    /**
     * Records where the finger is while ItemTouchHelper drags; always returns false, so RecyclerView
     * and ItemTouchHelper see every event exactly as before (they install their own item touch
     * listener - this is a plain OnTouchListener on the RecyclerView). UP/CANCEL deliberately do NOT
     * clear the targets: clearView runs right after this listener and reads them (the old hold-timer
     * cleared them first, which is why grouping never fired on release).
     */
    private val dragFingerListener = View.OnTouchListener { _, ev ->
        // Safety net: a fresh gesture can only start after the previous stream ended, so a
        // dragHolder left behind by a silently-cancelled drag is stale - clear it (and any
        // leftover highlight) instead of feeding targets forever.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && dragHolder != null) abortStaleDrag()
        if (dragHolder != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fingerX = ev.x; fingerY = ev.y
                    updateDropTarget()
                }
                else -> Unit
            }
        }
        false
    }

    /** Drops stale drag state left by a cancelled drag (visuals, highlight, deferred rebuild). */
    private fun abortStaleDrag() {
        val vh = dragHolder
        dragHolder = null; draggedRow = null
        vh?.itemView?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
        clearDropTarget()
        if (dragPendingRebuild) { dragPendingRebuild = false; rebuild() }
    }

    /**
     * v2.1.10 (A9), Chrome-style: dropping a tab onto another tab's card groups the two immediately
     * (no more 600 ms hold). The target's group wins (the dragged tab joins it); otherwise the
     * dragged tab's group is reused; only then is a new group created (same default name/colour as
     * the group dialog). Pinned tabs never group (checked at the highlight already) and same-group
     * drops fall through to a plain reorder.
     */
    private fun groupByDrag(draggedId: String, targetId: String) {
        val sid = sessionId ?: return
        val dragged = core.tabs.get(draggedId) ?: return
        val target = core.tabs.get(targetId) ?: return
        if (dragged.id == target.id) return
        val gid = target.groupId ?: dragged.groupId
            ?: core.tabs.createGroup(sid, getString(R.string.group_default_name), 0xFF2962FF.toInt()).id
        core.tabs.setTabsGroup(listOf(draggedId, targetId), gid)
        rebuild()
        browser?.snack(getString(R.string.group_created))
    }

    /**
     * Inserts the card [draggedId] so it lands before row [beforeIndex] (original list coordinates;
     * rows.size = append). Section rules of the old live-reorder are preserved: a card may never
     * enter the pinned section it does not belong to, and never above the first header. Committed
     * with TabManager.applyOrder through commitOrder(); returns false when nothing moved.
     */
    private fun moveRowTo(draggedId: String, beforeIndex: Int): Boolean {
        val from = adapter.rows.indexOfFirst { it is Row.Card && it.tab.id == draggedId }
        if (from == -1) return false
        val to = beforeIndex.coerceIn(0, adapter.rows.size)
        val card = adapter.rows[from] as Row.Card
        val copy = adapter.rows.toMutableList()
        copy.removeAt(from)
        val adj = (if (to > from) to - 1 else to).coerceIn(0, copy.size)
        if (adj == from) return false                     // would land exactly where it already is
        val targetSection = sectionAt(copy, adj)
        if ((targetSection == Section.PINNED) != card.tab.pinned) return false   // pinned stays pinned, normal stays normal
        if (adj == 0 && copy.isNotEmpty() && copy[0] is Row.Header) return false  // nothing above the first header
        copy.add(adj, card)
        adapter.submit(copy)
        commitOrder()
        return true
    }

    /** No grouping target under the finger: insert at the nearest laid-out card, on its nearer edge (gap/below drop). */
    private fun dropCardReorder(draggedId: String, x: Float, y: Float, exclude: View) {
        var best: View? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until recycler.childCount) {
            val c = recycler.getChildAt(i) ?: continue
            if (c === exclude) continue
            if (recycler.getChildViewHolder(c) !is CardVH) continue
            val p = recycler.getChildViewHolder(c).bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || p !in adapter.rows.indices || !(adapter.rows[p] is Row.Card)) continue
            val dx = maxOf(c.left - x, x - c.right, 0f)
            val dy = maxOf(c.top - y, y - c.bottom, 0f)
            val d2 = dx * dx + dy * dy
            if (d2 < bestDist) { bestDist = d2; best = c }
        }
        val target = best ?: return
        val pos = recycler.getChildViewHolder(target).bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val after = x > (target.left + target.right) / 2f || y > (target.top + target.bottom) / 2f
        moveRowTo(draggedId, if (after) pos + 1 else pos)
    }

    /** Dropping a card on a group header: land right below the header = first member of that group. */
    private fun dropCardOnHeader(draggedId: String, header: Row.Header) {
        val hIdx = adapter.rows.indexOf(header)
        if (hIdx == -1) return
        moveRowTo(draggedId, hIdx + 1)
    }

    /**
     * v2.1.10: a GROUP header drags its block (header + its visible cards) to a new spot among the
     * groups - before the nearest group header whose upper half the finger is in, or right after
     * that header's block below its center. The move is clamped to the GROUP region (never above
     * the first group header, never below the OTHER header) and a drop on its own span is a no-op.
     * Only the model order changes: TabManager.applyGroupOrder notifies, rebuild() re-renders.
     */
    private fun dropHeader(fromPos: Int, x: Float, y: Float) {
        val sid = sessionId ?: return
        val header = adapter.rows.getOrNull(fromPos) as? Row.Header ?: return
        if (header.section != Section.GROUP) return
        var best: RecyclerView.ViewHolder? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until recycler.childCount) {
            val c = recycler.getChildAt(i) ?: continue
            val h = recycler.getChildViewHolder(c) as? HeaderVH ?: continue
            val p = h.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || p == fromPos) continue
            val row = adapter.rows.getOrNull(p) as? Row.Header ?: continue
            if (row.section != Section.GROUP) continue
            val dx = maxOf(c.left - x, x - c.right, 0f)
            val dy = maxOf(c.top - y, y - c.bottom, 0f)
            val d2 = dx * dx + dy * dy
            if (d2 < bestDist) { bestDist = d2; best = h }
        }
        val snap = best ?: return
        val snapPos = snap.bindingAdapterPosition
        var snapEnd = snapPos + 1
        while (snapEnd < adapter.rows.size && adapter.rows[snapEnd] is Row.Card) snapEnd++
        val to = if (y < (snap.itemView.top + snap.itemView.bottom) / 2f) snapPos else snapEnd
        var selfEnd = fromPos + 1
        while (selfEnd < adapter.rows.size && adapter.rows[selfEnd] is Row.Card) selfEnd++
        val firstGroup = adapter.rows.indexOfFirst { (it as? Row.Header)?.section == Section.GROUP }
        val otherIdx = adapter.rows.indexOfFirst { (it as? Row.Header)?.section == Section.OTHER }
        val maxTo = if (otherIdx >= 0) otherIdx else adapter.rows.size
        val clamped = to.coerceIn(firstGroup.coerceAtLeast(0), maxTo)
        if (clamped in fromPos..selfEnd) return          // own span (before me / after my block) = no-op
        val block = adapter.rows.subList(fromPos, selfEnd).toList()
        val copy = adapter.rows.toMutableList()
        copy.subList(fromPos, selfEnd).clear()
        val adj = (if (clamped > fromPos) clamped - block.size else clamped).coerceIn(0, copy.size)
        copy.addAll(adj, block)
        val ids = copy.mapNotNull { r -> (r as? Row.Header)?.takeIf { it.section == Section.GROUP }?.group?.id }
        core.tabs.applyGroupOrder(sid, ids)
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (selectionMode) return 0
            if (vh is HeaderVH) {
                val row = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Header ?: return 0
                // v2.1.10: only GROUP headers drag (they carry their block); PINNED/OTHER are fixed anchors.
                return if (row.section == Section.GROUP) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
            }
            if (vh !is CardVH) return 0
            val card = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Card
            // v2.1.7 (issue O): swipe-to-close, but never for a pinned tab - those must not vanish by accident.
            val swipe = if (card?.tab?.pinned == true) 0 else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, swipe)
        }
        override fun isLongPressDragEnabled() = !selectionMode
        override fun isItemViewSwipeEnabled() = !selectionMode
        // v2.1.10 (Chrome-style): the grid never reflows mid-drag - the dragged view just follows the
        // finger, and clearView decides group vs reorder from where the finger was released.
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val card = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Card
            clearDropTarget(); dragHolder = null; draggedRow = null
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
            val active = dragHolder ?: return   // swipe recovery / idle clear: no drop to dispatch
            if (vh !== active) {
                // Another row's recovery while our drag runs (or the dragged holder was recreated):
                // always un-stick the holder we styled; only proceed for the dragged row itself.
                active.itemView.alpha = 1f; active.itemView.scaleX = 1f; active.itemView.scaleY = 1f
                val rp = vh.bindingAdapterPosition
                val rrow = if (rp == RecyclerView.NO_POSITION) null else adapter.rows.getOrNull(rp)
                if (rrow?.key != draggedRow?.key) return
            }
            // Dispatch from the MODEL captured at drag start, never from bindingAdapterPosition:
            // it reads NO_POSITION in exactly the cancelled-drag case this path must survive.
            val row = draggedRow
            val targetId = groupTargetId
            val targetHeader = headerTarget
            val fx = fingerX
            val fy = fingerY
            clearDropTarget(); dragHolder = null; draggedRow = null
            // The dragged view keeps its layout slot (only the draw is offset), so a finger still
            // inside its original bounds = released in place (long-press without moving): no drop,
            // no neighbour shift.
            val own = vh.itemView
            val inPlace = fx >= own.left && fx <= own.right && fy >= own.top && fy <= own.bottom
            when {
                row is Row.Card && targetId != null && targetId != row.tab.id -> groupByDrag(row.tab.id, targetId)
                row is Row.Card && targetHeader != null -> dropCardOnHeader(row.tab.id, targetHeader)
                row is Row.Card && !inPlace -> dropCardReorder(row.tab.id, fx, fy, own)
                row is Row.Header && !inPlace -> dropHeader(adapter.rows.indexOf(row), fx, fy)
            }
            if (dragPendingRebuild) { dragPendingRebuild = false; rebuild() }
        }
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragHolder = vh
                draggedRow = vh?.let { adapter.rows.getOrNull(it.bindingAdapterPosition) }
                clearDropTarget()
                vh?.itemView?.apply { alpha = 0.9f; scaleX = 1.04f; scaleY = 1.04f }
            } else if (vh == null) {
                // Runs right before clearView: a visual net only (state must survive for dispatch).
                dragHolder?.itemView?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
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
        // v2.1.10 (A9): a second finger tapping "Select" mid-drag would notifyDataSetChanged
        // and cancel the drag (see onTabUpdated); the drag wins.
        if (dragHolder != null) return
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

        init { setHasStableIds(true) }

        fun submit(list: List<Row>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
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
    }
}
