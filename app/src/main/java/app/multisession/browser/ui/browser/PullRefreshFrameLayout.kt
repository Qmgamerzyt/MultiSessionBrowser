package app.multisession.browser.ui.browser

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.isVisible
import kotlin.math.abs

/**
 * v2.1.7 (issue H): pull-to-refresh wrapper around the GeckoView.
 *
 * How it works: the gesture only arms when [canArm] says the page is scrolled to its very top
 * (Chrome/Firefox Android behave the same - a pull on a scrolled page is a normal drag). Once armed,
 * a downward drag past the touch slop is intercepted from the GeckoView and shown as a small
 * indeterminate spinner; releasing past [refreshDistance] fires [onRefresh] (BrowserActivity reloads
 * the current tab) and the spinner stays until the load finishes ([setRefreshing] false on
 * onPageStop / tab switch, with a safety timeout so it can never stick).
 *
 * Deliberately touch-based instead of nested scrolling: GeckoView is not assumed to implement the
 * nested-scrolling child protocol, so nothing here depends on an API that may not exist.
 *
 * v2.1.9 (gesture sensitivity): three independent gates keep a normal page scroll from ever
 * becoming a refresh, without ever disabling the pull itself:
 *  1. **Trigger area** - the finger must start in the top band of the container (top 40%, at least
 *     96dp), so a pull started halfway down the screen belongs to the page, not to us.
 *  2. **Axis lock** - the gesture's direction is decided once, after 48dp of movement, from the
 *     *relative* horizontal/vertical travel. Only a clearly vertical (downward) gesture may be
 *     intercepted; a horizontal or diagonal one is handed straight to the page and the decision is
 *     final for the rest of that gesture (no flip-flopping mid-drag).
 *  3. **Threshold** - [refreshDistance] was raised 96dp -> 160dp, so an inertial fling that happens
 *     to start at the top of the page cannot reach it. [canRefresh] is re-checked at the moment of
 *     interception and again on release, and ACTION_CANCEL always aborts.
 *
 * v2.1.10 (A9, Discord): arming ([canArm], ACTION_DOWN) keeps the cheap legacy root-scroll rules;
 * interception and release ([canRefresh]) consult GeckoView's `onTouchEventForDetailResult`
 * (`PanZoomController.InputResultDetail`), which reports the scroll container *under the finger*
 * (inner scrollers like Discord's message list included), not just the root document. The owner of
 * each gesture is therefore explicit: vertical = page scroll, and only a deliberate downward pull
 * in the top band with Gecko's blessing = refresh. No second gesture recognizer is added - this
 * class stays the single place that decides, and it only ever *declines* to intercept, never to
 * consume.
 */
class PullRefreshFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Runs when the pull passes the threshold (reload the current tab). */
    var onRefresh: (() -> Unit)? = null

    /**
     * Consulted at ACTION_DOWN only: may a gesture arm at all (page not fullscreen, not the start
     * page, not already refreshing, root document at offset 0). Split from [canRefresh] in v2.1.10
     * because Gecko's per-gesture detail has not resolved yet when DOWN arrives - arming stays
     * cheap and synchronous, while [canRefresh] decides the actual interception.
     */
    var canArm: () -> Boolean = { false }

    /** Consulted at intercept/release: is the scroll container under the finger at its top edge? (v2.1.10: fed `InputResultDetail`, legacy root-scroll fallback inside.) */
    var canRefresh: () -> Boolean = { false }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** Movement needed before the axis of a gesture may be decided: far above touch slop, so jitter never locks a direction. */
    private val axisLockSlop = 48f * density
    /** Minimum height of the pull trigger area at the top of the container. */
    private val minTriggerArea = 96f * density
    /** The trigger area is this share of the container's height (its floor is [minTriggerArea]). */
    private val triggerAreaRatio = 0.40f
    /** Pull distance that fires the refresh. Raised from 96dp (v2.1.7) so ordinary scrolling can't reach it. */
    private val refreshDistance = 160f * density
    private val autoHideMs = 20_000L

    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = GONE
        elevation = 16f * density
        alpha = 1f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private enum class Axis { UNDECIDED, VERTICAL, HORIZONTAL }

    private var startX = 0f
    private var startY = 0f
    private var pullDistance = 0f
    private var axis = Axis.UNDECIDED
    private var armed = false
    private var pulling = false
    private var refreshing = false

    private val autoHide = Runnable { setRefreshing(false) }

    init {
        val size = (32 * density).toInt()
        addView(
            indicator,
            LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (14 * density).toInt()
            },
        )
    }

    /** True while a refresh is in flight (the pull gesture itself never sets it). */
    val isRefreshing: Boolean get() = refreshing

    fun setRefreshing(value: Boolean) {
        refreshing = value
        removeCallbacks(autoHide)
        if (value) {
            indicator.alpha = 1f
            indicator.isVisible = true
            postDelayed(autoHide, autoHideMs)
        } else if (indicator.isVisible) {
            indicator.isVisible = false
        }
        if (!value) { armed = false; pulling = false; axis = Axis.UNDECIDED }
    }

    /** Top band of the container in which a pull may start: top 40%, never less than 96dp. */
    private fun triggerAreaBottom(): Float = maxOf(minTriggerArea, height * triggerAreaRatio)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                pullDistance = 0f
                pulling = false
                axis = Axis.UNDECIDED
                // Gate 1: top band only, plus the cheap legacy page-state rules (canArm). Everything
                // below the band is plain page scrolling, whatever the page is doing.
                armed = ev.y <= triggerAreaBottom() && canArm()
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed && !pulling) {
                    val dx = abs(ev.x - startX)
                    val dy = ev.y - startY
                    // Gate 2: decide the axis once, after 48dp of travel, and keep it for the whole gesture.
                    if (axis == Axis.UNDECIDED && (dx > axisLockSlop || abs(dy) > axisLockSlop)) {
                        axis = if (abs(dy) > dx * 1.5f) Axis.VERTICAL else Axis.HORIZONTAL
                    }
                    // Gate 3 + re-check: only a downward vertical pull past the slop is ours, and only
                    // if the page is still at its top right now.
                    if (axis == Axis.VERTICAL && dy > axisLockSlop && canRefresh()) {
                        pulling = true
                        indicator.alpha = 0.35f
                        indicator.isVisible = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                armed = false
                pulling = false
                axis = Axis.UNDECIDED
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Only a gesture we actually intercepted (pulling) may drive the indicator or fire a refresh.
        // `armed` alone must not reach this method: otherwise a drag the GeckoView declines to take
        // could charge a distance in complete silence and reload the page with no indicator shown.
        if (!pulling) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                pullDistance = (ev.y - startY).coerceAtLeast(0f)
                // Spinner fills up with the pull, so the threshold is visible instead of guessed.
                indicator.alpha = 0.35f + 0.65f * (pullDistance / refreshDistance).coerceIn(0f, 1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Re-check at release too: the page may have been scrolled by the drag itself.
                val trigger = pullDistance >= refreshDistance && canRefresh()
                pulling = false
                armed = false
                axis = Axis.UNDECIDED
                if (trigger) {
                    setRefreshing(true)
                    onRefresh?.invoke()
                } else if (!refreshing) {
                    indicator.isVisible = false
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Cancel always aborts: nothing may fire off a gesture the system took away from us.
                pulling = false
                armed = false
                axis = Axis.UNDECIDED
                if (!isRefreshing) indicator.isVisible = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
