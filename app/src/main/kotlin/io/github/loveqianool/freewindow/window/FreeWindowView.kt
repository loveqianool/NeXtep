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

    init {
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "surface ready window=$windowId ${w}x$h")
                onSurfaceReady?.invoke(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

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

    fun setMuted(muted: Boolean) = titleBar.setMuted(muted)

    fun setMaximized(maximized: Boolean) {
        titleBar.setMaximized(maximized)
        titleBar.visibility = if (maximized) View.GONE else View.VISIBLE
    }

    companion object {
        private const val TAG = "FreeWindow"
    }
}
