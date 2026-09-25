package app.multisession.browser.ui.browser

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.view.isVisible

/**
 * v2.1.7 (issue H): pull-to-refresh wrapper around the GeckoView.
 *
 * How it works: the gesture only arms when [canRefresh] says the page is scrolled to its very top
 * (Chrome/Firefox Android behave the same - a pull on a scrolled page is a normal drag). Once armed,
 * a downward drag past the touch slop is intercepted from the GeckoView and shown as a small
 * indeterminate spinner; releasing past [refreshDistance] fires [onRefresh] (BrowserActivity reloads
 * the current tab) and the spinner stays until the load finishes ([setRefreshing] false on
 * onPageStop / tab switch, with a safety timeout so it can never stick).
 *
 * Deliberately touch-based instead of nested scrolling: GeckoView is not assumed to implement the
 * nested-scrolling child protocol, so nothing here depends on an API that may not exist.
 */
class PullRefreshFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Runs when the pull passes the threshold (reload the current tab). */
    var onRefresh: (() -> Unit)? = null

    /** Consulted at ACTION_DOWN: only a page at scroll offset 0 (and not fullscreen) may start a pull. */
    var canRefresh: () -> Boolean = { false }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val refreshDistance = 96f * density
    private val autoHideMs = 20_000L

    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = GONE
        elevation = 16f * density
        alpha = 1f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var startY = 0f
    private var pullDistance = 0f
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
        if (!value) { armed = false; pulling = false }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                pullDistance = 0f
                pulling = false
                armed = canRefresh()
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed && !pulling && ev.y - startY > touchSlop) {
                    pulling = true
                    indicator.alpha = 0.35f
                    indicator.isVisible = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!armed && !pulling) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                pullDistance = (ev.y - startY).coerceAtLeast(0f)
                // Spinner fills up with the pull, so the threshold is visible instead of guessed.
                indicator.alpha = 0.35f + 0.65f * (pullDistance / refreshDistance).coerceIn(0f, 1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val trigger = pullDistance >= refreshDistance
                pulling = false
                armed = false
                if (trigger) {
                    setRefreshing(true)
                    onRefresh?.invoke()
                } else if (!refreshing) {
                    indicator.isVisible = false
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pulling = false
                armed = false
                if (!isRefreshing) indicator.isVisible = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
