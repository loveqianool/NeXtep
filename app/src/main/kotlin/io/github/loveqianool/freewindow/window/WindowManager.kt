package io.github.loveqianool.freewindow.window

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager as AndroidWindowManager
import android.widget.Toast
import io.github.loveqianool.freewindow.input.ImeHelper
import io.github.loveqianool.freewindow.bridge.RecentsBridge
import io.github.loveqianool.freewindow.server.DisplayEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages free-floating windows backed by VirtualDisplays.
 *
 * Runs in the SystemUI process. Each window is an independent system window
 * ([FreeWindowView]) showing one VirtualDisplay via TextureView.
 *
 * - [openApp]: create window + VirtualDisplay, launch the app onto it.
 * - [parkWindow]: hide the view (INVISIBLE) — the task keeps running on the
 *   VirtualDisplay, so the app believes it is still in the foreground.
 * - [unparkWindow]: show the view again and bring it to front.
 * - [collapseWindow]: shrink the window to a draggable 48dp icon; the VD
 *   keeps its size, so expanding restores the exact window layout.
 * - [expandCollapsed]: restore a collapsed window to its saved rect.
 * - [fitAspect]: resize a window to the app's aspect ratio (initial
 *   windows already follow the app's declared orientation).
 * - [toggleMaximize]: fullscreen the window within the usable display area.
 * - [setMuted]: per-window audio mute via AppOps ([WindowMute]).
 */
object WindowManager : WindowHost {
    private const val TAG = "FreeWindow"
    /** At least this much of a window stays visible, so it can be dragged back. */
    private const val MIN_VISIBLE_DP = 80
    // Title-bar buttons total 4 x 38dp = 152dp (the 48dp top-left toggle
    // overlays the bar, it does not add width), so the minimum must
    // exceed the button total or buttons would overflow a smallest window.
    private const val MIN_SIZE_DP = 200
    /** Each window is a VirtualDisplay + Surface + task; cap the count. */
    const val MAX_WINDOWS = 8

    fun isAtLimit(): Boolean = windows.size >= MAX_WINDOWS

    /** All windows by id. */
    private val windows: MutableMap<Int, WindowRecord> = ConcurrentHashMap()

    // No second display map here: DisplayEngine already tracks the
    // VirtualDisplay per id (create stores it, release removes it), so
    // readers go through displayEngine.get(id) instead of a duplicate map
    // that could drift from it.
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val savedRects = mutableMapOf<Int, Rect>()
    /** Labels rarely change; avoid a PackageManager lookup per window per update. */
    private val labelCache = ConcurrentHashMap<ComponentName, String>()
    /** Declared orientation / aspect per app; same reason, one lookup per app. */
    private val aspectCache = ConcurrentHashMap<ComponentName, AspectInfo>()
    /** Last full window list actually broadcast to Recents; same dedup idea. */
    private var lastRecentsWindows: List<RecentsBridge.WindowInfo>? = null
    /** Window ids with a content-size VD resize already queued. */
    private val pendingContentResize = mutableSetOf<Int>()
    private val zOrder = mutableListOf<Int>()
    private val nextId = AtomicInteger(1)

