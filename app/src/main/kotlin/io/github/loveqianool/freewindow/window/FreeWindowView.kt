package io.github.loveqianool.freewindow.window

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.loveqianool.freewindow.input.ImeHelper
import io.github.loveqianool.freewindow.input.ResizeHandler
import io.github.loveqianool.freewindow.input.TouchForwarder
import kotlin.math.hypot

/**
 * A free window: vertical stack of [TitleBar] (outside, on top) + [TextureView]
 * showing the VirtualDisplay content.
 *
 * - Windowed mode: title bar is visible above the content (38dp). The VD
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
    private var contentLayout: LinearLayout? = null
    private var toggleView: View? = null
    private var collapseIconView: TextView? = null
    private var cropOverlayView: CropSelectionOverlayView? = null

    /** Crop region as fractions of the VD (0..1); null = full picture. */
    private var cropFractions: RectF? = null
    /** True while the user is drawing a crop selection (not yet applied). */
    private var cropMode = false
    private var cropSelecting = false
    private var cropStartX = 0f
    private var cropStartY = 0f
    /** Inverse of the display transform, for mapping touches back to the VD. */
    private val inverseCropMatrix = Matrix()
    private var hasCropTransform = false

    private var iconLastRawX = 0f
    private var iconLastRawY = 0f
    private var iconDragging = false
    private val iconTouchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Toggle-zone drag state, same pattern as the collapse icon. */
    private var toggleLastRawX = 0f
    private var toggleLastRawY = 0f
    private var toggleDragging = false
    private val toggleTouchSlop = ViewConfiguration.get(context).scaledTouchSlop

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
        // Cropped display: view coordinates map back to the VD through
        // the inverse transform, so a tap on the cropped picture hits the
        // same spot in the app (not the black bar that used to be there).
        // Transform at event time: the crop cannot change mid-gesture
        // (crop mode intercepts content touches), so this copy stays valid.
        if (hasCropTransform) copy.transform(inverseCropMatrix)
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
        // Same level as the main window (see WindowManager.windowParams):
        // the resize preview stays in the same layer as the window itself,
        // added later so it still draws above it during a drag.
        android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR,
        object : ResizeHandler.Callback {
            override fun currentRect(): android.graphics.Rect = host.currentRect(windowId)
            override fun usableRect(): android.graphics.Rect = host.usableRect(windowId)
            override fun onResizeCommit(rect: android.graphics.Rect) =
                host.onResizeCommit(windowId, rect)
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
                st.setDefaultBufferSize(w, h)
            }

            // Return true: we keep the SurfaceTexture alive; WindowManager
            // releases the Surface on rebuild/close. Returning false lets the
            // framework destroy it while the VD still renders to it (blank).
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        textureView.setOnTouchListener { _, event ->
            if (cropMode) {
                // Selection drag: never forward to the app. Coordinates
                // are TextureView-local, the same space the overlay draws in.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cropSelecting = true
                        cropStartX = event.x
                        cropStartY = event.y
                        cropOverlayView?.selection = RectF(event.x, event.y, event.x, event.y)
                        cropOverlayView?.invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (cropSelecting) {
                            cropOverlayView?.selection = RectF(
                                minOf(cropStartX, event.x),
                                minOf(cropStartY, event.y),
                                maxOf(cropStartX, event.x),
                                maxOf(cropStartY, event.y),
                            )
                            cropOverlayView?.invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        cropSelecting = false
                        val sel = cropOverlayView?.selection
                        val tw = textureView.width
                        val th = textureView.height
                        // A tap (tiny rect) cancels without cropping; a real
                        // selection must cover a meaningful part of the view.
                        if (sel != null && tw > 0 && th > 0 &&
                            sel.width() > tw * 0.1f && sel.height() > th * 0.1f
                        ) {
                            applyCrop(
                                RectF(
                                    (sel.left / tw).coerceIn(0f, 1f),
                                    (sel.top / th).coerceIn(0f, 1f),
                                    (sel.right / tw).coerceIn(0f, 1f),
                                    (sel.bottom / th).coerceIn(0f, 1f),
                                ),
                            )
                        } else {
                            cancelCropMode()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cropSelecting = false
                        cancelCropMode()
                    }
                }
                true
            } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
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
        // TextureView + crop overlay in one frame: the overlay draws in
        // TextureView coordinates, so selection needs no title-bar offset,
        // and collapsing the content hides the overlay with it.
        val contentFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f, // Takes remaining space
            )
        }
        contentFrame.addView(textureView)
        val overlay = CropSelectionOverlayView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        cropOverlayView = overlay
        contentFrame.addView(overlay)
        contentLayout.addView(contentFrame)
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
        //
        // It must also drag: this view sits on top of the title bar, so
        // TitleBar's drag intercept never sees touches that start here,
        // and a plain clickable view swallows the whole gesture — that
        // is why dragging from the top-left corner did nothing. The
        // touch listener below mirrors the collapse icon: a tap toggles
        // the bar, a drag moves the window (same integer-advance rule
        // as TitleBar, so slow drags still move).
        // Capture the id first: inside View.apply, unqualified windowId
        // resolves to View.getWindowId(), not this class's Int property
        // (the same shadowing that broke the collapse icon in CI).
        val toggleId = windowId
        val toggleSize = (48 * density).toInt()
        val toggleView = View(context).apply {
            isClickable = true
            contentDescription = "切换标题栏显示"
            // Kept for accessibility: a touch tap is handled by the touch
            // listener (which consumes the stream, so this never fires
            // twice for a touch), while TalkBack's performClick still
            // reaches here.
            setOnClickListener { toggleTitleBar() }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        toggleLastRawX = event.rawX
                        toggleLastRawY = event.rawY
                        toggleDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - toggleLastRawX
                        val dy = event.rawY - toggleLastRawY
                        if (!toggleDragging && hypot(dx, dy) > toggleTouchSlop) {
                            toggleDragging = true
                        }
                        if (toggleDragging) {
                            val idx = dx.toInt()
                            val idy = dy.toInt()
                            host.onMove(toggleId, idx, idy)
                            toggleLastRawX += idx
                            toggleLastRawY += idy
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!toggleDragging) toggleTitleBar()
                        toggleDragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        toggleDragging = false
                        true
                    }
                    else -> true
                }
            }
        }
        addView(
            toggleView,
            LayoutParams(toggleSize, toggleSize).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )

        // Keep field references for setCollapsed(): the local views above
        // are the same instances; the toggle is hidden while collapsed so
        // it cannot swallow taps meant for the collapse icon.
        this.contentLayout = contentLayout
        this.toggleView = toggleView

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

    override fun onDetachedFromWindow() {
        // A resize drag interrupted by detach (window closed, or the
        // bring-to-front re-add) never delivers UP, so the preview system
        // window would otherwise be left on screen. Detach is also where
        // the touch handler's queued events are dropped (parent == null),
        // so cleaning the preview here is consistent with that.
        resizeHandler.cleanup()
        super.onDetachedFromWindow()
    }

    /**
     * Rotation (or any configuration change) changes the usable area, but
     * window rects are stored in old screen coordinates: without a
     * re-clamp, a title bar can end up off-screen and undraggable.
     * Attached system windows receive this dispatch from ViewRootImpl.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        host.onConfigurationChanged(windowId)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Window resizes / maximize / restore change the TextureView size;
        // the crop is stored as fractions, so re-derive the transform here
        // instead of letting it go stale. setTransform() does not trigger
        // layout, so this cannot loop.
        if (cropFractions != null) updateCropTransform()
    }

    fun isCropped(): Boolean = cropFractions != null

    fun isCropMode(): Boolean = cropMode

    /** Enter selection mode: drag on the content to draw the crop rect. */
    fun enterCropMode() {
        cropMode = true
        titleBar.setCropMode(true)
        cropSelecting = false
        cropOverlayView?.selection = null
        cropOverlayView?.visibility = View.VISIBLE
        Log.i(TAG, "crop mode entered window=$windowId")
    }

    /** Leave selection mode without applying or clearing a crop. */
    fun cancelCropMode() {
        cropMode = false
        titleBar.setCropMode(false)
        cropSelecting = false
        cropOverlayView?.selection = null
        cropOverlayView?.visibility = View.GONE
    }

    /** Clear the crop and restore the full picture. */
    fun resetCrop() {
        cropFractions = null
        hasCropTransform = false
        textureView.setTransform(Matrix())
        titleBar.setCropped(false)
        cancelCropMode()
        Log.i(TAG, "crop reset window=$windowId")
    }

    private fun applyCrop(fractions: RectF) {
        cropFractions = fractions
        titleBar.setCropped(true)
        cancelCropMode()
        updateCropTransform()
        Log.i(TAG, "crop applied window=$windowId fractions=$fractions")
    }

    /**
     * Scale the cropped region to fill the TextureView with a uniform
     * scale (no distortion): scale = max(viewW/cropW, viewH/cropH), so the
     * crop's center lands on the view's center and the excess in the other
     * dimension is clipped by the view bounds. That is the video-style
     * "zoom to fill": black bars go, a little of the sides may go with
     * them if the crop aspect differs from the window aspect.
     */
    private fun updateCropTransform() {
        val crop = cropFractions ?: return
        val tw = textureView.width
        val th = textureView.height
        if (tw <= 0 || th <= 0) return
        val cropW = (crop.right - crop.left) * tw
        val cropH = (crop.bottom - crop.top) * th
        if (cropW <= 0 || cropH <= 0) return
        val scale = maxOf(tw / cropW, th / cropH)
        val centerX = (crop.left + crop.right) / 2f * tw
        val centerY = (crop.top + crop.bottom) / 2f * th
        val matrix = Matrix().apply {
            setTranslate(-centerX, -centerY)
            postScale(scale, scale)
            postTranslate(tw / 2f, th / 2f)
        }
        hasCropTransform = matrix.invert(inverseCropMatrix)
        textureView.setTransform(matrix)
    }

    /** Toggles title bar visibility (top-left single tap, both modes). */
    fun toggleTitleBar() {
        // Hiding the bar removes the crop button; a selection in progress
        // would be left without a way out, so cancel the mode (not the crop).
        cancelCropMode()
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
        cancelCropMode()
        titleBar.setMaximized(maximized)
        titleBar.visibility = if (maximized) View.GONE else View.VISIBLE
        resizeHandleView?.visibility = if (maximized) View.GONE else View.VISIBLE
        // Content size changed (bar gone/shown); host resizes the VD.
        host.onContentSizeChanged(windowId)
    }

    /**
     * Collapse to just the icon, or restore the full window.
     *
     * Collapsing only swaps visibility: the TextureView (and its VD) is
     * untouched, so the app keeps its layout and does not re-layout to a
     * 48dp window. The host shrinks the system window rect to the icon
     * size without resizing the VD, and restores the saved rect on expand.
     * Collapse is only reachable from a windowed title bar, so expanding
     * always returns to a windowed window (title bar visible, handle on).
     */
    fun setCollapsed(collapsed: Boolean) {
        // Collapsing hides the content; a selection in progress would be
        // left dangling, so cancel the mode (the applied crop, if any, stays).
        if (collapsed) cancelCropMode()
        if (collapsed) {
            ensureCollapseIcon()
            contentLayout?.visibility = View.GONE
            resizeHandleView?.visibility = View.GONE
            toggleView?.visibility = View.GONE
            collapseIconView?.visibility = View.VISIBLE
        } else {
            collapseIconView?.visibility = View.GONE
            contentLayout?.visibility = View.VISIBLE
            resizeHandleView?.visibility = View.VISIBLE
            toggleView?.visibility = View.VISIBLE
        }
        Log.i(TAG, "collapsed window=$windowId collapsed=$collapsed")
    }

    private fun ensureCollapseIcon() {
        if (collapseIconView != null) return
        // Capture first: inside TextView.apply, unqualified `windowId`
        // resolves to View.getWindowId() (android.view.WindowId), not this
        // class's Int property — that shadowing was the CI error at the
        // host.onMove / host.onExpand calls below (WindowId! vs Int).
        val id = windowId
        val icon = TextView(context).apply {
            text = "▲"
            contentDescription = "点击还原窗口"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        iconLastRawX = event.rawX
                        iconLastRawY = event.rawY
                        iconDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - iconLastRawX
                        val dy = event.rawY - iconLastRawY
                        if (!iconDragging && hypot(dx, dy) > iconTouchSlop) {
                            iconDragging = true
                        }
                        if (iconDragging) {
                            // Same integer-advance rule as TitleBar: do not
                            // discard the sub-pixel remainder on slow drags.
                            val idx = dx.toInt()
                            val idy = dy.toInt()
                            host.onMove(id, idx, idy)
                            iconLastRawX += idx
                            iconLastRawY += idy
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Tap (no drag) restores; a drag just ends here.
                        if (!iconDragging) host.onExpand(id)
                        iconDragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        iconDragging = false
                        true
                    }
                    else -> true
                }
            }
        }
        collapseIconView = icon
        addView(
            icon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
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

        /** Collapsed window size in dp: just the icon, a 48dp touch target. */
        const val COLLAPSED_DP = 48
    }
}

/**
 * Dashed cyan selection rectangle drawn over the content during crop
 * mode. Draw-only: it never consumes touches (not clickable), so the
 * drag still reaches the TextureView below it, and it only invalidates
 * itself on MOVE (no layout pass per move event).
 */
private class CropSelectionOverlayView(context: Context) : View(context) {
    /** Selection in TextureView coordinates; null = nothing drawn. */
    var selection: RectF? = null

    private val density = context.resources.displayMetrics.density
    private val fillPaint = Paint().apply {
        color = 0x3300E5FF
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        selection?.let {
            canvas.drawRect(it, fillPaint)
            canvas.drawRect(it, strokePaint)
        }
    }
}
