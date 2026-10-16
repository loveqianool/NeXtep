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

    /** Armed by tapping the resize area; next tap on top-left toggles title bar. */
    private var titleToggleArmed = false

    private var isMaximized = false

    private var resizeHandleView: View? = null

    companion object {
        /** Title bar height in dp; content height = window height - this. */
        const val TITLE_BAR_DP = 44
    }

    private val resizeHandler = ResizeHandler(
        context,
        context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
        android.view.WindowManager.LayoutParams.TYPE_PHONE,
        object : ResizeHandler.Callback {
            override fun currentRect(): android.graphics.Rect = host.currentRect(windowId)
            override fun onResizeCommit(rect: android.graphics.Rect) =
                host.onResizeCommit(windowId, rect)
            override fun onResizeAreaTap() {
                titleToggleArmed = true
                postDelayed({ titleToggleArmed = false }, 2000)
            }
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
                ImeHelper.setFocusable(this@FreeWindowView, true)
                host.onTap(windowId)
            }
            if (displayId >= 0) {
                TouchForwarder.forward(event, displayId)
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

        // Top-left tap area for title-bar toggle in maximized mode.
        // In windowed mode the bar is always visible; single tap does nothing
        // unless armed (two-tap gesture).
        val toggleSize = (48 * density).toInt()
        val toggleView = View(context).apply {
            isClickable = true
            setOnClickListener {
                if (isMaximized) {
                    toggleTitleBar()
                } else if (titleToggleArmed) {
                    titleToggleArmed = false
                    toggleTitleBar()
                }
            }
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
                true
            } else {
                false
            }
        }
        Log.i(TAG, "FreeWindowView created window=$windowId")
    }

    /** Toggles title bar visibility (for maximized mode gesture). */
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
        isMaximized = maximized
        titleBar.setMaximized(maximized)
        titleBar.visibility = if (maximized) View.GONE else View.VISIBLE
        resizeHandleView?.visibility = if (maximized) View.GONE else View.VISIBLE
        titleToggleArmed = false
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
    }
}
