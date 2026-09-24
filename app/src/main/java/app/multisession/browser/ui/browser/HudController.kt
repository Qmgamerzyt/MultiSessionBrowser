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
 * Compact HUD: a floating pill (40dp high) with the HUD controls, shown over the bottom-right
 * corner of the page. It never covers more than one row of icons and can be dragged vertically.
 *
 * While the toolbar is hidden ("fullscreen" item), the pill is replaced by a small floating
 * close-fullscreen button ([nub]): tapping it exits fullscreen again (the toolbar/menu are hidden
 * there), and the button disappears together with fullscreen mode. The pill's own hide control is
 * session-only, because the menu no longer offers a "Show HUD" toggle - hiding must never be able
 * to lock the HUD away for good.
 */
class HudController(private val bar: LinearLayout, private val nub: View, private val actions: Actions) {

    interface Actions {
        fun hudBack(); fun hudForward(); fun hudReload()
        fun hudScrollTop(); fun hudScrollBottom(); fun hudDesktop(); fun hudToggleToolbar()
        fun hudNewTab(); fun hudCloseTab(); fun hudFind()
    }

    class Item(val key: String, val labelRes: Int, val iconRes: Int, val run: (Actions) -> Unit)

    val allItems = listOf(
        Item("back", R.string.hud_item_back, R.drawable.ic_arrow_back) { it.hudBack() },
        Item("forward", R.string.hud_item_forward, R.drawable.ic_arrow_forward) { it.hudForward() },
        Item("reload", R.string.hud_item_reload, R.drawable.ic_refresh) { it.hudReload() },
        Item("top", R.string.hud_item_top, R.drawable.ic_align_top) { it.hudScrollTop() },
        Item("bottom", R.string.hud_item_bottom, R.drawable.ic_align_bottom) { it.hudScrollBottom() },
        Item("desktop", R.string.hud_item_desktop, R.drawable.ic_desktop) { it.hudDesktop() },
        Item("fullscreen", R.string.hud_item_fullscreen, R.drawable.ic_fullscreen) { it.hudToggleToolbar() },
        Item("newtab", R.string.hud_item_newtab, R.drawable.ic_add) { it.hudNewTab() },
        Item("closetab", R.string.hud_item_closetab, R.drawable.ic_close) { it.hudCloseTab() },
        Item("find", R.string.hud_item_find, R.drawable.ic_search) { it.hudFind() },
    )

    /** The customizable subset of [allItems] (toolbar/menu duplicates excluded - bug fix). */
    private val customItems = allItems.filter { it.key in Prefs.DEFAULT_HUD_KEYS }

    init {
        rebuild()
        // Always start visible: the menu's "Show HUD" toggle is gone, so a stored `false` from an
        // older version must not be able to lock the pill away permanently.
        bar.isVisible = true
        nub.isVisible = false
        makeDraggable()
        makeNubDraggable()
    }

    fun setVisible(visible: Boolean) {
        Prefs.hudVisible = visible
        bar.isVisible = visible
    }

    val isVisible: Boolean get() = bar.isVisible

    /** Called by BrowserActivity.setToolbarHidden: in fullscreen the floating close button
     *  replaces the pill; leaving fullscreen brings the pill back. */
    fun onFullscreenChanged(hidden: Boolean) {
        nub.isVisible = hidden
        rebuild()
        bar.isVisible = !hidden
    }

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
        // A hide control is always present so the HUD can never get stuck on screen. It only hides
        // for this session (not persisted): with the menu toggle gone, a persisted hide would have
        // no way back - the pill returns on the next launch or after leaving fullscreen.
        bar.addView(ImageButton(bar.context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(0xFFBBBBBB.toInt()); background = null
            contentDescription = bar.context.getString(R.string.hud_item_hide)
            setOnClickListener { bar.isVisible = false }
        })
    }

    /** Checkbox dialog to choose + order the HUD items (order = order of the list).
     *  Only the de-duplicated [customItems] are offered - toolbar/menu actions are not listed. */
    fun customize() {
        val current = Prefs.hudItems
        val labels = customItems.map { bar.context.getString(it.labelRes) }.toTypedArray()
        val checked = BooleanArray(customItems.size) { current.contains(customItems[it].key) }
        MaterialAlertDialogBuilder(bar.context)
            .setTitle(R.string.hud_title)
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(R.string.save) { _, _ ->
                val chosen = customItems.filterIndexed { i, _ -> checked[i] }.map { it.key }
                Prefs.hudItems = chosen.ifEmpty { Prefs.DEFAULT_HUD_KEYS }
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

    /** Floating close-fullscreen button: freely draggable in both axes (clamped inside the
     *  container); a tap without dragging exits fullscreen via [Actions.hudToggleToolbar]. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeNubDraggable() {
        var sx = 0f; var sy = 0f; var tx = 0f; var ty = 0f; var moved = false
        nub.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; tx = v.translationX; ty = v.translationY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx; val dy = ev.rawY - sy
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    val p = v.parent as? ViewGroup ?: return@setOnTouchListener true
                    val minTx = (8 - v.left).toFloat()
                    val maxTx = (p.width - 8 - v.right).toFloat()
                    val minTy = (8 - v.top).toFloat()
                    val maxTy = (p.height - 8 - v.bottom).toFloat()
                    v.translationX = (tx + dx).coerceIn(minTx, maxOf(minTx, maxTx))
                    v.translationY = (ty + dy).coerceIn(minTy, maxOf(minTy, maxTy))
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) actions.hudToggleToolbar(); true }
                else -> moved
            }
        }
    }
}
