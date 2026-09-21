package app.multisession.browser.ui.sessions

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.R
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.session.SessionManager
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * App-owned left drawer: switch / create / rename / duplicate / reset / delete sessions, set the default session and
 * drag-reorder. The default session is pinned at index 0: it cannot be dragged and nothing can be dropped above it.
 */
class SessionsDrawer(private val activity: BrowserActivity, private val core: BrowserCore, root: View) {

    private val recycler: RecyclerView = root.findViewById(R.id.sessionsRecycler)
    private val adapter = Adapter()
    // Declared BEFORE init: Kotlin runs property initialisers and init blocks in declaration order, and init
    // attaches the helper. The callback only reads `adapter` inside its methods, so order is safe.
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val s = adapter.items.getOrNull(vh.bindingAdapterPosition) ?: return 0
            return if (s.isDefault) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }
        override fun isLongPressDragEnabled() = true
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val f = from.bindingAdapterPosition; val t = to.bindingAdapterPosition
            if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
            if (adapter.items.getOrNull(t)?.isDefault == true) return false   // never above the default session
            adapter.move(f, t); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (adapter.dirty) {
                adapter.dirty = false
                val ids = adapter.items.map { it.id }
                activity.lifecycleScope.launch { core.sessions.reorder(ids); refresh() }
            }
        }
    })

    init {
        root.findViewById<TextView>(R.id.isolationStatus).text = core.isolation.describe(activity)
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        touchHelper.attachToRecyclerView(recycler)
        root.findViewById<View>(R.id.newSessionButton).setOnClickListener {
            SessionEditDialog.show(activity, null) { name, color, isPrivate -> create(name, color, isPrivate) }
        }
        root.findViewById<View>(R.id.newPrivateButton).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.private_session)
                .setMessage(R.string.private_session_explainer)
                .setPositiveButton(R.string.create) { _, _ -> create(activity.getString(R.string.private_session_name), SessionManager.PALETTE[4], true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun refresh() = adapter.submit(core.sessions.sessions.value, core.sessions.activeId)

    private fun create(name: String, color: Int, isPrivate: Boolean) {
        activity.lifecycleScope.launch {
            val s = core.sessions.create(name, color, isPrivate)
            activity.switchSession(s.id)
            activity.closeDrawers()
        }
    }

    private fun showItemMenu(anchor: View, session: SessionEntity) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_session_item, popup.menu)
        if (!session.isDefault && !session.isPrivate) popup.menu.add(0, ID_SET_DEFAULT, 0, R.string.set_default_session)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> SessionEditDialog.show(activity, session) { name, color, _ ->
                    activity.lifecycleScope.launch { core.sessions.update(session.id, name, color); refresh() }
                }
                R.id.action_duplicate -> activity.lifecycleScope.launch {
                    core.sessions.duplicate(session.id); refresh(); activity.snack(activity.getString(R.string.session_duplicated))
                }
                R.id.action_reset -> confirmReset(session)
                R.id.action_delete -> confirmDelete(session)
                ID_SET_DEFAULT -> activity.lifecycleScope.launch { core.sessions.setDefault(session.id); refresh() }
            }
            true
        }
        popup.show()
    }

    private fun confirmReset(session: SessionEntity) {
        val labels = arrayOf(activity.getString(R.string.clear_cookies), activity.getString(R.string.clear_storage), activity.getString(R.string.clear_cache), activity.getString(R.string.clear_history))
        val checked = booleanArrayOf(true, true, true, false)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.reset_session_title, session.name))
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(R.string.clear) { _, _ ->
                activity.lifecycleScope.launch {
                    core.sessions.clearData(session.id, checked[0], checked[1], checked[2], checked[3])
                    if (session.id == core.sessions.activeId) activity.refreshCurrentTab()
                    activity.snack(activity.getString(R.string.session_data_cleared))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(session: SessionEntity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_session_title)
            .setMessage(activity.getString(R.string.delete_session_confirm, session.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    val wasActive = session.id == core.sessions.activeId
                    core.sessions.delete(session.id)
                    refresh()
                    if (wasActive) activity.onActiveSessionReplaced()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        val items = mutableListOf<SessionEntity>()
        private var activeId: String? = null
        var dirty = false

        fun submit(list: List<SessionEntity>, active: String?) { items.clear(); items.addAll(list); activeId = active; notifyDataSetChanged() }
        fun move(from: Int, to: Int) { items.add(to, items.removeAt(from)); notifyItemMoved(from, to); dirty = true }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], items[position].id == activeId)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val drag: View = v.findViewById(R.id.sessionDrag)
            private val dot: View = v.findViewById(R.id.sessionDot)
            private val name: TextView = v.findViewById(R.id.sessionName)
            private val subtitle: TextView = v.findViewById(R.id.sessionSubtitle)
            private val check: View = v.findViewById(R.id.sessionCheck)
            private val more: View = v.findViewById(R.id.sessionMore)

            fun bind(s: SessionEntity, active: Boolean) {
                dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
                name.text = s.name
                val tabs = core.tabs.countFor(s.id)
                val parts = mutableListOf(activity.resources.getString(if (tabs == 1) R.string.tab_count_fmt else R.string.tabs_count_fmt, tabs))
                if (s.isDefault) parts += activity.getString(R.string.default_label)
                if (s.isPrivate) parts += activity.getString(R.string.private_label)
                subtitle.text = parts.joinToString(" · ")
                check.isVisible = active
                drag.isVisible = !s.isDefault
                itemView.setOnClickListener { activity.switchSession(s.id); activity.closeDrawers() }
                more.setOnClickListener { showItemMenu(it, s) }
                drag.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this); false }
            }
        }
    }

    private companion object {
        const val ID_SET_DEFAULT = 9001
    }
}
