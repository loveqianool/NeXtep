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
 * Title bar for a free window: drag to move, plus a hamburger menu
 * (collapse / fit / crop / mute) and park / maximize / close buttons.
 * 38dp tall, semi-transparent black.
 */
class TitleBar @JvmOverloads constructor(
    context: Context,
    private val windowId: Int,
    private val host: WindowHost,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val menuBtn = makeButton("☰", "更多功能")
    private val parkBtn = makeButton("▽", "最小化到后台")
    private val maxBtn = makeButton("⛶", "最大化")
    private val closeBtn = makeButton("✕", "关闭窗口")

    // Menu item text follows current state; refreshed each time the menu opens.
    private var muted = false
    private var cropped = false

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
        addView(menuBtn)
        addView(parkBtn)
        addView(maxBtn)
        addView(closeBtn)

        menuBtn.setOnClickListener { showMenu() }
        parkBtn.setOnClickListener { host.onPark(windowId) }
        maxBtn.setOnClickListener { host.onToggleMaximize(windowId) }
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
        this.muted = muted
    }

    fun setMaximized(maximized: Boolean) {
        maxBtn.text = if (maximized) "🗗" else "⛶"
        maxBtn.contentDescription = if (maximized) "恢复窗口大小" else "最大化"
    }

    fun setCropped(cropped: Boolean) {
        this.cropped = cropped
    }

    // Built fresh on every open so mute/crop labels track current state.
    private fun showMenu() {
        val popup = android.widget.PopupMenu(context, menuBtn)
        val menu = popup.menu
        menu.add(0, MENU_COLLAPSE, 0, "缩成图标")
        menu.add(0, MENU_FIT, 1, "按应用宽高比适配")
        menu.add(0, MENU_CROP, 2, if (cropped) "恢复完整画面" else "裁剪画面")
        menu.add(0, MENU_MUTE, 3, if (muted) "取消静音" else "静音")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COLLAPSE -> host.onCollapse(windowId)
                MENU_FIT -> host.onFitAspect(windowId)
                MENU_CROP -> host.onCrop(windowId)
                MENU_MUTE -> host.onToggleMute(windowId)
            }
            true
        }
        popup.show()
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
        private const val MENU_COLLAPSE = 1
        private const val MENU_FIT = 2
        private const val MENU_CROP = 3
        private const val MENU_MUTE = 4
    }
}
