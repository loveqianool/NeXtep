package io.github.lujinxin.nextep.freewindow

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floating ball shown inside the SystemUI overlay. Draggable, tap to expand the
 * app list. A badge shows the number of parked (background) windows.
 *
 * Movement itself is delegated to [FloatingBallHost]: this view only reports
 * touch deltas; the host repositions it via [setBallRect].
 */
class FloatingBallView(context: Context, private val host: FloatingBallHost) : FrameLayout(context) {

    private val badge: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE53935.toInt())
        }
        visibility = View.GONE
        layoutParams = LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply {
            // Slight overhang past the ball edge.
            topMargin = -dp(4)
            marginEnd = -dp(4)
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC1A1A1A.toInt())
            setStroke(dp(2), 0x66FFFFFF.toInt())
        }
        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                }
                layoutParams = LayoutParams(dp(14), dp(14), Gravity.CENTER)
            },
        )
        addView(badge)

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!dragging && Math.hypot(totalDx.toDouble(), totalDy.toDouble()) > dp(8)) {
                        dragging = true
                    }
                    if (dragging) {
                        host.onBallMove(
                            (event.rawX - lastRawX).toInt(),
                            (event.rawY - lastRawY).toInt(),
                        )
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) {
                        host.onBallTap()
                    }
                    host.onBallRelease()
                }
            }
            true
        }
    }

    /** Badge shows [n]; hidden when n == 0. */
    fun setParkedCount(n: Int) {
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) badge.text = if (n > 99) "99+" else n.toString()
    }

    /** Positions the ball: 56dp x 56dp at the given rect's top-left. */
    fun setBallRect(rect: Rect) {
        layoutParams = LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
    }

    /** Reads the ball rect back from its LayoutParams. */
    fun currentRect(): Rect {
        val lp = layoutParams as? LayoutParams ?: return Rect(0, 0, 0, 0)
        return Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
