package io.github.loveqianool.freewindow.input

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

/**
 * Resize gesture for a free window.
 *
 * - A 36dp invisible touch area sits at the window's bottom-right corner.
 * - While dragging, a bright-cyan (#FF00E5FF) 3dp dashed preview rect is
 *   shown in an independent system window (TYPE_PHONE,
 *   NOT_FOCUSABLE|NOT_TOUCHABLE); the window itself is not resized yet.
 * - On release the new rect is committed through [Callback.onResizeCommit];
 *   the host then resizes the VirtualDisplay and re-lays out the content.
 * - A plain tap (no drag) on the area is reported via
 *   [Callback.onResizeAreaTap] so the host can run its own gesture.
 */
class ResizeHandler(
    private val context: Context,
    private val windowManager: WindowManager,
    private val windowType: Int,
    private val callback: Callback,
) {
    interface Callback {
        /** Current window rect in screen coordinates, used as the drag start. */
        fun currentRect(): Rect
        /** Drag finished: commit the resize for real. */
        fun onResizeCommit(rect: Rect)
        /** Tap without drag on the resize area. */
        fun onResizeAreaTap()
    }

    companion object {
        const val HANDLE_DP = 36
        const val MIN_SIZE_DP = 200
        const val TAP_SLOP_DP = 8
        private const val TAG = "FreeWindow"
        private const val PREVIEW_COLOR = 0xFF00E5FF.toInt()
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var previewView: View? = null

    private var startRect: Rect? = null
    private var lastPreview: Rect? = null
    private var startRawX = 0f
    private var startRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    /** Touch listener to attach to the 36dp handle view. */
    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRect = Rect(callback.currentRect())
                lastPreview = null
                startRawX = event.rawX
                startRawY = event.rawY
                downX = event.rawX
                downY = event.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(event.rawX - downX, event.rawY - downY) > dp(TAP_SLOP_DP)) {
                    moved = true
                }
                val start = startRect ?: return@OnTouchListener true
                val minPx = dp(MIN_SIZE_DP)
                val newW = (start.width() + event.rawX - startRawX).toInt().coerceAtLeast(minPx)
                val newH = (start.height() + event.rawY - startRawY).toInt().coerceAtLeast(minPx)
                val preview = Rect(start.left, start.top, start.left + newW, start.top + newH)
                lastPreview = Rect(preview)
                showPreview(preview)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hidePreview()
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    callback.onResizeAreaTap()
                } else {
                    lastPreview?.let { callback.onResizeCommit(Rect(it)) }
                }
                startRect = null
                lastPreview = null
            }
        }
        true
    }

    /**
     * Creates the invisible 36dp handle view. The host adds it to the
     * window's bottom-right corner and attaches [touchListener].
     */
    fun createHandleView(): View = View(context).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(
            dp(HANDLE_DP),
            dp(HANDLE_DP),
            Gravity.BOTTOM or Gravity.END,
        )
        setOnTouchListener(touchListener)
    }

    private fun showPreview(rect: Rect) {
        val view = previewView ?: run {
            val v = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), PREVIEW_COLOR, dp(10).toFloat(), dp(5).toFloat())
                }
            }
            previewView = v
            v
        }
        val params = (view.layoutParams as? WindowManager.LayoutParams)
            ?: WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        if (view.parent == null) {
            runCatching { windowManager.addView(view, params) }
                .onFailure { Log.i(TAG, "ResizeHandler: add preview failed: ${it.message}") }
        } else {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun hidePreview() {
        previewView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        previewView = null
    }
}
