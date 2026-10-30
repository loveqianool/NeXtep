package io.github.loveqianool.freewindow.window

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.loveqianool.freewindow.input.ImeHelper
import io.github.loveqianool.freewindow.input.ResizeHandler
import io.github.loveqianool.freewindow.input.TouchForwarder

/**
 * A free window: vertical stack of [TitleBar] (outside, on top) + [TextureView]
 * showing the VirtualDisplay content.
 *
 * - Windowed mode: title bar is visible above the content (44dp). The VD
 *   matches the content size (window minus title bar).
 * - Maximized: title bar is GONE, content takes the full window.
 * - Touches on the content are forwarded to the VirtualDisplay via
 *   [TouchForwarder]; ACTION_DOWN also notifies [host] (focus + bring to
 *   front).
 * - Tapping outside the window (ACTION_OUTSIDE, via
 *   FLAG_WATCH_OUTSIDE_TOUCH) drops window focus so underlying apps keep
 *   working, see [ImeHelper].
 */
class FreeWindowView @JvmOverloads constructor(
    context: Context,
    val windowId: Int,
    private val host: WindowHost,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Fired with a fresh [Surface] every time the SurfaceTexture is created. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    /** VirtualDisplay id backing this window; -1 until created. */
    var displayId: Int = -1

    private val textureView = TextureView(context)
    private val titleBar = TitleBar(context, windowId, host)

    private var resizeHandleView: View? = null

    /** Ensures touch events are forwarded in order (DOWN before MOVE/UP). */
    private var lastForwardAt = 0L
    private val touchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Forwards a touch event after [extraDelay] ms, ensuring it is not
     * delivered before any previously queued event. The framework owns
     * [event] (pooled); we forward a copy. That copy is already caller-owned,
     * so it goes through [TouchForwarder.forwardOwned] — the old path ran it
     * through [TouchForwarder.forward] as well, which obtained a second copy
     * on every touch event.
     */
    /**
     * Resolve the display id at delivery time, not at event time: tapping
     * a background window brings it to front first (Surface/VD rebuild,
     * new display id), and a DOWN delayed 233ms for the focus transition
     * must go to the new display, not the released old one. Dropping the
     * whole gesture when there is still no display (or the view is already
     * detached) is better than a DOWN-less MOVE/UP on another display.
     */
    private fun forwardOrdered(event: android.view.MotionEvent, extraDelay: Long) {
        val copy = android.view.MotionEvent.obtain(event)
        val at = maxOf(
            android.os.SystemClock.uptimeMillis() + extraDelay,
            lastForwardAt,
        )
        lastForwardAt = at
        touchHandler.postAtTime({
            val didNow = if (parent == null) -1 else displayId
            if (didNow >= 0) {
                TouchForwarder.forwardOwned(copy, didNow)
            }
            copy.recycle()
        }, at)
    }

    private val resizeHandler = ResizeHandler(
        context,
        context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
        android.view.WindowManager.LayoutParams.TYPE_PHONE,
        object : ResizeHandler.Callback {
            override fun currentRect(): android.graphics.Rect = host.currentRect(windowId)
            override fun usableRect(): android.graphics.Rect = host.usableRect(windowId)
            override fun onResizeCommit(rect: android.graphics.Rect) =
                host.onResizeCommit(windowId, rect)
            // Tap on the resize area no longer arms anything: the top-left
            // tap below toggles the title bar on its own, in both modes.
            override fun onResizeAreaTap() = Unit
        },
    )

    init {
        val density = resources.displayMetrics.density
        val barHeight = (TITLE_BAR_DP * density).toInt()

        // Vertical stack: title bar on top (outside content), TextureView below.
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
        }

        titleBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            barHeight,
        )
        contentLayout.addView(titleBar)

        textureView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f, // Takes remaining space
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "surface ready window=$windowId ${w}x$h")
                st.setDefaultBufferSize(w, h)
                onSurfaceReady?.invoke(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "surface size changed window=$windowId ${w}x$h")
                st.setDefaultBufferSize(w, h)
            }

            // Return true: we keep the SurfaceTexture alive; WindowManager
            // releases the Surface on rebuild/close. Returning false lets the
            // framework destroy it while the VD still renders to it (blank).
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        textureView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Delay DOWN: updateViewLayout (focusable) is async; the app
                // requests the IME on DOWN, which needs the window to already
                // be focusable. Only delay when actually transitioning to
                // focusable; otherwise the tap feels laggy.
                val needDelay = !ImeHelper.isFocusable(this@FreeWindowView)
                ImeHelper.setFocusable(this@FreeWindowView, true)
                resizeHandler.setIndicatorVisible(true)
                host.onTap(windowId)
                // Always queue: the display id is resolved at delivery time,
                // so a bring-to-front rebuild between DOWN and delivery
                // still lands the whole gesture on the new display.
                forwardOrdered(event, if (needDelay) 233L else 0L)
            } else {
                // MOVE/UP must not overtake the delayed DOWN; queue them in order.
                forwardOrdered(event, 0L)
            }
            true
        }
        contentLayout.addView(textureView)
        addView(contentLayout)

        // Resize handle at bottom-right (of the whole window).
        val handleView = resizeHandler.createHandleView()
        resizeHandleView = handleView
        val handleSize = (ResizeHandler.HANDLE_DP * density).toInt()
        addView(
            handleView,
            LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            },
        )

        // Top-left tap area for title-bar toggle: one tap, in both modes.
        // The 48dp zone already sits above the app content (windowed: it
        // covers title-bar space; maximized: it already swallowed taps
        // there), so a single tap costs no extra accidental-tap risk
        // versus the old double-tap / two-tap gestures.
        val toggleSize = (48 * density).toInt()
        val toggleView = View(context).apply {
            isClickable = true
            setOnClickListener { toggleTitleBar() }
        }
        addView(
            toggleView,
            LayoutParams(toggleSize, toggleSize).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )

        // Taps outside the window: drop focusability.
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                ImeHelper.setFocusable(this@FreeWindowView, false)
                resizeHandler.setIndicatorVisible(false)
                true
            } else {
                false
            }
        }
        Log.i(TAG, "FreeWindowView created window=$windowId")
    }

    /** Toggles title bar visibility (top-left single tap, both modes). */
    fun toggleTitleBar() {
        val isVisible = titleBar.visibility == View.VISIBLE
        titleBar.visibility = if (isVisible) View.GONE else View.VISIBLE
        // When bar visibility changes, the content size changes; notify host
        // so the VD can be resized.
        host.onContentSizeChanged(windowId)
        Log.i(TAG, "title bar toggled window=$windowId visible=${!isVisible}")
    }

    fun setMuted(muted: Boolean) = titleBar.setMuted(muted)

    fun isTitleBarVisible(): Boolean = titleBar.visibility == View.VISIBLE

    fun setMaximized(maximized: Boolean) {
        titleBar.setMaximized(maximized)
        titleBar.visibility = if (maximized) View.GONE else View.VISIBLE
        resizeHandleView?.visibility = if (maximized) View.GONE else View.VISIBLE
        // Content size changed (bar gone/shown); host resizes the VD.
        host.onContentSizeChanged(windowId)
    }

    /**
     * Releases the SurfaceTexture. Call when the window is permanently closed
     * (not on temporary detach like bringToFront). We return true from
     * onSurfaceTextureDestroyed, so ownership is ours.
     */
    fun releaseSurfaceTexture() {
        runCatching { textureView.surfaceTexture?.release() }
    }

    /**
     * Content size (VD size): window size minus title bar (if visible).
     * Called by host to size the VirtualDisplay.
     */
    fun contentSize(): android.graphics.Rect {
        val barH = if (titleBar.visibility == View.VISIBLE) {
            (TITLE_BAR_DP * resources.displayMetrics.density).toInt()
        } else {
            0
        }
        return android.graphics.Rect(0, 0, width, (height - barH).coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "FreeWindow"

        /** Title bar height in dp; content height = window height - this. */
        const val TITLE_BAR_DP = 38
    }
}
