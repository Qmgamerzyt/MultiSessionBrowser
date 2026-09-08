package app.multisession.browser.ui.sessions

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.session.SessionManager
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Session manager: switch, create (normal / private), rename, duplicate, reset data, delete. */
class SessionsSheet : BottomSheetDialogFragment() {

    private val core get() = BrowserApp.core()
    private val browser get() = activity as? BrowserActivity
    private lateinit var adapter: Adapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_sessions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.isolationStatus).text = core.isolation.describe(requireContext())
        adapter = Adapter()
        view.findViewById<RecyclerView>(R.id.sessionsRecycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SessionsSheet.adapter
        }
        view.findViewById<View>(R.id.newSessionButton).setOnClickListener {
            SessionEditDialog.show(requireContext(), null) { name, color, isPrivate -> create(name, color, isPrivate) }
        }
        view.findViewById<View>(R.id.newPrivateButton).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.private_session)
                .setMessage(R.string.private_session_explainer)
                .setPositiveButton(R.string.create) { _, _ -> create(getString(R.string.private_session_name), SessionManager.PALETTE[4], true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refresh()
    }

    private fun refresh() {
        adapter.submit(core.sessions.sessions.value, core.sessions.activeId)
    }

    private fun create(name: String, color: Int, isPrivate: Boolean) {
        lifecycleScope.launch {
            val s = core.sessions.create(name, color, isPrivate)
            browser?.switchSession(s.id)
            dismiss()
        }
    }

    private fun showItemMenu(anchor: View, session: SessionEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_session_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> SessionEditDialog.show(requireContext(), session) { name, color, _ ->
                    lifecycleScope.launch { core.sessions.update(session.id, name, color); refresh() }
                }
                R.id.action_duplicate -> lifecycleScope.launch {
                    core.sessions.duplicate(session.id)
                    refresh()
                    browser?.snack(getString(R.string.session_duplicated))
                }
                R.id.action_reset -> confirmReset(session)
                R.id.action_delete -> confirmDelete(session)
            }
            true
        }
        popup.show()
    }

    private fun confirmReset(session: SessionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.reset_session_title, session.name))
            .setMessage(if (core.isolation.isIsolated) R.string.reset_session_confirm else R.string.reset_session_confirm_shared)
            .setPositiveButton(R.string.clear) { _, _ ->
                lifecycleScope.launch {
                    core.sessions.clearData(session.id, cookies = true, storage = true, cache = true, history = true)
                    browser?.snack(getString(R.string.session_data_cleared))
                    if (session.id == core.sessions.activeId) browser?.switchSession(session.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(session: SessionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_session_title, session.name))
            .setMessage(R.string.delete_session_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val wasActive = session.id == core.sessions.activeId
                    core.sessions.delete(session.id)
                    if (wasActive) core.sessions.activeId?.let { browser?.switchSession(it) }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<SessionEntity>()
        private var activeId: String? = null

        fun submit(list: List<SessionEntity>, active: String?) {
            items.clear(); items.addAll(list); activeId = active; notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.name.text = s.name
            val tabs = core.tabs.countFor(s.id)
            val sub = resources.getQuantityString(R.plurals.tabs_count, tabs, tabs) +
                (if (s.isPrivate) " · " + getString(R.string.private_label) else "") +
                (if (s.desktopMode) " · " + getString(R.string.desktop_label) else "")
            holder.subtitle.text = sub
            holder.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
            holder.check.isVisible = s.id == activeId
            holder.itemView.setOnClickListener {
                browser?.switchSession(s.id)
                dismiss()
            }
            holder.more.setOnClickListener { showItemMenu(it, s) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.sessionDot)
            val name: TextView = v.findViewById(R.id.sessionName)
            val subtitle: TextView = v.findViewById(R.id.sessionSubtitle)
            val check: View = v.findViewById(R.id.sessionCheck)
            val more: View = v.findViewById(R.id.sessionMore)
        }
    }
}
