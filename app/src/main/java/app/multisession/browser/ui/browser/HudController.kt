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

/**
 * Compact HUD: a floating pill (40dp high) with the HUD controls, shown over the bottom-right
 * corner of the page. It never covers more than one row of icons and can be dragged vertically.
 *
 * The class keeps no chrome-visibility logic of its own: `BrowserActivity.applyChrome()` derives
 * whether the pill or the close-fullscreen button ([nub]) shows from the toolbar-hidden and HTML5
 * fullscreen flags and pushes it here through [onChromeChanged]. A pill the user hid this session
 * stays hidden ([pillHidden]) - it is never force-shown again by leaving fullscreen, and it is never
 * persisted, so the next launch always starts with the pill visible.
 */
class HudController(private val bar: LinearLayout, private val nub: View, private val actions: Actions) {

    interface Actions {
        fun hudBack(); fun hudForward(); fun hudReload()
        fun hudScrollTop(); fun hudScrollBottom(); fun hudDesktop(); fun hudToggleToolbar()
        fun hudNewTab(); fun hudCloseTab(); fun hudFind()
        /** Leave every fullscreen flavour at once (toolbar-hidden and HTML5 fullscreen). */
        fun hudExitFullscreen()
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

    /** Session-only hide chosen by the user with the pill's own control (never persisted: with the
     *  menu toggle gone, a persisted hide would have no way back). */
    private var pillHidden = false

    init {
        rebuild()
        // Always start visible: nothing is persisted, so the pill can never be locked away.
        pillHidden = false
        bar.isVisible = true
        nub.isVisible = false
        makeDraggable()
        makeNubDraggable()
    }

    /**
     * Called from `BrowserActivity.applyChrome()` whenever the chrome-hidden state changes: the
     * floating close-fullscreen button replaces the pill while the toolbar is hidden or a page is
     * in HTML5 fullscreen, and both go away together. A pill hidden this session stays hidden.
     */
    fun onChromeChanged(hidden: Boolean) {
        nub.isVisible = hidden
        bar.isVisible = !hidden && !pillHidden
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
        // no way back - the pill returns on the next launch, but NOT merely by leaving fullscreen.
        bar.addView(ImageButton(bar.context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(0xFFBBBBBB.toInt()); background = null
            contentDescription = bar.context.getString(R.string.hud_item_hide)
            setOnClickListener { pillHidden = true; bar.isVisible = false }
        })
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
     *  container); a tap without dragging exits fullscreen via [Actions.hudExitFullscreen]. */
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
                MotionEvent.ACTION_UP -> { if (!moved) actions.hudExitFullscreen(); true }
                else -> moved
            }
        }
    }
}
