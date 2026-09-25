package app.multisession.browser.ui.browser

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible

/**
 * The single control that leaves fullscreen (HTML5 video fullscreen AND the app's hidden-toolbar
 * mode). It is the only UI way out of fullscreen since the bottom HUD was removed in v2.1.7:
 * Back keeps its normal meaning (drawers -> URL focus -> page back -> background the app).
 *
 * A small circular button, bottom-right by default, shown only while a fullscreen flavour is
 * active ([setVisible]). It is freely draggable in both axes (clamped inside the container) so it
 * can be moved off a video's own controls; the position lives only for the current Activity
 * instance and is never persisted. A tap without dragging calls [onExit].
 */
class FullscreenExitButton(private val button: View, private val onExit: () -> Unit) {

    init {
        button.isVisible = false
        makeDraggable()
    }

    /** Derived from BrowserActivity.applyChrome(); only touches the view when the value changes. */
    fun setVisible(visible: Boolean) {
        if (button.isVisible != visible) button.isVisible = visible
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable() {
        var sx = 0f; var sy = 0f; var tx = 0f; var ty = 0f; var moved = false
        button.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; tx = v.translationX; ty = v.translationY; moved = false; true
                }
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
                MotionEvent.ACTION_UP -> { if (!moved) onExit(); true }
                else -> moved
            }
        }
    }
}
