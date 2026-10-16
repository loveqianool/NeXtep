package io.github.loveqianool.freewindow.window

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager as AndroidWindowManager
import io.github.loveqianool.freewindow.input.ImeHelper
import io.github.loveqianool.freewindow.notification.FreeWindowNotification
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
 * - [toggleMaximize]: fullscreen the window within the usable display area.
 * - [setMuted]: per-window audio mute via AppOps ([WindowMute]).
 */
object WindowManager : WindowHost {
    private const val TAG = "FreeWindow"

    /** All windows by id. */
    private val windows: MutableMap<Int, WindowRecord> = ConcurrentHashMap()

    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val savedRects = mutableMapOf<Int, Rect>()
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
        Log.i(TAG, "WindowManager initialized")
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
        val id = nextId.getAndIncrement()
        val rect = defaultRect(app)
        val view = FreeWindowView(app, id, this)
        view.onSurfaceReady = { surface -> onSurfaceAvailable(id, surface) }
        wmRef.addView(view, windowParams(rect))
        synchronized(zOrder) { zOrder.add(id) }
        windows[id] = WindowRecord(
            id = id,
            view = view,
            displayId = -1,
            taskId = -1,
            componentName = componentName,
            isParked = false,
            isMaximized = false,
            isMuted = false,
        )
        Log.i(TAG, "openApp id=$id component=$componentName rect=$rect")
        return id
    }

    fun closeWindow(id: Int) {
        val rec = windows.remove(id) ?: return
        synchronized(zOrder) { zOrder.remove(id) }
        synchronized(savedRects) { savedRects.remove(id) }
        runCatching { wm?.removeView(rec.view) }
        rec.view.releaseSurfaceTexture()
        surfaces.remove(id)?.let { runCatching { it.release() } }
        displays.remove(id)
        displayEngine?.release(id)
        updateNotification()
        Log.i(TAG, "closeWindow id=$id")
    }

    /** Hide the window; the app keeps running on its VirtualDisplay. */
    fun parkWindow(id: Int) {
        val rec = windows[id] ?: return
        if (rec.isParked) return
        rec.view.visibility = View.INVISIBLE
        windows[id] = rec.copy(isParked = true)
        updateNotification()
        Log.i(TAG, "parkWindow id=$id")
    }

    /** Show a parked window again and bring it to front. */
    fun unparkWindow(id: Int) {
        val rec = windows[id] ?: return
        rec.view.visibility = View.VISIBLE
        bringToFront(id)
        windows[id] = windows[id]?.copy(isParked = false) ?: rec.copy(isParked = false)
        updateNotification()
        Log.i(TAG, "unparkWindow id=$id")
    }

    fun toggleMaximize(id: Int) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        if (rec.isMaximized) {
            val rect = synchronized(savedRects) { savedRects.remove(id) } ?: defaultRect(app)
            applyRect(id, rect)
            rec.view.setMaximized(false)
            windows[id] = rec.copy(isMaximized = false)
            Log.i(TAG, "restored id=$id rect=$rect")
        } else {
            synchronized(savedRects) { savedRects[id] = currentRect(rec.view) }
            val rect = usableRect(app)
            applyRect(id, rect)
            rec.view.setMaximized(true)
            windows[id] = rec.copy(isMaximized = true)
            Log.i(TAG, "maximized id=$id rect=$rect")
        }
    }

    fun setMuted(id: Int, muted: Boolean) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        val pkg = rec.componentName.packageName
        val uid = runCatching {
            app.packageManager.getApplicationInfo(pkg, 0).uid
        }.getOrNull()
        if (uid == null) {
            Log.i(TAG, "setMuted id=$id: uid lookup failed for $pkg")
            return
        }
        WindowMute.setMuted(app, pkg, uid, muted)
        windows[id] = rec.copy(isMuted = muted)
        rec.view.setMuted(muted)
        Log.i(TAG, "setMuted id=$id muted=$muted")
    }

    /** Close every window and hide the notification. */
    fun exit() {
        windows.keys.toList().forEach(::closeWindow)
        appContext?.let { FreeWindowNotification.hide(it) }
        Log.i(TAG, "exit")
    }

    // ------------------------------------------------------------ WindowHost

    override fun onTap(id: Int) = bringToFront(id)

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
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        params.x += dx
        params.y += dy
        wm?.updateViewLayout(rec.view, params)
    }

    override fun onResizeCommit(id: Int, rect: android.graphics.Rect) {
        Log.i(TAG, "onResizeCommit id=$id rect=$rect")
        val rec = windows[id] ?: run {
            Log.i(TAG, "onResizeCommit: no rec for id=$id")
            return
        }
        if (rec.isMaximized) {
            Log.i(TAG, "onResizeCommit: maximized, skip")
            return
        }
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: run {
            Log.i(TAG, "onResizeCommit: bad layoutParams")
            return
        }
        Log.i(TAG, "onResizeCommit: old=${params.width}x${params.height} new=${rect.width()}x${rect.height()}")
        params.width = rect.width()
        params.height = rect.height()
        params.x = rect.left
        params.y = rect.top
        val wmRef = wm
        if (wmRef == null) {
            Log.i(TAG, "onResizeCommit: wm is NULL!")
        } else {
            wmRef.updateViewLayout(rec.view, params)
            rec.view.requestLayout()
        }
        // Resize the VirtualDisplay in place (no rebuild, no task move).
        // This is how the main branch does it.
        val vd = displays[id]
        if (vd != null) {
            val app = appContext
            val dpi = if (app != null) densityDpi(app) else 440
            val (vdW, vdH) = vdSizeForWindow(rect, rec.view)
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
        val app = appContext ?: return
        // Post to let the layout settle; contentSize() reads measured dims.
        handler.post {
            val content = rec.view.contentSize()
            val vd = displays[id] ?: return@post
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
            surfaces.remove(id)
            closeWindow(id)
            return
        }
        val displayId = vd.display.displayId
        displays[id] = vd
        rec.view.displayId = displayId
        windows[id] = rec.copy(displayId = displayId)
        launchOnDisplay(app, rec.componentName, displayId)
        Log.i(TAG, "openApp id=$id launched on display=$displayId")
        handler.postDelayed({ resolveTaskId(id) }, 500)
    }

    private fun rebuildDisplay(id: Int, surface: Surface) {
        val rec = windows[id] ?: return
        val app = appContext ?: return
        // Keep the old display alive while moving the task; releasing it first
        // kills the task (Unknown rootTaskId).
        val oldVd = displays[id]
        val oldTaskId = rec.taskId
        // If the size changed (resize/maximize), the moved task won't re-layout;
        // relaunch instead. Only move when the size is the same (surface recreate).
        val oldW = oldVd?.display?.let { d ->
            runCatching {
                val metrics = android.util.DisplayMetrics()
                d.getRealMetrics(metrics)
                metrics.widthPixels
            }.getOrNull()
        } ?: -1
        surfaces[id]?.let { runCatching { it.release() } }
        surfaces[id] = surface
        val rect = currentRect(rec.view)
        val (vdW, vdH) = vdSizeForWindow(rect, rec.view)
        val sizeChanged = oldW > 0 && oldW != vdW
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
        displays[id] = vd
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
        runCatching {
            wm.removeView(rec.view)
            wm.addView(rec.view, params)
        }.onFailure {
            Log.i(TAG, "bringToFront failed id=$id: ${it.message}")
            return
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
        val params = rec.view.layoutParams as? AndroidWindowManager.LayoutParams ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        wm?.updateViewLayout(rec.view, params)
        displays[id]?.let { vd ->
            val (vdW, vdH) = vdSizeForWindow(rect, rec.view)
            runCatching { vd.resize(vdW, vdH, densityDpi(app)) }
                .onFailure { Log.i(TAG, "resize failed id=$id: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ tasks

    private fun resolveTaskId(id: Int, attempts: Int = 8) {
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
            val method = service.javaClass.declaredMethods.firstOrNull {
                (it.name == "moveRootTaskToDisplay" || it.name == "moveTaskToDisplay") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true } ?: return@lazy null
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
            // TYPE_PHONE (2002) sits below TYPE_INPUT_METHOD (2011); the IME
            // stays visible above a maximized window. OVERLAY (2038) would
            // cover it.
            AndroidWindowManager.LayoutParams.TYPE_PHONE,
            ImeHelper.DEFAULT_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
            title = "FreeWindow"
        }
    }

    private fun defaultRect(app: Context): Rect {
        val m = app.resources.displayMetrics
        val w = (minOf(m.widthPixels, m.heightPixels) * 0.72).toInt()
        val h = minOf((w * 1.25).toInt(), (maxOf(m.widthPixels, m.heightPixels) * 0.8).toInt())
        val left = (m.widthPixels - w) / 2
        val top = (m.heightPixels - h) / 3
        return Rect(left, top, left + w, top + h)
    }

    private fun usableRect(app: Context): Rect {
        val wm = app.getSystemService(AndroidWindowManager::class.java) ?: return defaultRect(app)
        val bounds = wm.currentWindowMetrics.bounds
        val insets = wm.currentWindowMetrics.windowInsets.getInsets(
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

    // ------------------------------------------------------------ notification

    fun getParkedApps(): List<FreeWindowNotification.ParkedApp> {
        val app = appContext ?: return emptyList()
        return windows.values
            .filter { it.isParked }
            .map { rec ->
                FreeWindowNotification.ParkedApp(rec.id, appLabel(app, rec.componentName))
            }
    }

    private fun updateNotification() {
        val app = appContext ?: return
        val parked = getParkedApps()
        FreeWindowNotification.update(app, parked)
    }

    private fun appLabel(app: Context, component: ComponentName): String {
        return runCatching {
            val pm = app.packageManager
            pm.getActivityInfo(component, 0).loadLabel(pm)?.toString()
        }.getOrNull() ?: component.packageName
    }
}
