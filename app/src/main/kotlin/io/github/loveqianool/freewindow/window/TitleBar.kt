package io.github.loveqianool.freewindow.window

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Title bar for a free window: drag to move, plus collapse / park /
 * maximize / mute / close buttons. 38dp tall, semi-transparent black.
 */
class TitleBar @JvmOverloads constructor(
    context: Context,
    private val windowId: Int,
    private val host: WindowHost,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val collapseBtn = makeButton("🗕", "缩成图标")
    private val parkBtn = makeButton("▽", "最小化到后台")
    private val maxBtn = makeButton("⛶", "最大化")
    private val muteBtn = makeButton("🔊", "静音")
    private val closeBtn = makeButton("✕", "关闭窗口")

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0x99000000.toInt())

        // Flexible drag space on the left; buttons docked right.
        val spacer = View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        addView(spacer)
        addView(collapseBtn)
        addView(parkBtn)
        addView(maxBtn)
        addView(muteBtn)
        addView(closeBtn)

        collapseBtn.setOnClickListener { host.onCollapse(windowId) }
        parkBtn.setOnClickListener { host.onPark(windowId) }
        maxBtn.setOnClickListener { host.onToggleMaximize(windowId) }
        muteBtn.setOnClickListener { host.onToggleMute(windowId) }
        closeBtn.setOnClickListener { host.onClose(windowId) }

        // Entire bar is draggable, including on buttons: intercept the touch
        // stream once it becomes a drag. Taps still reach the buttons.
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - lastRawX
                val dy = ev.rawY - lastRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    // Intercept: buttons won't get the rest of the gesture.
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the stream for blank-area touches; buttons use intercept path.
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) {
                    // Advance only by the integer pixels actually moved:
                    // resetting lastRaw to the event position would discard
                    // the sub-pixel remainder on every MOVE, so a slow drag
                    // (under 1px per event) would never move the window.
                    val idx = dx.toInt()
                    val idy = dy.toInt()
                    host.onMove(windowId, idx, idy)
                    lastRawX += idx
                    lastRawY += idy
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setMuted(muted: Boolean) {
        muteBtn.text = if (muted) "🔇" else "🔊"
        muteBtn.contentDescription = if (muted) "取消静音" else "静音"
    }

    fun setMaximized(maximized: Boolean) {
        maxBtn.text = if (maximized) "🗗" else "⛶"
        maxBtn.contentDescription = if (maximized) "恢复窗口大小" else "最大化"
    }

    private fun makeButton(text: String, description: String): TextView {
        val size = (FreeWindowView.TITLE_BAR_DP * resources.displayMetrics.density).toInt()
        return TextView(context).apply {
            this.text = text
            contentDescription = description
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(size, size)
            // Tap feedback: plain text buttons gave none; the ripple
            // background is transparent, so the bar stays semi-transparent.
            val out = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }
    }

    companion object {
        private const val TAG = "FreeWindow"
    }
}
