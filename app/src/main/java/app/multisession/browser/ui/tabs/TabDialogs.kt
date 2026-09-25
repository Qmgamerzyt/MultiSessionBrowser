package app.multisession.browser.ui.tabs

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.multisession.browser.R
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.WorkspaceEntity
import app.multisession.browser.tabs.Tab
import app.multisession.browser.ui.browser.BrowserActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Two-line list row (icon, title, subtitle) for the dialogs below. */
private class RowItem(val iconRes: Int, val title: String, val subtitle: String)

private class RowAdapter(ctx: Context, items: List<RowItem>) : ArrayAdapter<RowItem>(ctx, 0, items) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_simple_row, parent, false)
        val item = getItem(position)!!
        v.findViewById<ImageView>(R.id.rowIcon).setImageResource(item.iconRes)
        v.findViewById<TextView>(R.id.rowTitle).text = item.title
        v.findViewById<TextView>(R.id.rowSubtitle).text = item.subtitle
        return v
    }
}

/** Recently closed / archived / workspaces dialogs of the tab grid (Part 1). Compact by design: plain lists, no extra screens. */
object TabDialogs {

    /** Closed groups first (restorable as a whole), then closed tabs, newest first. */
    fun recentlyClosed(activity: BrowserActivity, core: BrowserCore, sessionId: String, onRestored: (Tab?) -> Unit) {
        val groups = core.tabs.recentlyClosedGroups(sessionId)
        val tabs = core.tabs.recentlyClosedTabs(sessionId)
        if (groups.isEmpty() && tabs.isEmpty()) { activity.snack(activity.getString(R.string.recently_closed_empty)); return }
        val items = groups.map { g ->
            val n = tabs.count { it.closedGroupId == g.id }
            RowItem(R.drawable.ic_label, activity.getString(R.string.closed_group_fmt, g.name), activity.resources.getString(if (n == 1) R.string.tab_count_fmt else R.string.tabs_count_fmt, n) + " · " + ago(g.closedAt))
        } + tabs.map { t ->
            RowItem(R.drawable.ic_history, t.title.ifBlank { UrlUtils.displayHost(t.url).ifBlank { t.url } }, UrlUtils.displayHost(t.url).ifBlank { t.url } + " · " + ago(t.closedAt))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.recently_closed)
            .setAdapter(RowAdapter(activity, items)) { _, which ->
                if (which < groups.size) {
                    val g = core.tabs.restoreClosedGroup(groups[which].id)
                    onRestored(g?.let { core.tabs.tabsInGroup(it.id).firstOrNull() })
                } else {
                    onRestored(core.tabs.reopenClosedTab(sessionId, tabs[which - groups.size].id))
                }
            }
            // v2.1.7 (issue I): "Clear list" is final - the tabs are gone afterwards - so it gets the
            // same confirmation every other destructive action has instead of firing on one tap.
            .setNeutralButton(R.string.clear_list) { _, _ ->
                MaterialAlertDialogBuilder(activity).setTitle(R.string.clear_list).setMessage(R.string.clear_list_confirm)
                    .setPositiveButton(R.string.clear) { _, _ -> core.tabs.clearRecentlyClosed(sessionId) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Multi-choice list of archived tabs: Restore (selected, or all when nothing is ticked) / Delete selected. */
    fun archived(activity: BrowserActivity, core: BrowserCore, sessionId: String, onRestored: (List<Tab>) -> Unit) {
        val archived = core.tabs.archivedTabsFor(sessionId)
        if (archived.isEmpty()) { activity.snack(activity.getString(R.string.archived_empty)); return }
        val labels = archived.map { t ->
            val host = UrlUtils.displayHost(t.url).ifBlank { t.url }
            val title = t.displayTitle(activity)
            (if (title == host) host else "$title\n$host") + (t.archivedAt?.let { " · " + ago(it) } ?: "")
        }.toTypedArray()
        val checked = BooleanArray(archived.size)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.archived_tabs) + " (${archived.size})")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(R.string.restore) { _, _ ->
                val ids = archived.filterIndexed { i, _ -> checked[i] }.ifEmpty { archived }.map { it.id }
                val restored = core.tabs.unarchiveTabs(ids)
                activity.snack(activity.getString(R.string.tabs_restored_fmt, restored.size))
                onRestored(restored)
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                val ids = archived.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isEmpty()) { activity.snack(activity.getString(R.string.nothing_selected)); return@setNeutralButton }
                MaterialAlertDialogBuilder(activity).setTitle(activity.getString(R.string.close_tabs_fmt, ids.size)).setMessage(R.string.confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> core.tabs.deleteArchived(ids) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Workspaces of the session: tap one for actions; "Save current…" stores the session's current tabs + groups. */
    fun workspaces(activity: BrowserActivity, core: BrowserCore, sessionId: String, onOpened: (Tab?) -> Unit) {
        activity.lifecycleScope.launch {
            val list = core.workspaces.forSession(sessionId)
            val counts = list.associate { it.id to core.workspaces.itemCount(it.id) }
            val items = list.map { w ->
                val n = counts[w.id] ?: 0
                RowItem(R.drawable.ic_workspace, w.name, activity.resources.getString(if (n == 1) R.string.tab_count_fmt else R.string.tabs_count_fmt, n) + " · " + ago(w.updatedAt))
            }
            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.workspaces)
                .setPositiveButton(R.string.workspace_new) { _, _ -> askName(activity, null) { name ->
                    activity.lifecycleScope.launch { core.workspaces.saveCurrent(sessionId, name); activity.snack(activity.getString(R.string.workspace_saved, name)) }
                } }
                .setNegativeButton(android.R.string.cancel, null)
            if (items.isEmpty()) builder.setMessage(R.string.workspaces_empty)
            else builder.setAdapter(RowAdapter(activity, items)) { _, which -> workspaceActions(activity, core, list[which], onOpened) }
            builder.show()
        }
    }

    private fun workspaceActions(activity: BrowserActivity, core: BrowserCore, ws: WorkspaceEntity, onOpened: (Tab?) -> Unit) {
        val actions = arrayOf(
            activity.getString(R.string.workspace_open), activity.getString(R.string.workspace_open_replace), activity.getString(R.string.workspace_update),
            activity.getString(R.string.rename), activity.getString(R.string.duplicate), activity.getString(R.string.delete),
        )
        MaterialAlertDialogBuilder(activity).setTitle(ws.name).setItems(actions) { _, which ->
            when (which) {
                0 -> activity.lifecycleScope.launch { onOpened(core.workspaces.restore(ws.id, replaceCurrent = false)); activity.snack(activity.getString(R.string.workspace_restored_fmt, ws.name)) }
                1 -> MaterialAlertDialogBuilder(activity).setTitle(R.string.workspace_open_replace).setMessage(R.string.workspace_open_replace_confirm)
                    .setPositiveButton(R.string.workspace_open_replace) { _, _ -> activity.lifecycleScope.launch { onOpened(core.workspaces.restore(ws.id, replaceCurrent = true)) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
                2 -> MaterialAlertDialogBuilder(activity).setTitle(R.string.workspace_update).setMessage(R.string.workspace_update_confirm)
                    .setPositiveButton(R.string.save) { _, _ -> activity.lifecycleScope.launch { core.workspaces.update(ws.id); activity.snack(activity.getString(R.string.workspace_update_done)) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
                3 -> askName(activity, ws.name) { name -> activity.lifecycleScope.launch { core.workspaces.rename(ws.id, name) } }
                4 -> activity.lifecycleScope.launch { core.workspaces.duplicate(ws.id); activity.snack(activity.getString(R.string.workspace_duplicated)) }
                5 -> MaterialAlertDialogBuilder(activity).setTitle(R.string.delete).setMessage(activity.getString(R.string.workspace_delete_confirm, ws.name))
                    .setPositiveButton(R.string.delete) { _, _ -> activity.lifecycleScope.launch { core.workspaces.delete(ws.id) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askName(activity: BrowserActivity, current: String?, onDone: (String) -> Unit) {
        val input = EditText(activity).apply { hint = activity.getString(R.string.workspace_name_hint); setSingleLine(); setText(current ?: "") }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (current == null) R.string.workspace_new else R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) onDone(n) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun ago(time: Long): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
