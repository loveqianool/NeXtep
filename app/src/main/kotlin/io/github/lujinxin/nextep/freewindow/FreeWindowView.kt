package io.github.lujinxin.nextep.freewindow

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * A single free-floating app window. Lives inside a fullscreen FrameLayout
 * overlay container in the com.android.systemui process.
 *
 * Own position/size come from [setWindowRect] (FrameLayout.LayoutParams with
 * TOP|START gravity). Rendering surface is [textureView]; the manager wires
 * [onSurfaceReady] to attach a VirtualDisplay.
 */
class FreeWindowView(
    context: Context,
    val windowId: Int,
    private val host: FreeWindowViewHost,
) : FrameLayout(context) {

    var onSurfaceReady: ((Surface) -> Unit)? = null

    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val muteBtn = makeTitleButton("🔊")
    private val maxBtn = makeTitleButton("⛶")
    private val parkBtn = makeTitleButton("▽")
    private val closeBtn = makeTitleButton("✕")
    private val titleBar = LinearLayout(context)
    private val textureView = TextureView(context)

    // Title-bar drag / long-press-carry state.
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var titleDown = false
    private var titleDragging = false
    private var titleCarry = false
    private val carryRunnable = Runnable {
        if (titleDown && !titleDragging) {
            titleCarry = true
            animate().alpha(0.8f).setDuration(120).start()
        }
    }

    // Resize-handle state.
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartRect: Rect? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            onSurfaceReady?.invoke(Surface(surface))
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val titleTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                titleDown = true
                titleDragging = false
                titleCarry = false
                postDelayed(carryRunnable, 500)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!titleDragging &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > dp(8)
                ) {
                    titleDragging = true
                    removeCallbacks(carryRunnable)
                }
                if (titleDragging) {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    host.onWindowMove(windowId, dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(carryRunnable)
                titleDown = false
                if (titleCarry) {
                    host.onWindowCarryRelease(windowId, event.rawX.toInt(), event.rawY.toInt())
                } else if (!titleDragging) {
                    host.onWindowTap(this@FreeWindowView.windowId)
                }
                titleCarry = false
                animate().alpha(1f).setDuration(120).start()
            }
        }
        true
    }

    private val resizeTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartRawX = event.rawX
                resizeStartRawY = event.rawY
                resizeStartRect = currentRect()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val start = resizeStartRect ?: return@OnTouchListener true
                val minPx = dp(200)
                val newW =
                    (start.width() + event.rawX - resizeStartRawX).toInt().coerceAtLeast(minPx)
                val newH =
                    (start.height() + event.rawY - resizeStartRawY).toInt().coerceAtLeast(minPx)
                setWindowRect(Rect(start.left, start.top, start.left + newW, start.top + newH))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                host.onWindowResizeCommit(windowId, currentRect())
                resizeStartRect = null
            }
        }
        true
    }

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), 0x66FFFFFF.toInt())
            cornerRadius = dp(12).toFloat()
        }

        iconView.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(6), 0, dp(6), 0)
            }
        }
        titleView.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        muteBtn.setOnClickListener { host.onWindowMuteToggle(windowId) }
        maxBtn.setOnClickListener { host.onWindowMaximizeToggle(windowId) }
        parkBtn.setOnClickListener { host.onWindowPark(windowId) }
        closeBtn.setOnClickListener { host.onWindowClose(windowId) }

        titleBar.apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC1F1F1F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
            )
            addView(iconView)
            addView(titleView)
            addView(muteBtn)
            addView(maxBtn)
            addView(parkBtn)
            addView(closeBtn)
            setOnTouchListener(titleTouchListener)
        }

        textureView.apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            surfaceTextureListener = surfaceListener
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    host.onWindowTap(this@FreeWindowView.windowId)
                }
                false
            }
        }

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
            addView(titleBar)
            addView(textureView)
        }
        addView(contentColumn)

        val resizeHandle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88FFFFFF.toInt())
            }
            layoutParams = LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            setOnTouchListener(resizeTouchListener)
        }
        addView(resizeHandle)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(carryRunnable)
        super.onDetachedFromWindow()
    }

    fun setAppInfo(icon: Drawable?, title: String) {
        iconView.setImageDrawable(icon)
        titleView.text = title
    }

    fun setMuted(muted: Boolean) {
        muteBtn.text = if (muted) "🔇" else "🔊"
    }

    fun setMaximized(maximized: Boolean) {
        maxBtn.text = if (maximized) "▣" else "⛶"
    }

    fun setWindowRect(rect: Rect) {
        val lp = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(rect.width(), rect.height(), Gravity.TOP or Gravity.START)
        lp.width = rect.width()
        lp.height = rect.height()
        lp.leftMargin = rect.left
        lp.topMargin = rect.top
        lp.gravity = Gravity.TOP or Gravity.START
        layoutParams = lp
        requestLayout()
    }

    fun currentRect(): Rect {
        val lp = layoutParams as? FrameLayout.LayoutParams
        val w = if (lp != null && lp.width > 0) lp.width else width
        val h = if (lp != null && lp.height > 0) lp.height else height
        val l = lp?.leftMargin ?: 0
        val t = lp?.topMargin ?: 0
        return Rect(l, t, l + w, t + h)
    }

    /** The live Surface wrapping the current SurfaceTexture, or null if unavailable. */
    fun currentSurface(): Surface? {
        val texture = textureView.surfaceTexture
        return if (textureView.isAvailable && texture != null) {
            runCatching { Surface(texture) }.getOrNull()
        } else {
            null
        }
    }

    private fun makeTitleButton(glyph: String): TextView = TextView(context).apply {
        text = glyph
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
