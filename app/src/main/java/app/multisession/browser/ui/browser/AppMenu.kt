package app.multisession.browser.ui.browser

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.extensions.ExtensionAction
import com.google.android.material.materialswitch.MaterialSwitch

/** One row of the app-owned right-side menu. */
class MenuEntry(
    val iconRes: Int,
    val title: String,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val badge: String? = null,
    val onClick: () -> Unit,
)

/**
 * The browser's own menu (right drawer): replaces the Android PopupMenu. Entries are rebuilt every time the drawer
 * opens, so state (bookmark, desktop mode, HUD, download count, extension buttons) is always current.
 */
class AppMenu(private val root: View, private val core: BrowserCore) {

    private val title: TextView = root.findViewById(R.id.menuTitle)
    private val subtitle: TextView = root.findViewById(R.id.menuSubtitle)
    private val extScroll: View = root.findViewById(R.id.extActionsScroll)
    private val extActions: LinearLayout = root.findViewById(R.id.extActions)
    private val recycler: RecyclerView = root.findViewById(R.id.menuRecycler)
    private val adapter = Adapter()

    init {
        recycler.layoutManager = LinearLayoutManager(root.context)
        recycler.adapter = adapter
    }

    fun render(titleText: String, subtitleText: String, entries: List<MenuEntry>, actions: Collection<ExtensionAction>, onAction: (ExtensionAction) -> Unit) {
        title.text = titleText
        subtitle.text = subtitleText
        subtitle.isVisible = subtitleText.isNotBlank()
        adapter.submit(entries)
        extActions.removeAllViews()
        extScroll.isVisible = actions.isNotEmpty()
        val inflater = LayoutInflater.from(root.context)
        actions.forEach { a ->
            val v = inflater.inflate(R.layout.item_ext_action, extActions, false)
            val icon = v.findViewById<ImageView>(R.id.extIcon)
            val badge = v.findViewById<TextView>(R.id.extBadge)
            val text = a.action.badgeText
            badge.isVisible = !text.isNullOrBlank()
            badge.text = text ?: ""
            v.contentDescription = a.action.title ?: a.extension.metaData.name
            v.alpha = if (a.action.enabled == false) 0.4f else 1f
            v.setOnClickListener { onAction(a) }
            v.setOnLongClickListener { android.widget.Toast.makeText(root.context, v.contentDescription, android.widget.Toast.LENGTH_SHORT).show(); true }
            try {
                val size = (24 * root.resources.displayMetrics.density).toInt()
                a.action.icon?.getBitmap(size)?.accept({ bmp: Bitmap? -> if (bmp != null) icon.setImageBitmap(bmp) }, { AppLog.d("AppMenu", "icon failed: ${it?.message}") })
            } catch (t: Throwable) {
                AppLog.d("AppMenu", "icon not available")
            }
            extActions.addView(v)
        }
    }

    private class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<MenuEntry>()
        fun submit(list: List<MenuEntry>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_menu_entry, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val icon: ImageView = v.findViewById(R.id.entryIcon)
            private val title: TextView = v.findViewById(R.id.entryTitle)
            private val badge: TextView = v.findViewById(R.id.entryBadge)
            private val switch: MaterialSwitch = v.findViewById(R.id.entrySwitch)
            fun bind(e: MenuEntry) {
                icon.setImageResource(e.iconRes)
                title.text = e.title
                badge.isVisible = e.badge != null
                badge.text = e.badge ?: ""
                switch.isVisible = e.checked != null
                switch.isChecked = e.checked == true
                itemView.alpha = if (e.enabled) 1f else 0.4f
                itemView.isEnabled = e.enabled
                itemView.setOnClickListener { if (e.enabled) e.onClick() }
            }
        }
    }
}
