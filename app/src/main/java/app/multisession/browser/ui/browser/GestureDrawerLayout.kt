package app.multisession.browser.ui.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/**
 * v2.1.9: [DrawerLayout] with explicit gesture ownership.
 *
 * DrawerLayout decides its own interception from the drag helpers, which are tuned for a plain
 * content view and happily treat a vertical drag as a possible drawer drag once the finger starts
 * anywhere near the edge. That makes the sidebar's open/close sensitive to ordinary vertical page
 * scrolling - and, in the other direction, a fast horizontal flick can steal a scroll that should
 * have stayed with the page.
 *
 * This class arbitrates the axis of a gesture ONCE, before it is allowed to do anything:
 *
 *  - the first [axisLockSlop] (2x touch slop) of movement decide the axis;
 *  - **VERTICAL** -> this view never intercepts at all, so vertical drags belong to the content
 *    (page scroll, list scroll inside a drawer, nested scroll containers) and the drawer stays put;
 *  - **HORIZONTAL / undecided** -> the event is passed straight to [DrawerLayout], so opening and
 *    closing the drawer by swiping keeps working exactly as it did.
 *
 * ACTION_UP and ACTION_CANCEL are always forwarded to DrawerLayout first, so its ViewDragHelper is
 * never left mid-drag, and the decision is reset for the next gesture.
 *
 * This is *declining to intercept*, not consuming: no second gesture recognizer, no synthetic
 * events, no change to taps, long-presses or scrolling. Nothing here can stop the drawer from being
 * swiped - it only stops a vertical drag from ever reaching DrawerLayout's interception logic.
 */
class GestureDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DrawerLayout(context, attrs, defStyleAttr) {

    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    /** Movement before the axis of a gesture is decided: deliberately above touch slop. */
    private val axisLockSlop = ViewConfiguration.get(context).scaledTouchSlop * 2

    /** How much more horizontal than vertical a gesture must be before it may move the drawer. */
    private val horizontalBias = 1.5f

    private var downX = 0f
    private var downY = 0f
    private var axis = Axis.UNDECIDED

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                axis = Axis.UNDECIDED
            }
            MotionEvent.ACTION_MOVE -> {
                if (axis == Axis.UNDECIDED) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    // The axis is only called HORIZONTAL when the horizontal travel clearly wins
                    // (1.5x the vertical travel, the mirror of the pull-to-refresh rule). A drag that
                    // leans even slightly vertical therefore never reaches DrawerLayout, which is what
                    // stopped a vertical swipe inside/over the sidebar from sliding the drawer shut.
                    if (dx > axisLockSlop || dy > axisLockSlop) {
                        axis = if (dx > dy * horizontalBias) Axis.HORIZONTAL else Axis.VERTICAL
                    }
                }
                // A vertical gesture is not ours to intercept: the content keeps it untouched.
                if (axis == Axis.VERTICAL) return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Always let DrawerLayout settle its drag helper before the decision is dropped.
                val handled = super.onInterceptTouchEvent(ev)
                axis = Axis.UNDECIDED
                return handled
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
