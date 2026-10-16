package io.github.lujinxin.nextep.freewindow

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.input.InputManager
import android.view.InputEvent
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
 * A single free-floating app window. Added as its own system window in the
 * com.android.systemui process (one WindowManager entry per window).
 *
 * Own position/size come from [setWindowRect] (tracked for drag bookkeeping;
 * the actual window geometry is owned by the manager via WindowManager
 * params). Rendering surface is [textureView]; the manager wires
 * [onSurfaceReady] to attach a VirtualDisplay. On window re-add (z-order)
 * the surface is recreated and the manager rebuilds the display.
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
    private lateinit var topLeftTapArea: View

    // Title-bar drag state.
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var titleDragging = false

    // Resize-handle state.
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartRect: Rect? = null
    private var resizeDownX = 0f
    private var resizeDownY = 0f
    private var resizeMoved = false

    // Title-bar visibility. Windowed mode shows it by default; maximized
    // hides it. Toggled by the two-tap gesture (bottom-right resize area,
    // then top-left corner).
    private var titleBarVisible = true
    private var armed = false
    private val disarmRunnable = Runnable { setArmed(false) }

    fun isTitleBarVisible(): Boolean = titleBarVisible

    /** Bottom-right tap: arm the gesture (or hide an already-visible bar). */
    fun armTitleBarGesture() {
        if (titleBarVisible) {
            hideTitleBar()
            return
        }
        setArmed(true)
    }

    /** Top-left tap: show the bar when armed; hide when visible. */
    fun onTopLeftTap() {
        if (titleBarVisible) {
            hideTitleBar()
        } else if (armed) {
            showTitleBar()
        }
    }

    private fun setArmed(value: Boolean) {
        armed = value
        topLeftTapArea.visibility = if (value) View.VISIBLE else View.GONE
        removeCallbacks(disarmRunnable)
        if (value) postDelayed(disarmRunnable, 3000)
    }

    fun showTitleBar() {
        if (titleBarVisible) return
        titleBarVisible = true
        setArmed(false)
        titleBar.visibility = View.VISIBLE
        notifyContentSize()
    }

    fun hideTitleBar() {
        if (!titleBarVisible) return
        titleBarVisible = false
        titleBar.visibility = View.GONE
        notifyContentSize()
    }

    private fun notifyContentSize() {
        val rect = currentRect()
        val h = rect.height() - (if (titleBarVisible) dp(TITLE_BAR_DP) else 0)
        host.onContentSizeChanged(windowId, rect.width().coerceAtLeast(1), h.coerceAtLeast(1))
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            onSurfaceReady?.invoke(Surface(surface))
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        // Let the framework release the SurfaceTexture on detach. When the
        // window is re-added (z-order change), a fresh SurfaceTexture is
        // created and the manager rebuilds the VirtualDisplay on it.
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val titleTouchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                titleDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!titleDragging &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > dp(8)
                ) {
                    titleDragging = true
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
                if (!titleDragging) {
                    host.onWindowTap(this@FreeWindowView.windowId)
                }
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
                resizeDownX = event.rawX
                resizeDownY = event.rawY
                resizeMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.rawX - resizeDownX, event.rawY - resizeDownY) > dp(8)) {
                    resizeMoved = true
                }
                val start = resizeStartRect ?: return@OnTouchListener true
                val minPx = dp(200)
                val newW =
                    (start.width() + event.rawX - resizeStartRawX).toInt().coerceAtLeast(minPx)
                val newH =
                    (start.height() + event.rawY - resizeStartRawY).toInt().coerceAtLeast(minPx)
                val preview = Rect(start.left, start.top, start.left + newW, start.top + newH)
                setWindowRect(preview)
                host.onWindowResizePreview(windowId, preview)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!resizeMoved && event.actionMasked == MotionEvent.ACTION_UP) {
                    // Plain tap: arm (or toggle) the title-bar gesture.
                    host.onResizeAreaTap(windowId)
                } else {
                    host.onWindowResizeCommit(windowId, currentRect())
                }
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
            // Visible by default in windowed mode (GONE = zero height in the
            // vertical stack, so content fills the whole window when hidden).
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(TITLE_BAR_DP),
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
            // NB: never set a background on a TextureView — it throws
            // UnsupportedOperationException. Fills the LinearLayout cell
            // 1:1 with the VirtualDisplay, so touches map without scaling.
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
                forwardToDisplay(event)
                true
            }
        }

        // Vertical stack: title bar (gone by default) + content. When the
        // bar is GONE the TextureView takes the full window, so a maximized
        // window uses the entire screen.
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
            addView(titleBar)
            addView(textureView)
        }

        // Black backdrop behind the (transparent-until-drawn) TextureView.
        setBackgroundColor(0xFF000000.toInt())
        addView(contentLayout)

        // Top-left hot corner: only visible (hit-testable) while the
        // show-title-bar gesture is armed, so it never steals app taps.
        topLeftTapArea = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START)
            visibility = View.GONE
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    host.onTopLeftTap(this@FreeWindowView.windowId)
                }
                true
            }
        }
        addView(topLeftTapArea)

        // Invisible resize grab area (bottom-right). Drag resizes (dashed
        // preview); a plain tap arms the title-bar gesture.
        val resizeHandle = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END)
            setOnTouchListener(resizeTouchListener)
        }
        addView(resizeHandle)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(disarmRunnable)
        super.onDetachedFromWindow()
    }

    // Cached reflection for input injection (hidden APIs).
    private val injectMethod by lazy {
        runCatching {
            InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            )
        }.getOrNull()
    }
    private val setDisplayIdMethod by lazy {
        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    /**
     * Forward a touch on the TextureView into the VirtualDisplay. The view
     * fills the window 1:1 with the display, so coordinates map directly;
     * only the displayId needs overriding.
     */
    private fun forwardToDisplay(event: MotionEvent) {
        val displayId = host.displayIdForWindow(windowId) ?: return
        if (displayId < 0) return
        val inject = injectMethod ?: return
        val setDisplayId = setDisplayIdMethod ?: return
        val inputManager = context.getSystemService(InputManager::class.java) ?: return
        val ev = MotionEvent.obtain(event)
        try {
            setDisplayId.invoke(ev, displayId)
            inject.invoke(inputManager, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
        } catch (e: Exception) {
            // Injection failures are non-fatal; the window still works.
        } finally {
            ev.recycle()
        }
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

    /**
     * The view fills its window; the window position/size is owned by the
     * manager via WindowManager params. This just tracks the rect for
     * [currentRect] (drag bookkeeping).
     */
    private var windowRect = Rect()

    fun setWindowRect(rect: Rect) {
        windowRect.set(rect)
    }

    fun currentRect(): Rect = Rect(windowRect)

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