    private var appContext: Context? = null
    private var wm: AndroidWindowManager? = null
    private var displayEngine: DisplayEngine? = null
    private val handler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        wm = app.getSystemService(AndroidWindowManager::class.java)
        displayEngine = DisplayEngine(app)
        // Aspect/label caches are per-app and never expire on their own:
        // an app update that changes its orientation or maxAspectRatio
        // would otherwise keep the old fit until SystemUI restarts.
        // Best-effort: if registration is refused in this process, the
        // caches simply stay stale until the next restart.
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            // Exported is required for system package broadcasts on
            // API 33+; a spoofed intent could only clear a cache entry,
            // which is re-read on next use, so that is harmless here.
            app.registerReceiver(
                packageChangeReceiver, filter,
                android.content.Context.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.i(TAG, "package change receiver refused: ${it.message}") }
        Log.i(TAG, "WindowManager initialized")
    }

    /** Drops cached per-app info for one package on install/update/remove. */
    private val packageChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            aspectCache.keys.filter { it.packageName == pkg }.forEach { aspectCache.remove(it) }
            labelCache.keys.filter { it.packageName == pkg }.forEach { labelCache.remove(it) }
            Log.i(TAG, "package changed, cache cleared pkg=$pkg")
        }
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Open [componentName] in a new free window. Returns the window id.
     * The VirtualDisplay is created once the TextureView surface is ready;
     * the app is then launched onto that display.
     */
    fun openApp(context: Context, componentName: ComponentName): Int {
        val app = context.applicationContext
        init(app)
        val wmRef = wm ?: run {
            Log.w(TAG, "openApp: WindowManager unavailable")
            return -1
        }
        if (isAtLimit()) {
            Log.w(TAG, "openApp: at window limit ($MAX_WINDOWS)")
            return -1
        }
        val id = nextId.getAndIncrement()
        // Clamp like move/resize do: the default rect is computed from
        // raw screen pixels and could otherwise sit under a system bar.
        // The default rect follows the app's declared orientation, so a
        // landscape app does not open in a portrait window (which would
        // letterbox it with side bars from the first frame).
        val rect = clampRect(defaultRect(app, componentName), usableRect(app), app.resources.displayMetrics.density)
        val view = FreeWindowView(app, id, this)
        view.onSurfaceReady = { surface -> onSurfaceAvailable(id, surface) }
        // addView must not throw into the Recents-menu click handler: an
        // uncaught exception on SystemUI's main thread kills SystemUI
        // and with it every free window. Fail with -1 instead, and let
        // the caller tell the user (the menu does so on -1).
        val added = runCatching { wmRef.addView(view, windowParams(rect)) }.isSuccess
        if (!added) {
            Log.i(TAG, "openApp id=$id: addView failed")
            return -1
        }
        synchronized(zOrder) { zOrder.add(id) }
        // If the same package already has a muted window, inherit its state.
        // (The unmute sync for a fresh package happens in onSurfaceAvailable,
        // after the display is created and the receiver is registered.)
        val pkg = componentName.packageName
        val inherited = windows.values.any { it.isMuted && it.componentName.packageName == pkg }
        if (inherited) {
            view.setMuted(true)
        }
        windows[id] = WindowRecord(
            id = id,
            view = view,
            displayId = -1,
            taskId = -1,
            componentName = componentName,
            isParked = false,
            isMaximized = false,
            isMuted = inherited,
        )
        // Recents needs the new card right away, so broadcast explicitly
        // (notifyRecents dedups on the full list, and a new window
        // changes it, so this is never skipped).
        notifyRecents()
        Log.i(TAG, "openApp id=$id component=$componentName rect=$rect")
        return id
    }

    fun closeWindow(id: Int) {
        val rec = windows.remove(id) ?: return
        synchronized(zOrder) { zOrder.remove(id) }
        synchronized(savedRects) { savedRects.remove(id) }
        // AppOps mute is persistent; if this was the last muted window for the
        // package, restore audio so the app isn't silently muted after close.
        if (rec.isMuted) {
            val pkg = rec.componentName.packageName
            val stillMuted = windows.values.any { it.isMuted && it.componentName.packageName == pkg }
            if (!stillMuted) {
                appContext?.let { WindowMute.setMuted(it, pkg, false) }
            }
        }
        runCatching { wm?.removeView(rec.view) }
        rec.view.releaseSurfaceTexture()
        surfaces.remove(id)?.let { runCatching { it.release() } }
        displayEngine?.release(id)
        notifyRecents()
        Log.i(TAG, "closeWindow id=$id")
    }

    /**
     * Collapse the window to just its icon (48dp, top-left of the old
     * window). Only visibility and the system-window rect change: the VD
     * is deliberately NOT resized to the icon size, or the app would
     * re-layout to 48dp and lose its window layout on restore. The saved
     * rect is the same slot maximize uses (a collapsed window can never
     * be maximized at the same time, so they cannot clash).
     */
    fun collapseWindow(id: Int) {
        val rec = windows[id] ?: return
        if (rec.isCollapsed || rec.isMaximized) return
        val app = appContext ?: return
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        val usable = usableRect(app)
        val density = app.resources.displayMetrics.density
        val size = (FreeWindowView.COLLAPSED_DP * density).toInt()
        synchronized(savedRects) { savedRects[id] = currentRect(rec.view) }
        rec.view.setCollapsed(true)
        // Position-only clamp: clampRect() would force the minimum size
        // size and blow the icon back up to a window.
        params.width = size
        params.height = size
        params.x = params.x.coerceIn(usable.left, (usable.right - size).coerceAtLeast(usable.left))
        params.y = params.y.coerceIn(usable.top, (usable.bottom - size).coerceAtLeast(usable.top))
        wm?.updateViewLayout(rec.view, params)
        windows[id] = rec.copy(isCollapsed = true)
        Log.i(TAG, "collapseWindow id=$id icon=(${params.x},${params.y})")
    }

    /** Restore a collapsed window to its saved rect and bring it to front. */
    fun expandCollapsed(id: Int) {
        val rec = windows[id] ?: return
        if (!rec.isCollapsed) return
        val app = appContext ?: return
        val rect = synchronized(savedRects) { savedRects.remove(id) } ?: defaultRect(app, rec.componentName)
        // Expand the view first: applyRect() sizes the VD from the view's
        // title-bar visibility, which is only correct once the content is
        // back (a collapsed view would size the VD to the icon).
        rec.view.setCollapsed(false)
        // A collapsed window can also be parked (park hides the root view
        // but keeps the collapse state); expanding must clear both, or the
        // restored window would stay INVISIBLE and still list as background.
        rec.view.visibility = View.VISIBLE
        windows[id] = rec.copy(isCollapsed = false, isParked = false)
        applyRect(id, rect)
        bringToFront(id)
        // bringToFront may have closed the window on re-add failure.
        if (windows[id] == null) return
        notifyRecents()
        Log.i(TAG, "expandCollapsed id=$id rect=$rect")
    }

    /**
     * Resize the window to the app's aspect ratio, keeping the top-left
     * corner and the window's long side, shrinking only the short side.
     * Shrinking (not growing) keeps the window inside the usable area;
     * applyRect() still clamps it (the minimum size may soften the ratio on
     * very small windows, which is better than overflowing the screen).
     *
     * Target ratio (long / short): the app's declared maxAspectRatio when
     * present — that is exactly the ratio the system letterboxes to, so
     * matching it removes the bars — otherwise the device screen aspect.
     * Direction: the app's declared orientation when present, otherwise
     * the window's current direction. A maximized or collapsed window is
     * restored first (the fit button only exists on a windowed title bar,
     * but Recents-restore callers could reach here in either state).
     */
    fun fitAspect(id: Int) {
        val rec0 = windows[id] ?: return
        if (rec0.isCollapsed) expandCollapsed(id)
        if (windows[id]?.isMaximized == true) toggleMaximize(id)
        val rec = windows[id] ?: return
        if (rec.isCollapsed || rec.isMaximized) return
        // Resizing under a selection would leave the overlay in old
        // coordinates; cancel the mode (the applied crop, if any, stays
        // and is re-derived from fractions on the new size).
        rec.view.cancelCropMode()
        val app = appContext ?: return
        val aspect = aspectFor(rec.componentName)
        val rect = currentRect(rec.view)
        val longPx = maxOf(rect.width(), rect.height())
        val ratio = aspect?.ratio ?: screenAspect(app)
        val shortPx = (longPx / ratio).toInt()
        val landscape = aspect?.landscape ?: (rect.width() >= rect.height())
        val fitted = if (landscape) {
            Rect(rect.left, rect.top, rect.left + longPx, rect.top + shortPx)
        } else {
            Rect(rect.left, rect.top, rect.left + shortPx, rect.top + longPx)
        }
        applyRect(id, fitted)
        Log.i(TAG, "fitAspect id=$id rect=$rect fitted=$fitted ratio=$ratio source=${aspect?.ratioSource ?: "screenAspect"} landscape=$landscape")
    }

    /** Hide the window; the app keeps running on its VirtualDisplay. */
    fun parkWindow(id: Int) {
        val rec = windows[id] ?: return
        if (rec.isParked) return
        // A selection in progress would otherwise survive hidden and
        // intercept content touches on unpark; the applied crop stays.
        rec.view.cancelCropMode()
        rec.view.visibility = View.INVISIBLE
        windows[id] = rec.copy(isParked = true)
        notifyRecents()
        Log.i(TAG, "parkWindow id=$id")
    }

    /** Show a parked window again and bring it to front. */
    fun unparkWindow(id: Int) {
        val rec = windows[id] ?: return
        rec.view.visibility = View.VISIBLE
        bringToFront(id)
        // bringToFront may have closed the window (re-add failure): do not
        // resurrect the removed record via the rec fallback below.
        val current = windows[id] ?: return
        windows[id] = current.copy(isParked = false)
        notifyRecents()
        Log.i(TAG, "unparkWindow id=$id")
    }

    fun toggleMaximize(id: Int) {
        // Maximizing a collapsed window must expand it first: applying
        // the fullscreen rect to a collapsed view would leave a fullscreen
        // icon (and a VD sized from the collapsed view).
        if (windows[id]?.isCollapsed == true) expandCollapsed(id)
        val rec = windows[id] ?: return
        val app = appContext ?: return
        if (rec.isMaximized) {
            val rect = synchronized(savedRects) { savedRects.remove(id) } ?: defaultRect(app, rec.componentName)
            applyRect(id, rect)
            rec.view.setMaximized(false)
            windows[id] = rec.copy(isMaximized = false)
            Log.i(TAG, "restored id=$id rect=$rect")
        } else {
            maximizeWindow(id)
        }
        notifyRecents()
    }

    /** Maximize the window (no-op if already maximized). */
    fun maximizeWindow(id: Int) {
        val rec = windows[id] ?: return
        if (rec.isMaximized) return
        val app = appContext ?: return
        synchronized(savedRects) { savedRects[id] = currentRect(rec.view) }
        val rect = usableRect(app)
        applyRect(id, rect)
        rec.view.setMaximized(true)
        windows[id] = rec.copy(isMaximized = true)
        Log.i(TAG, "maximized id=$id rect=$rect")
    }

    /** Clear a window's crop without expanding or unparking it. */
    fun resetCrop(id: Int) {
        windows[id]?.view?.resetCrop()
    }

    fun setMuted(id: Int, muted: Boolean) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        val pkg = rec.componentName.packageName
        // AppOps mute is per-package, not per-window: sync every window of
        // this package, or a sibling window would show 🔊 while actually
        // muted, and closing the muted window would clear the package mute
        // (closeWindow checks the other windows' isMuted flags).
        WindowMute.setMuted(app, pkg, muted)
        windows.values.filter { it.componentName.packageName == pkg }.forEach { w ->
            windows[w.id] = w.copy(isMuted = muted)
            w.view.setMuted(muted)
        }
        Log.i(TAG, "setMuted id=$id muted=$muted")
    }

    /**
     * "Go to this window", the unified Recents-card-tap semantics:
     * a parked window is shown, and a maximized one is additionally restored
     * to its windowed size (a bare unpark on a maximized window is a no-op
     * when it is already on top).
     */
    fun restoreWindow(id: Int) {
        val rec = windows[id] ?: return
        // Recents-card tap on a collapsed window restores it;
        // a bare unpark would only show the icon again, not the window.
        if (rec.isCollapsed) {
            expandCollapsed(id)
            return
        }
        if (rec.isMaximized) {
            unparkWindow(id)
            toggleMaximize(id)
        } else {
            unparkWindow(id)
        }
    }

    /**
     * Window id for an already-open [componentName], most-recent first.
     * Exact ComponentName match first; if the window was opened via a
     * different entry point of the same app (activity-alias, a second
     * launcher entry), fall back to the most recent window of the same
     * package, so a Recents-menu tap restores instead of opening a
     * duplicate window for an app that is already windowed.
     */
    fun findWindowId(componentName: ComponentName): Int? {
        return synchronized(zOrder) {
            val reversed = zOrder.asReversed()
            reversed.firstOrNull { windows[it]?.componentName == componentName }
                ?: reversed.firstOrNull {
                    windows[it]?.componentName?.packageName == componentName.packageName
                }
        }
    }

    // ------------------------------------------------------------ WindowHost

    override fun usableRect(id: Int): Rect {
        val app = appContext ?: return Rect()
        return usableRect(app)
    }

    override fun onConfigurationChanged(id: Int) {
        reclampWindow(id)
    }

    private fun reclampWindow(id: Int) {
        val app = appContext ?: return
        val rec = windows[id] ?: return
        if (rec.isCollapsed) {
            reclampCollapsed(id)
        } else if (rec.isMaximized) {
            applyRect(id, usableRect(app))
        } else {
            applyRect(id, currentRect(rec.view))
        }
    }

    /** Rotation safety net for a collapsed icon: move it back in bounds only. */
    private fun reclampCollapsed(id: Int) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        val usable = usableRect(app)
        params.x = params.x.coerceIn(
            usable.left, (usable.right - params.width).coerceAtLeast(usable.left),
        )
        params.y = params.y.coerceIn(
            usable.top, (usable.bottom - params.height).coerceAtLeast(usable.top),
        )
        wm?.updateViewLayout(rec.view, params)
    }

    override fun onTap(id: Int) = bringToFront(id)

    override fun onCollapse(id: Int) = collapseWindow(id)

    override fun onFitAspect(id: Int) = fitAspect(id)

    override fun onCrop(id: Int) {
        val rec = windows[id] ?: return
        val view = rec.view
        when {
            // Cropped: one tap restores the full picture.
            view.isCropped() -> view.resetCrop()
            // Selecting: one tap cancels the mode without cropping.
            view.isCropMode() -> view.cancelCropMode()
            // Normal: enter selection mode; the hint explains the gesture,
            // since a crop mode with no visible affordance is undiscoverable.
            else -> {
                view.enterCropMode()
                appContext?.let {
                    Toast.makeText(it, "拖动框选要保留的画面，松手即裁剪；菜单里可恢复", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Log.i(TAG, "onCrop id=$id cropped=${view.isCropped()} mode=${view.isCropMode()}")
    }

    override fun onExpand(id: Int) = expandCollapsed(id)

    override fun onPark(id: Int) = parkWindow(id)

    override fun onToggleMaximize(id: Int) = toggleMaximize(id)

    override fun onToggleMute(id: Int) {
        val rec = windows[id] ?: return
        setMuted(id, !rec.isMuted)
    }

    override fun onClose(id: Int) = closeWindow(id)

    override fun onMove(id: Int, dx: Int, dy: Int) {
        val rec = windows[id] ?: return
        if (rec.isMaximized) return
        val app = appContext ?: return
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        // Hot path (MOVE fires at touch rate): usableRect() queries
        // WindowMetrics, so compute it once here for the clamp.
        val usable = usableRect(app)
        if (rec.isCollapsed) {
            // Icon drag: position only, keep the 48dp size. The normal
            // clamp below would force the minimum size and re-inflate it.
            params.x = (params.x + dx).coerceIn(
                usable.left, (usable.right - params.width).coerceAtLeast(usable.left),
            )
            params.y = (params.y + dy).coerceIn(
                usable.top, (usable.bottom - params.height).coerceAtLeast(usable.top),
            )
            wm?.updateViewLayout(rec.view, params)
            return
        }
        val density = app.resources.displayMetrics.density
        params.x += dx
        params.y += dy
        val clamped = clampRect(
            Rect(params.x, params.y, params.x + params.width, params.y + params.height),
            usable,
            density,
        )
        params.x = clamped.left
        params.y = clamped.top
        wm?.updateViewLayout(rec.view, params)
    }

    override fun onResizeCommit(id: Int, rect: android.graphics.Rect) {
        Log.i(TAG, "onResizeCommit id=$id rect=$rect")
        val rec = windows[id] ?: run {
            Log.i(TAG, "onResizeCommit: no rec for id=$id")
            return
        }
        if (rec.isMaximized || rec.isCollapsed) {
            Log.i(TAG, "onResizeCommit: maximized/collapsed, skip")
            return
        }
        val app = appContext ?: return
        val clamped = clampRect(rect, usableRect(app), app.resources.displayMetrics.density)
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: run {
            Log.i(TAG, "onResizeCommit: bad layoutParams")
            return
        }
        Log.i(TAG, "onResizeCommit: old=${params.width}x${params.height} new=${clamped.width()}x${clamped.height()}")
        params.width = clamped.width()
        params.height = clamped.height()
        params.x = clamped.left
        params.y = clamped.top
        val wmRef = wm
        if (wmRef == null) {
            Log.i(TAG, "onResizeCommit: wm is NULL!")
        } else {
            wmRef.updateViewLayout(rec.view, params)
            rec.view.requestLayout()
        }
        // Resize the VirtualDisplay in place (no rebuild, no task move).
        val vd = displayEngine?.get(id)
        if (vd != null) {
            val app = appContext
            val dpi = if (app != null) densityDpi(app) else 440
            val (vdW, vdH) = vdSizeForWindow(clamped, rec.view)
            runCatching {
                vd.resize(vdW, vdH, dpi)
                Log.i(TAG, "onResizeCommit: vd resized to ${vdW}x${vdH}")
            }.onFailure {
                Log.i(TAG, "onResizeCommit: vd.resize failed: ${it.message}")
            }
        } else {
            Log.i(TAG, "onResizeCommit: no vd for id=$id")
        }
    }

    override fun currentRect(id: Int): android.graphics.Rect {
        val rec = windows[id] ?: return android.graphics.Rect()
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams
            ?: return android.graphics.Rect()
        return android.graphics.Rect(params.x, params.y,
            params.x + params.width, params.y + params.height)
    }

    override fun onContentSizeChanged(id: Int) {
        val rec = windows[id] ?: return
        // Never resize the VD while collapsed: contentSize() of a 48dp
        // icon window would shrink the VD to the icon.
        if (rec.isCollapsed) return
        val app = appContext ?: return
        // Coalesce: rapid toggles (title bar show/hide + maximize) used to
        // queue one VD resize per call. A pending resize reads contentSize()
        // at execution time, so a second call before it runs is already
        // covered by the first — skip it instead of queueing another.
        if (!pendingContentResize.add(id)) return
        // Post to let the layout settle; contentSize() reads measured dims.
        handler.post {
            pendingContentResize.remove(id)
            val content = rec.view.contentSize()
            val vd = displayEngine?.get(id) ?: return@post
            runCatching {
                vd.resize(content.width(), content.height(), densityDpi(app))
                Log.i(TAG, "onContentSizeChanged id=$id vd=${content.width()}x${content.height()}")
            }.onFailure {
                Log.i(TAG, "onContentSizeChanged resize failed id=$id: ${it.message}")
            }
        }
    }

    // ------------------------------------------------------------ internals

    private fun onSurfaceAvailable(id: Int, surface: Surface) {
        val rec = windows[id] ?: run {
            runCatching { surface.release() }
            return
        }
        // Surface recreated (e.g. after bring-to-front re-add): rebuild the
        // VirtualDisplay on the new surface and move the task back.
        if (surfaces.containsKey(id)) {
            rebuildDisplay(id, surface)
            return
        }
        val app = appContext ?: return
        surfaces[id] = surface
        val rect = currentRect(rec.view)
        // VD matches the content area (window minus title bar).
        val (vdW, vdH) = vdSizeForWindow(rect, rec.view)
        val vd = displayEngine?.create(id, vdW, vdH, densityDpi(app), surface)
        if (vd == null) {
            Log.i(TAG, "openApp id=$id: display creation failed")
            // Let closeWindow release the surface; removing it here first
            // would make closeWindow's release a no-op and leak it.
            closeWindow(id)
            return
        }
        val displayId = vd.display.displayId
        rec.view.displayId = displayId
        windows[id] = rec.copy(displayId = displayId)
        launchOnDisplay(app, rec.componentName, displayId)
        // Sync mute state now that the display exists (receiver is registered
        // via the display hook). AppOps mute is persistent; clear any leftover
        // from a previous session unless another window of the same package
        // is currently muted. Checking only this window is wrong: a sibling
        // window muted after this one opened would have its mute cleared.
        val pkg = rec.componentName.packageName
        val anyMuted = windows.values.any { it.componentName.packageName == pkg && it.isMuted }
        if (!anyMuted) {
            WindowMute.setMuted(app, pkg, false)
        }
        Log.i(TAG, "openApp id=$id launched on display=$displayId")
        handler.postDelayed({ resolveTaskId(id) }, 500)
    }

    private fun rebuildDisplay(id: Int, surface: Surface) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        // Keep the old display alive while moving the task; releasing it first
        // kills the task (Unknown rootTaskId).
        val oldVd = displayEngine?.get(id)
        val oldTaskId = rec.taskId
        // If the size changed (resize/maximize), the moved task won't re-layout;
        // relaunch instead. Only move when the size is the same (surface recreate).
        // Check both dimensions: a height-only change (vertical resize with
        // unchanged width) must also relaunch, or the task keeps the old layout.
        val oldSize = oldVd?.display?.let { d ->
            runCatching {
                val metrics = android.util.DisplayMetrics()
                d.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }.getOrNull()
        } ?: (-1 to -1)
        val oldW = oldSize.first
        val oldH = oldSize.second
        surfaces[id]?.let { runCatching { it.release() } }
        surfaces[id] = surface
        val rect = currentRect(rec.view)
        val (vdW, vdH) = vdSizeForWindow(rect, rec.view)
        // oldH is the old VD's own height (already content-only, like vdH:
        // title-bar toggles resize the VD in place, so it already reflects
        // the current bar visibility). Compare VD sizes directly.
        val sizeChanged = (oldW > 0 && oldW != vdW) || (oldH > 0 && oldH != vdH)
        val vd = displayEngine?.create(
            id, vdW, vdH, densityDpi(app), surface,
            releaseFirst = false,
        )
        if (vd == null) {
            Log.i(TAG, "rebuildDisplay id=$id failed")
            closeWindow(id)
            return
        }
        val newDisplayId = vd.display.displayId
        rec.view.displayId = newDisplayId
        windows[id] = rec.copy(displayId = newDisplayId)
        var moved = false
        if (!sizeChanged && oldTaskId >= 0) {
            moved = moveTaskToDisplay(oldTaskId, newDisplayId)
            if (moved) {
                Log.i(TAG, "rebuildDisplay id=$id moved task=$oldTaskId to display=$newDisplayId")
            }
        } else if (sizeChanged) {
            Log.i(TAG, "rebuildDisplay id=$id size changed, relaunching instead of move")
        }
        // Now safe to release the old display; the task is gone from it.
        oldVd?.let { runCatching { it.release() } }
        if (!moved) {
            Log.i(TAG, "rebuildDisplay id=$id: move failed, relaunching")
            // Forget the old task id first: resolveTaskId() returns early
            // while taskId >= 0, so without this it would never learn the
            // new task and every later bring-to-front would move-then-relaunch.
            // Only if the window still exists; do not re-add a closed window.
            windows[id]?.let { windows[id] = it.copy(taskId = -1) }
            launchOnDisplay(app, rec.componentName, newDisplayId)
            handler.postDelayed({ resolveTaskId(id) }, 500)
        }
    }

    private fun launchOnDisplay(app: Context, componentName: ComponentName, displayId: Int) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = componentName
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        app.startActivity(intent, opts)
    }

    private fun bringToFront(id: Int) {
        val rec = windows[id] ?: return
        val wm = wm ?: return
        val top = synchronized(zOrder) { zOrder.lastOrNull() }
        if (top == id) return
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        // Re-adding changes z-order. The TextureView surface is recreated and
        // onSurfaceAvailable() rebuilds the VirtualDisplay (task is moved back).
        val bringOk = runCatching {
            wm.removeView(rec.view)
            wm.addView(rec.view, params)
        }.isSuccess
        if (!bringOk) {
            // Do not leave a detached orphan (record exists, view gone):
            // try to put the view back once; if that also fails, close
            // the window cleanly instead of leaving an unopenable entry.
            val recovered = runCatching { wm.addView(rec.view, params) }.isSuccess
            if (!recovered) {
                Log.i(TAG, "bringToFront failed id=$id, closing window")
                closeWindow(id)
                return
            }
            Log.i(TAG, "bringToFront recovered id=$id")
        }
        synchronized(zOrder) {
            zOrder.remove(id)
            zOrder.add(id)
        }
        Log.i(TAG, "bringToFront id=$id")
    }

    private fun applyRect(id: Int, rect: Rect) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        // Clamp here, not only in callers: the restore fallback (defaultRect
        // when no saved rect exists) used to bypass the clamp and could sit
        // under a system bar. Clamping an already-clamped rect is a no-op.
        val clamped = clampRect(rect, usableRect(app), app.resources.displayMetrics.density)
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        params.x = clamped.left
        params.y = clamped.top
        params.width = clamped.width()
        params.height = clamped.height()
        wm?.updateViewLayout(rec.view, params)
        // Defensive: applyRect() on a collapsed view must never shrink
        // the VD to the icon size (expandCollapsed clears the flag first,
        // so a correct call never reaches here collapsed).
        if (rec.isCollapsed) return
        displayEngine?.get(id)?.let { vd ->
            val (vdW, vdH) = vdSizeForWindow(clamped, rec.view)
            runCatching { vd.resize(vdW, vdH, densityDpi(app)) }
                .onFailure { Log.i(TAG, "resize failed id=$id: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ tasks

    // Cold starts can take longer than the old 8 x 400ms (~3.7s with the
    // initial 500ms delay); 30 attempts covers ~12s. Still bounded, so a
    // permanently failed launch does not poll getRunningTasks forever.
    private fun resolveTaskId(id: Int, attempts: Int = 30) {
        val rec = windows[id] ?: return
        if (rec.taskId >= 0 || rec.displayId < 0) return
        val taskId = findTaskOnDisplay(rec.displayId)
        if (taskId >= 0) {
            windows[id] = rec.copy(taskId = taskId)
            Log.i(TAG, "resolved task id=$id taskId=$taskId display=${rec.displayId}")
            return
        }
        if (attempts > 0) {
            handler.postDelayed({ resolveTaskId(id, attempts - 1) }, 400)
        } else {
            Log.i(TAG, "task not found id=$id display=${rec.displayId}")
        }
    }

    private fun findTaskOnDisplay(displayId: Int): Int {
        return try {
            val am = appContext?.getSystemService(ActivityManager::class.java) ?: return -1
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(64) ?: return -1
            for (info in tasks) {
                if (readDisplayId(info) == displayId) return info.taskId
            }
            -1
        } catch (e: Throwable) {
            Log.i(TAG, "findTaskOnDisplay failed: ${e.message}")
            -1
        }
    }

    /** Cached field/method for displayId; the lookup never changes at runtime. */
    private var displayIdAccessor: java.lang.reflect.AccessibleObject? = null
    private var displayIdAccessorResolved = false

    private fun readDisplayId(info: ActivityManager.RunningTaskInfo): Int? {
        if (!displayIdAccessorResolved) {
            displayIdAccessorResolved = true
            var c: Class<*>? = info.javaClass
            while (c != null) {
                for (name in listOf("displayId", "mDisplayId")) {
                    runCatching {
                        displayIdAccessor = c.getDeclaredField(name).apply { isAccessible = true }
                        return@runCatching
                    }
                    if (displayIdAccessor != null) break
                }
                if (displayIdAccessor != null) break
                c = c.superclass
            }
            if (displayIdAccessor == null) {
                runCatching {
                    displayIdAccessor = info.javaClass.getMethod("getDisplayId")
                        .apply { isAccessible = true }
                }
            }
        }
        return try {
            when (val acc = displayIdAccessor) {
                is java.lang.reflect.Field -> acc.getInt(info)
                is java.lang.reflect.Method -> acc.invoke(info) as? Int
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Cached ATM service + move method; reflection is expensive per call. */
    private val atmMove: Pair<Any, java.lang.reflect.Method>? by lazy {
        try {
            val atm = Class.forName("android.app.ActivityTaskManager")
            val service = atm.getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null) ?: return@lazy null
            // Prefer moveTaskToDisplay; falling back to moveRootTaskToDisplay
            // only if it is absent. The old firstOrNull with an OR picked
            // whichever the JVM happened to declare first on a given ROM.
            val methods = service.javaClass.declaredMethods.filter {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val method = (methods.firstOrNull { it.name == "moveTaskToDisplay" }
                ?: methods.firstOrNull { it.name == "moveRootTaskToDisplay" })
                ?.apply { isAccessible = true } ?: return@lazy null
            service to method
        } catch (e: Throwable) {
            Log.i(TAG, "ATM move method lookup failed: ${e.message}")
            null
        }
    }

    private fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val (service, method) = atmMove ?: run {
            Log.i(TAG, "moveTaskToDisplay: method not available")
            return false
        }
        return try {
            Log.i(TAG, "moveTaskToDisplay: invoking ${method.name} task=$taskId display=$displayId")
            method.invoke(service, taskId, displayId)
            Log.i(TAG, "moveTaskToDisplay task=$taskId display=$displayId via ${method.name}")
            true
        } catch (e: Throwable) {
            Log.i(TAG, "moveTaskToDisplay failed: ${e.javaClass.simpleName}: ${e.message}")
            e.cause?.let { Log.i(TAG, "  caused by: ${it.javaClass.simpleName}: ${it.message}") }
            false
        }
    }

    // ------------------------------------------------------------ geometry

    private fun windowParams(rect: Rect): AndroidWindowManager.LayoutParams {
        return AndroidWindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            // TYPE_SEARCH_BAR (2001) is one level below TYPE_PHONE (2002):
            // still a system window above apps, but a level lower than
            // before. On this device PHONE was observed above the keyboard
            // despite sitting below TYPE_INPUT_METHOD (2011) in theory, so
            // lower it one level so the IME stays visible and tappable
            // above a free window. OVERLAY (2038) would cover it.
            AndroidWindowManager.LayoutParams.TYPE_SEARCH_BAR,
            ImeHelper.DEFAULT_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
            title = "FreeWindow"
            // Don't let focus changes show/hide the IME or pan the window;
            // the IME is requested by the app on the VirtualDisplay.
            softInputMode =
                AndroidWindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                AndroidWindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    private fun defaultRect(app: Context, component: ComponentName? = null): Rect {
        val m = app.resources.displayMetrics
        val shortSide = minOf(m.widthPixels, m.heightPixels)
        val longSide = maxOf(m.widthPixels, m.heightPixels)
        // Portrait default, mirrored for a landscape-declared app: the
        // short side is 72% of the screen's short side, the long side
        // 1.25x that (capped at 80% of the screen's long side).
        val shortPx = (shortSide * 0.72).toInt()
        val longPx = minOf((shortPx * 1.25).toInt(), (longSide * 0.8).toInt())
        val landscape = component?.let { aspectFor(it)?.landscape } == true
        val w = if (landscape) longPx else shortPx
        val h = if (landscape) shortPx else longPx
        val left = (m.widthPixels - w) / 2
        val top = (m.heightPixels - h) / 3
        return Rect(left, top, left + w, top + h)
    }

    /**
     * An app's declared aspect: direction from screenOrientation (null
     * when the app follows the display, e.g. unspecified / sensor), ratio
     * from maxAspectRatio when declared, else the device screen aspect.
     * maxAspectRatio is long / short; a letterboxed app is boxed to
     * exactly that ratio, so it is the fit target when present.
     * Note: maxAspectRatio is NOT in the compileSdk 36.1 stub (CI:
     * "Unresolved reference"), so it is read via reflection like the
     * other hidden framework fields in this codebase; absent at runtime
     * it simply falls back to the screen aspect.
     */
    private data class AspectInfo(
        val landscape: Boolean?,
        val ratio: Float,
        /** "maxAspectRatio" when the app declares one, else "screenAspect". */
        val ratioSource: String,
    )

    private fun aspectFor(component: ComponentName): AspectInfo? {
        aspectCache[component]?.let { return it }
        val app = appContext ?: return null
        val info = runCatching {
            app.packageManager.getActivityInfo(component, 0)
        }.getOrNull() ?: return null
        val landscape = when (info.screenOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            -> true
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            -> false
            else -> null
        }
        val maxRatio = runCatching {
            ActivityInfo::class.java.getDeclaredField("maxAspectRatio")
                .apply { isAccessible = true }
                .getFloat(info)
        }.getOrElse { 0f }
        val ratio = if (maxRatio > 0f) maxRatio else screenAspect(app)
        val source = if (maxRatio > 0f) "maxAspectRatio" else "screenAspect"
        val aspect = AspectInfo(landscape, ratio, source)
        aspectCache[component] = aspect
        return aspect
    }

    /** Device screen aspect, long / short, as a fallback fit ratio. */
    private fun screenAspect(app: Context): Float {
        val m = app.resources.displayMetrics
        val shortSide = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
        return maxOf(m.widthPixels, m.heightPixels).toFloat() / shortSide
    }

    private fun usableRect(app: Context): Rect {
        val wm = app.getSystemService(AndroidWindowManager::class.java) ?: return defaultRect(app)
        // One metrics snapshot: bounds and insets from two separate calls
        // could disagree if the configuration changes between them.
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return Rect(
            bounds.left + insets.left,
            bounds.top + insets.top,
            bounds.right - insets.right,
            bounds.bottom - insets.bottom,
        )
    }

    private fun currentRect(view: View): Rect {
        val params = view.layoutParams as? AndroidWindowManager.LayoutParams
            ?: return defaultRect(view.context)
        return Rect(params.x, params.y, params.x + params.width, params.y + params.height)
    }

    /**
     * Clamp [rect] so the title bar can never leave the usable area: the
     * top stays in bounds (so the 38dp bar is reachable) and at least
     * [MIN_VISIBLE_DP] of width stays on screen on either side. Size is
     * clamped to the usable area and to [MIN_SIZE_DP].
     */
    private fun clampRect(rect: Rect, usable: Rect, density: Float): Rect {
        val minPx = (MIN_SIZE_DP * density).toInt()
        val visiblePx = (MIN_VISIBLE_DP * density).toInt()
        val barPx = (FreeWindowView.TITLE_BAR_DP * density).toInt()
        // coerceIn() throws on an empty range; fall back to keeping a narrow
        // window fully inside when it is smaller than 2 * MIN_VISIBLE_DP.
        val leftLow = usable.left - rect.width() + visiblePx
        val leftHigh = usable.right - visiblePx
        var left = if (leftLow <= leftHigh) {
            rect.left.coerceIn(leftLow, leftHigh)
        } else {
            rect.left.coerceIn(usable.left, (usable.right - rect.width()).coerceAtLeast(usable.left))
        }
        var top = rect.top.coerceIn(usable.top, (usable.bottom - barPx).coerceAtLeast(usable.top))
        var right = minOf(rect.right, usable.right)
        var bottom = minOf(rect.bottom, usable.bottom)
        // Size floor after position clamping (a clamped edge may have shrunk it).
        if (right - left < minPx) right = minOf(left + minPx, usable.right)
        if (bottom - top < minPx) bottom = minOf(top + minPx, usable.bottom)
        if (right - left < minPx) left = maxOf(right - minPx, usable.left)
        if (bottom - top < minPx) top = maxOf(bottom - minPx, usable.top)
        return Rect(left, top, right, bottom)
    }

    /**
     * VD size for a window rect: full width, height minus title bar (if visible).
     * The title bar sits outside the content.
     */
    private fun vdSizeForWindow(windowRect: Rect, view: FreeWindowView): Pair<Int, Int> {
        val barH = if (view.isTitleBarVisible()) {
            (FreeWindowView.TITLE_BAR_DP * view.resources.displayMetrics.density).toInt()
        } else {
            0
        }
        return windowRect.width() to (windowRect.height() - barH).coerceAtLeast(1)
    }

    private fun densityDpi(app: Context): Int = app.resources.displayMetrics.densityDpi

    // ------------------------------------------------------------ recents list

    /** All windows for Recents, topmost first, with full state for cards. */
    fun getRecentsWindows(): List<RecentsBridge.WindowInfo> {
        val app = appContext ?: return emptyList()
        val order = synchronized(zOrder) { zOrder.reversed() }
        return order.mapNotNull { id ->
            windows[id]?.let { rec ->
                RecentsBridge.WindowInfo(
                    windowId = rec.id,
                    label = appLabel(app, rec.componentName),
                    isMaximized = rec.isMaximized,
                    component = rec.componentName,
                    isParked = rec.isParked,
                    isMuted = rec.isMuted,
                    isCollapsed = rec.isCollapsed,
                    isCropped = rec.view.isCropped(),
                )
            }
        }
    }

    /**
     * Broadcast the full window list to Recents (see RecentsBridge).
     * Called on every window-state change, plus explicitly from openApp()
     * (see there). Identical lists are skipped — e.g. restoreWindow()
     * unparks then toggles, and the first step changes nothing here.
     */
    private fun notifyRecents() {
        val app = appContext ?: return
        val list = getRecentsWindows()
        if (list == lastRecentsWindows) return
        lastRecentsWindows = list
        RecentsBridge.broadcastWindows(app, list)
    }

    private fun appLabel(app: Context, component: ComponentName): String {
        return labelCache[component] ?: run {
            val label = runCatching {
                val pm = app.packageManager
                pm.getActivityInfo(component, 0).loadLabel(pm)?.toString()
            }.getOrNull() ?: component.packageName
            labelCache[component] = label
            label
        }
    }
}
