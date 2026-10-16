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
import io.github.loveqianool.freewindow.input.ImeHelper
import io.github.loveqianool.freewindow.input.ResizeHandler
import io.github.loveqianool.freewindow.input.TouchForwarder

/**
 * A free window: a [TextureView] showing the VirtualDisplay content with a
 * [TitleBar] overlaid on top.
 *
 * - The TextureView fills the whole window; the title bar is shown by
 *   default in windowed mode (hidden when maximized, see [setMaximized]).
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

    private var resizeHandleView: View? = null

    private val resizeHandler = ResizeHandler(
        context,
        context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
        android.view.WindowManager.LayoutParams.TYPE_PHONE,
        object : ResizeHandler.Callback {
            override fun currentRect(): android.graphics.Rect = host.currentRect(windowId)
            override fun onResizePreview(rect: android.graphics.Rect) =
                host.onResizePreview(windowId, rect)
            override fun onResizeCommit(rect: android.graphics.Rect) =
                host.onResizeCommit(windowId, rect)
            override fun onResizeAreaTap() {
                // First tap of the title-bar toggle gesture.
                titleToggleArmed = true
                // Disarm after 2s if no second tap.
                postDelayed({ titleToggleArmed = false }, 2000)
            }
        },
    )

    init {
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "surface ready window=$windowId ${w}x$h")
                st.setDefaultBufferSize(w, h)
                onSurfaceReady?.invoke(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "surface size changed window=$windowId ${w}x$h")
                // Update the buffer size to match; otherwise the VD renders at
                // the wrong scale.
                st.setDefaultBufferSize(w, h)
                host.onSurfaceSizeChanged(windowId)
            }

            // Let the framework release the SurfaceTexture; the Surface we
            // handed out is released by WindowManager on rebuild/close.
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = false

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
        addView(textureView)

        val barHeight = (44 * resources.displayMetrics.density).toInt()
        addView(
            titleBar,
            LayoutParams(LayoutParams.MATCH_PARENT, barHeight).apply {
                gravity = Gravity.TOP
            },
        )

        // Resize handle at bottom-right.
        val handleView = resizeHandler.createHandleView()
        resizeHandleView = handleView
        val handleSize = (ResizeHandler.HANDLE_DP * resources.displayMetrics.density).toInt()
        addView(
            handleView,
            LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            },
        )

        // Top-left tap area for title-bar toggle (second tap of the gesture).
        // Only active when titleToggleArmed (after tapping resize area).
        val toggleSize = (48 * resources.displayMetrics.density).toInt()
        val toggleView = View(context).apply {
            isClickable = true
            setOnClickListener {
                if (titleToggleArmed) {
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
        Log.i(TAG, "title bar toggled window=$windowId visible=${!isVisible}")
    }

    fun setMuted(muted: Boolean) = titleBar.setMuted(muted)

    fun setMaximized(maximized: Boolean) {
        titleBar.setMaximized(maximized)
        titleBar.visibility = if (maximized) View.GONE else View.VISIBLE
        // No resize handle in maximized mode.
        resizeHandleView?.visibility = if (maximized) View.GONE else View.VISIBLE
        // Disarm any pending title toggle.
        titleToggleArmed = false
    }

    companion object {
        private const val TAG = "FreeWindow"
    }
}
