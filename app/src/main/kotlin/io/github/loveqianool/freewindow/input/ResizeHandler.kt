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

/**
 * Resize gesture for a free window.
 *
 * - A 36dp invisible touch area sits at the window's bottom-right corner.
 *   It carries a faint "◢" corner mark that is only shown while the window
 *   is focused (see [setIndicatorVisible]); the mark is a separate child
 *   view, so hiding it never disables the touch area.
 * - While dragging, a bright-cyan (#FF00E5FF) 3dp dashed preview rect is
 *   shown in an independent system window (TYPE_PHONE,
 *   NOT_FOCUSABLE|NOT_TOUCHABLE); the window itself is not resized yet.
 *   The preview is clamped to the usable area, and the commit is clamped
 *   again by the host to the same area.
 * - On release the new rect is committed through [Callback.onResizeCommit];
 *   the host then resizes the VirtualDisplay and re-lays out the content.
 *   A plain tap (no drag) does nothing: the top-left tap below toggles
 *   the title bar on its own, in both modes.
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
        /** Usable area; the preview is clamped to it, like the commit. */
        fun usableRect(): Rect
        /** Drag finished: commit the resize for real. */
        fun onResizeCommit(rect: Rect)
    }

    companion object {
        const val HANDLE_DP = 36
        // Keep in sync with WindowManager: title-bar buttons total 152dp.
        const val MIN_SIZE_DP = 200
        const val TAP_SLOP_DP = 8
        private const val TAG = "FreeWindow"
        private const val PREVIEW_COLOR = 0xFF00E5FF.toInt()
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // Precomputed once: density does not change for a handle's lifetime,
    // and these were recomputed on every MOVE before.
    private val minPx = dp(MIN_SIZE_DP)
    private val tapSlopPxSquared = dp(TAP_SLOP_DP).let { it * it }.toFloat()

    private var previewView: View? = null
    private var indicatorView: View? = null

    /** Show/hide the faint corner mark; the touch area itself is unaffected. */
    fun setIndicatorVisible(visible: Boolean) {
        indicatorView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private var startRect: Rect? = null
    /** Usable area at gesture start; querying WindowMetrics per MOVE would be wasteful. */
    private var startUsable: Rect? = null
    /** Reused across MOVEs; only the UP commit copies it (once per gesture). */
    private val previewRect = Rect()
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
                startUsable = callback.usableRect()
                startRawX = event.rawX
                startRawY = event.rawY
                downX = event.rawX
                downY = event.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Squared distance: same threshold as hypot(), no sqrt per MOVE.
                if (!moved) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dx * dx + dy * dy > tapSlopPxSquared) moved = true
                }
                val start = startRect ?: return@OnTouchListener true
                // Clamp to the usable area, not the whole screen: otherwise
                // the preview can show a size larger than the commit, which
                // the host clamps back to the usable area on release.
                val usable = startUsable ?: Rect()
                val metrics = context.resources.displayMetrics
                val maxW = if (usable.isEmpty) {
                    (metrics.widthPixels - start.left)
                } else {
                    (usable.right - start.left)
                }.coerceAtLeast(minPx)
                val maxH = if (usable.isEmpty) {
                    (metrics.heightPixels - start.top)
                } else {
                    (usable.bottom - start.top)
                }.coerceAtLeast(minPx)
                val newW = (start.width() + event.rawX - startRawX).toInt()
                    .coerceIn(minPx, maxW)
                val newH = (start.height() + event.rawY - startRawY).toInt()
                    .coerceIn(minPx, maxH)
                previewRect.set(start.left, start.top, start.left + newW, start.top + newH)
                showPreview(previewRect)
            }
            MotionEvent.ACTION_UP -> {
                hidePreview()
                if (moved) {
                    // Host's clampRect returns a new Rect and never mutates
                    // its input, but copy once here so the reused previewRect
                    // can never leak into the host's state.
                    callback.onResizeCommit(Rect(previewRect))
                }
                startRect = null
                startUsable = null
            }
            MotionEvent.ACTION_CANCEL -> {
                // Cancelled, not released: discard the preview without
                // committing it. The old shared UP/CANCEL branch committed
                // a partial resize whenever the system aborted the gesture.
                hidePreview()
                startRect = null
                startUsable = null
            }
        }
        true
    }

    /**
     * Creates the 36dp handle view. The host adds it to the window's
     * bottom-right corner. The container itself is the touch target and
     * stays invisible; the "◢" mark inside it is shown only while focused.
     */
    fun createHandleView(): View {
        val indicator = android.widget.TextView(context).apply {
            text = "◢"
            textSize = 14f
            setTextColor(0x66FFFFFF)
            gravity = Gravity.BOTTOM or Gravity.END
            visibility = View.INVISIBLE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        indicatorView = indicator
        return android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                dp(HANDLE_DP),
                dp(HANDLE_DP),
                Gravity.BOTTOM or Gravity.END,
            )
            // Clickable so TalkBack can focus it; the touch listener
            // still consumes the gesture first, so touch behaviour is unchanged.
            isClickable = true
            contentDescription = "缩放窗口"
            addView(indicator)
            setOnTouchListener(touchListener)
        }
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

    /**
     * Drop the preview without committing. Called by the host view when
     * it detaches mid-drag (e.g. the window is closed while resizing):
     * without this the preview is an independent system window and would
     * stay on screen until SystemUI restarts, since no UP ever arrives.
     */
    fun cleanup() {
        hidePreview()
    }

    private fun hidePreview() {
        previewView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        previewView = null
    }
}
