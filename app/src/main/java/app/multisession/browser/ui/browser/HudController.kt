package app.multisession.browser.ui.browser

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.isVisible
import app.multisession.browser.R
import app.multisession.browser.core.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Compact, selectable HUD: a floating pill (40dp high) with the controls the user picked, shown over the bottom-right
 * corner of the page. It never covers more than one row of icons, can be dragged vertically, and keeps working when
 * the toolbar is hidden ("fullscreen" item) - the case where sites hide behind the normal chrome.
 */
class HudController(private val bar: LinearLayout, private val actions: Actions) {

    interface Actions {
        fun hudBack(); fun hudForward(); fun hudUndo(); fun hudRedo(); fun hudReload()
        fun hudScrollTop(); fun hudScrollBottom(); fun hudDesktop(); fun hudToggleToolbar()
        fun hudNewTab(); fun hudCloseTab(); fun hudFind()
    }

    class Item(val key: String, val labelRes: Int, val iconRes: Int, val run: (Actions) -> Unit)

    val allItems = listOf(
        Item("back", R.string.hud_item_back, R.drawable.ic_arrow_back) { it.hudBack() },
        Item("forward", R.string.hud_item_forward, R.drawable.ic_arrow_forward) { it.hudForward() },
        Item("undo", R.string.hud_item_undo, R.drawable.ic_undo) { it.hudUndo() },
        Item("redo", R.string.hud_item_redo, R.drawable.ic_redo) { it.hudRedo() },
        Item("reload", R.string.hud_item_reload, R.drawable.ic_refresh) { it.hudReload() },
        Item("top", R.string.hud_item_top, R.drawable.ic_align_top) { it.hudScrollTop() },
        Item("bottom", R.string.hud_item_bottom, R.drawable.ic_align_bottom) { it.hudScrollBottom() },
        Item("desktop", R.string.hud_item_desktop, R.drawable.ic_desktop) { it.hudDesktop() },
        Item("fullscreen", R.string.hud_item_fullscreen, R.drawable.ic_fullscreen) { it.hudToggleToolbar() },
        Item("newtab", R.string.hud_item_newtab, R.drawable.ic_add) { it.hudNewTab() },
        Item("closetab", R.string.hud_item_closetab, R.drawable.ic_close) { it.hudCloseTab() },
        Item("find", R.string.hud_item_find, R.drawable.ic_search) { it.hudFind() },
    )

    init {
        rebuild()
        bar.isVisible = Prefs.hudVisible
        makeDraggable()
    }

    fun setVisible(visible: Boolean) {
        Prefs.hudVisible = visible
        bar.isVisible = visible
    }

    val isVisible: Boolean get() = bar.isVisible

    fun rebuild() {
        bar.removeAllViews()
        val keys = Prefs.hudItems
        val size = (36 * bar.resources.displayMetrics.density).toInt()
        keys.mapNotNull { k -> allItems.firstOrNull { it.key == k } }.forEach { item ->
            val b = ImageButton(bar.context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageResource(item.iconRes)
                setColorFilter(0xFFFFFFFF.toInt())
                background = null
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(6, 6, 6, 6)
                contentDescription = bar.context.getString(item.labelRes)
                setOnClickListener { item.run(actions) }
                setOnLongClickListener { android.widget.Toast.makeText(bar.context, item.labelRes, android.widget.Toast.LENGTH_SHORT).show(); true }
            }
            bar.addView(b)
        }
        // A hide control is always present so the HUD can never get stuck on screen.
        bar.addView(ImageButton(bar.context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(0xFFBBBBBB.toInt()); background = null
            contentDescription = bar.context.getString(R.string.hud_item_hide)
            setOnClickListener { setVisible(false) }
        })
    }

    /** Checkbox dialog to choose + order the HUD items (order = order of the list). */
    fun customize() {
        val current = Prefs.hudItems
        val labels = allItems.map { bar.context.getString(it.labelRes) }.toTypedArray()
        val checked = BooleanArray(allItems.size) { current.contains(allItems[it].key) }
        MaterialAlertDialogBuilder(bar.context)
            .setTitle(R.string.hud_title)
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(R.string.save) { _, _ ->
                val chosen = allItems.filterIndexed { i, _ -> checked[i] }.map { it.key }
                Prefs.hudItems = chosen.ifEmpty { listOf("back", "forward") }
                rebuild()
                setVisible(true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable() {
        var startY = 0f; var startMargin = 0; var moved = false
        bar.setOnTouchListener { v, ev ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = ev.rawY; startMargin = lp.bottomMargin; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - ev.rawY
                    if (kotlin.math.abs(dy) > 8) moved = true
                    val parentH = (v.parent as? ViewGroup)?.height ?: return@setOnTouchListener true
                    lp.bottomMargin = (startMargin + dy.toInt()).coerceIn(8, (parentH - v.height - 8).coerceAtLeast(8))
                    v.layoutParams = lp; true
                }
                else -> moved
            }
        }
    }
}
