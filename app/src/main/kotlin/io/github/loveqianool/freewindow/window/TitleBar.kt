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
 * Title bar for a free window: drag to move, plus park / maximize /
 * mute / close buttons. 44dp tall, semi-transparent black.
 */
class TitleBar @JvmOverloads constructor(
    context: Context,
    private val windowId: Int,
    private val host: WindowHost,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val parkBtn = makeButton("▽")
    private val maxBtn = makeButton("⛶")
    private val muteBtn = makeButton("🔊")
    private val closeBtn = makeButton("✕")

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
        addView(parkBtn)
        addView(maxBtn)
        addView(muteBtn)
        addView(closeBtn)

        parkBtn.setOnClickListener { host.onPark(windowId) }
        maxBtn.setOnClickListener { host.onToggleMaximize(windowId) }
        muteBtn.setOnClickListener { host.onToggleMute(windowId) }
        closeBtn.setOnClickListener { host.onClose(windowId) }

        // Drag the bar (anywhere not on a button) to move the window.
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        host.onMove(windowId, dx.toInt(), dy.toInt())
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    true
                }
                else -> false
            }
        }
        Log.i(TAG, "TitleBar created window=$windowId")
    }

    fun setMuted(muted: Boolean) {
        muteBtn.text = if (muted) "🔇" else "🔊"
    }

    fun setMaximized(maximized: Boolean) {
        maxBtn.text = if (maximized) "🗗" else "⛶"
    }

    private fun makeButton(text: String): TextView {
        val size = (44 * resources.displayMetrics.density).toInt()
        return TextView(context).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(size, size)
        }
    }

    companion object {
        private const val TAG = "FreeWindow"
    }
}
