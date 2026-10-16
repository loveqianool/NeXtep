package io.github.lujinxin.nextep.freewindow

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import io.github.lujinxin.nextep.display.SlotGeometry
import io.github.lujinxin.nextep.display.VirtualDisplayFactory
import io.github.lujinxin.nextep.framework.ActivityTaskManagerCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.task.TaskRepository
import kotlin.math.max
import kotlin.math.min

/**
 * Owns free windows in the systemui process.
 *
 * Each window gets its own VirtualDisplay (named "NeXtep-slot-<id>" so the
 * existing system_server insets hooks keep applying), rendered into the
 * window's TextureView. Window rects are free; z-order follows list order.
 *
 * The overlay container, floating ball and app-list panel are owned by
 * FreeWindowSystemUiHook; this manager only manipulates window views and
 * calls [restackOverlay] after any z-order change so the hook can keep
 * panel/ball above windows.
 */
class FreeWindowManager(
    context: Context,
    private val container: FrameLayout,
    /** Screen-space bounds of the floating ball, or null when unknown. */
    private val ballBounds: () -> Rect?,
    /** Re-add panel/ball above window views after z-order changes. */
    private val restackOverlay: () -> Unit,
) : FreeWindowViewHost {

    interface Listener {
        fun onChanged()
    }

    private val appContext = context.applicationContext ?: context
    private val density = appContext.resources.displayMetrics.density
    private val densityDpi = appContext.resources.displayMetrics.densityDpi
    private val handler = Handler(Looper.getMainLooper())
    private val taskRepository = TaskRepository(appContext)

    private data class Record(
        var state: FreeWindowState,
        var view: FreeWindowView? = null,
        var virtualDisplay: VirtualDisplay? = null,
        var preMaxRect: Rect? = null,
        var launchAttempts: Int = 0,
    )

    /** Z-order: later entries are above earlier ones. */
    private val records = mutableListOf<Record>()
    private var nextWindowId = 1
    private var listener: Listener? = null

    fun setListener(value: Listener?) {
        listener = value
    }

    fun windows(): List<FreeWindowState> = records.map { it.state }

    fun parkedCount(): Int = records.count { it.state.mode == FreeWindowMode.PARKED }

    // ------------------------------------------------------------------ launch

    /**
     * Create a window for [component] and launch the app on a fresh
     * VirtualDisplay sized to the default rect. Returns the window id,
     * or -1 when the window cap is reached.
     */
    fun launchApp(component: ComponentName, label: String): Int {
        if (records.size >= MAX_WINDOWS) {
            NeXtepLog.warn("freewindow", "Window cap reached ($MAX_WINDOWS)")
            return -1
        }
        val windowId = nextWindowId++
        val rect = defaultRect()
        val packageName = component.packageName
        val uid = runCatching {
            appContext.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrElse { -1 }
        val record = Record(
            state = FreeWindowState(
                windowId = windowId,
                component = component,
                packageName = packageName,
                uid = uid,
                rect = Rect(rect),
                title = label,
            ),
        )
        val icon = runCatching {
            appContext.packageManager.getActivityIcon(component)
        }.getOrNull()
        val view = FreeWindowView(appContext, windowId, this).apply {
            setAppInfo(icon, label)
            setWindowRect(rect)
            onSurfaceReady = { surface -> onViewSurfaceReady(record, surface) }
        }
        record.view = view
        record.state = record.state.copy(icon = icon)
        records += record
        container.addView(view, layoutParamsFor(rect))
        restackOverlay()
        notifyChanged()
        NeXtepLog.info("freewindow", "Launched window=$windowId component=$component rect=$rect")
        return windowId
    }

    private fun onViewSurfaceReady(record: Record, surface: Surface) {
        if (record.virtualDisplay != null) return
        val rect = record.state.rect
        val geometry = SlotGeometry(
            rect.width().coerceAtLeast(1),
            rect.height().coerceAtLeast(1),
            densityDpi,
        )
        val display = VirtualDisplayFactory(appContext)
            .create(record.state.windowId, geometry, surface)
            .getOrElse { error ->
                NeXtepLog.error("freewindow", "VirtualDisplay failed window=${record.state.windowId}", error)
                return
            }
        record.virtualDisplay = display
        val displayId = display.display.displayId
        record.state = record.state.copy(displayId = displayId)
        // Fresh display (initial launch, or relaunch after the parked task died):
        // the task always starts here, never migrated between displays.
        startActivityOnDisplay(record, displayId)
        notifyChanged()
    }

    private fun startActivityOnDisplay(record: Record, displayId: Int) {
        val component = record.state.component ?: return
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            appContext.startActivity(intent, options.toBundle())
            NeXtepLog.info(
                "freewindow",
                "startActivity window=${record.state.windowId} display=$displayId component=$component",
            )
        }.onFailure { error ->
            NeXtepLog.error("freewindow", "startActivity failed window=${record.state.windowId}", error)
            return
        }
        record.launchAttempts = 0
        handler.postDelayed({ resolveTaskId(record) }, 500)
    }

    private fun resolveTaskId(record: Record) {
        if (record.state.taskId >= 0 || record.virtualDisplay == null) return
        val displayId = record.state.displayId
        val match = taskRepository.runningTasks().lastOrNull { it.displayId == displayId }
        if (match != null) {
            record.state = record.state.copy(taskId = match.taskId)
            NeXtepLog.info(
                "freewindow",
                "Resolved task window=${record.state.windowId} taskId=${match.taskId} display=$displayId",
            )
            notifyChanged()
            return
        }
        record.launchAttempts++
        if (record.launchAttempts < TASK_RESOLVE_ATTEMPTS) {
            handler.postDelayed({ resolveTaskId(record) }, 400)
        } else {
            NeXtepLog.warn(
                "freewindow",
                "Task not found window=${record.state.windowId} display=$displayId after retries",
            )
        }
    }

    // ------------------------------------------------------------------ window ops

    fun closeWindow(windowId: Int) {
        val record = find(windowId) ?: return
        record.state.taskId.takeIf { it >= 0 }?.let { taskId ->
            ActivityTaskManagerCompat.removeTask(taskId)
                .onFailure { error -> NeXtepLog.warn("freewindow", "removeTask failed task=$taskId", error) }
        }
        runCatching { record.virtualDisplay?.release() }
        record.view?.let { container.removeView(it) }
        records.remove(record)
        NeXtepLog.info("freewindow", "Closed window=$windowId")
        notifyChanged()
    }

    /**
     * Park a window: the task STAYS on its VirtualDisplay, resumed and still
     * rendering, so the app keeps believing it is in the foreground. Only the
     * view is hidden (INVISIBLE, never detached, so the SurfaceTexture and
     * the display surface survive untouched). This is the whole point of the
     * floating-ball background: fooled apps don't pause.
     */
    fun parkWindow(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.PARKED) return
        record.view?.visibility = View.INVISIBLE
        record.state = record.state.copy(mode = FreeWindowMode.PARKED)
        NeXtepLog.info("freewindow", "Parked window=$windowId task=${record.state.taskId} (kept foreground on display)")
        notifyChanged()
    }

    /**
     * Unpark: reorder without detach (View.bringToFront never detaches, so
     * the SurfaceTexture survives) and show the view again. Instant restore,
     * no surface rebind needed.
     */
    fun unparkWindow(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode != FreeWindowMode.PARKED) return
        // If the system killed the task while parked, relaunch it on unpark.
        // The view stayed attached, so its SurfaceTexture is still live.
        if (record.state.taskId >= 0 && taskRepository.findTask(record.state.taskId) == null) {
            NeXtepLog.info("freewindow", "Parked task died; relaunching window=$windowId")
            runCatching { record.virtualDisplay?.release() }
            record.virtualDisplay = null
            record.state = record.state.copy(taskId = -1, displayId = -1, mode = FreeWindowMode.WINDOWED)
            val view = record.view ?: return
            records.remove(record)
            records += record
            view.bringToFront()
            view.visibility = View.VISIBLE
            restackOverlay()
            view.currentSurface()?.let { surface -> onViewSurfaceReady(record, surface) }
            notifyChanged()
            return
        }
        record.state = record.state.copy(mode = FreeWindowMode.WINDOWED)
        val view = record.view ?: return
        records.remove(record)
        records += record
        view.bringToFront()
        view.visibility = View.VISIBLE
        restackOverlay()
        NeXtepLog.info("freewindow", "Unparked window=$windowId task=${record.state.taskId}")
        notifyChanged()
    }

    fun maximizeWindow(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.MAXIMIZED) return
        if (record.state.mode == FreeWindowMode.PARKED) unparkWindow(windowId)
        val full = fullRect()
        record.preMaxRect = Rect(record.state.rect)
        applyRect(record, full, resizeDisplay = true)
        record.state = record.state.copy(mode = FreeWindowMode.MAXIMIZED)
        record.view?.setMaximized(true)
        bringToFront(windowId)
        NeXtepLog.info("freewindow", "Maximized window=$windowId")
        notifyChanged()
    }

    fun restoreWindowed(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode != FreeWindowMode.MAXIMIZED) return
        val rect = record.preMaxRect ?: defaultRect()
        record.preMaxRect = null
        applyRect(record, rect, resizeDisplay = true)
        record.state = record.state.copy(mode = FreeWindowMode.WINDOWED)
        record.view?.setMaximized(false)
        NeXtepLog.info("freewindow", "Restored window=$windowId rect=$rect")
        notifyChanged()
    }

    fun bringToFront(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.PARKED) {
            unparkWindow(windowId)
            return
        }
        val view = record.view ?: return
        records.remove(record)
        records += record
        // View.bringToFront reorders without detach: the SurfaceTexture survives.
        view.bringToFront()
        restackOverlay()
        notifyChanged()
    }

    fun setMuted(windowId: Int, muted: Boolean) {
        val record = find(windowId) ?: return
        if (record.state.muted == muted) return
        val packageName = record.state.packageName
        val uid = record.state.uid
        if (uid < 0 || packageName.isNullOrEmpty()) {
            NeXtepLog.warn("freewindow", "Cannot mute window=$windowId: unknown uid/package")
            return
        }
        MuteController.setMuted(appContext, uid, packageName, muted)
        record.state = record.state.copy(muted = muted)
        record.view?.setMuted(muted)
        notifyChanged()
    }

    // ------------------------------------------------------- FreeWindowViewHost

    override fun onWindowTap(windowId: Int) = bringToFront(windowId)

    override fun onWindowMove(windowId: Int, dx: Int, dy: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.MAXIMIZED) return
        val rect = Rect(record.state.rect).apply { offset(dx, dy) }
        rect.clampToScreen()
        record.state = record.state.copy(rect = rect)
        record.view?.setWindowRect(rect)
    }

    override fun onWindowCarryRelease(windowId: Int, centerX: Int, centerY: Int) {
        val record = find(windowId) ?: return
        // The view already moved live; sync the final rect, then park when
        // the drop lands on the floating ball.
        record.view?.let { record.state = record.state.copy(rect = it.currentRect()) }
        if (ballBounds()?.contains(centerX, centerY) == true) {
            parkWindow(windowId)
        } else {
            notifyChanged()
        }
    }

    override fun onWindowResizeCommit(windowId: Int, rect: Rect) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.MAXIMIZED) return
        val clamped = Rect(rect).apply { clampMinSize() }
        applyRect(record, clamped, resizeDisplay = true)
        notifyChanged()
    }

    override fun onWindowClose(windowId: Int) = closeWindow(windowId)

    override fun onWindowPark(windowId: Int) = parkWindow(windowId)

    override fun onWindowMaximizeToggle(windowId: Int) {
        val record = find(windowId) ?: return
        if (record.state.mode == FreeWindowMode.MAXIMIZED) restoreWindowed(windowId)
        else maximizeWindow(windowId)
    }

    override fun onWindowMuteToggle(windowId: Int) {
        val record = find(windowId) ?: return
        setMuted(windowId, !record.state.muted)
    }

    override fun onWindowSurfaceReady(windowId: Int, surface: Surface) {
        find(windowId)?.let { onViewSurfaceReady(it, surface) }
    }

    // ------------------------------------------------------------------ internals

    private fun applyRect(record: Record, rect: Rect, resizeDisplay: Boolean) {
        record.state = record.state.copy(rect = Rect(rect))
        record.view?.setWindowRect(rect)
        if (resizeDisplay) {
            runCatching {
                record.virtualDisplay?.resize(rect.width(), rect.height(), densityDpi)
                NeXtepLog.info(
                    "freewindow",
                    "Resized display window=${record.state.windowId} to ${rect.width()}x${rect.height()}",
                )
            }.onFailure { error ->
                NeXtepLog.warn("freewindow", "VirtualDisplay.resize failed window=${record.state.windowId}", error)
            }
        }
    }

    private fun find(windowId: Int): Record? = records.firstOrNull { it.state.windowId == windowId }

    private fun notifyChanged() {
        listener?.onChanged()
    }

    private fun layoutParamsFor(rect: Rect) =
        FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
            gravity = Gravity.TOP or Gravity.START
        }

    private fun defaultRect(): Rect {
        val metrics = appContext.resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val longSide = max(metrics.widthPixels, metrics.heightPixels)
        val w = (shortSide * 0.72).toInt()
        val h = min((w * 1.25).toInt(), (longSide * 0.8).toInt())
        val step = (24 * density).toInt()
        val cascade = (records.size % 4) * step
        val left = ((metrics.widthPixels - w) / 2 + cascade - step).coerceAtLeast(0)
        val top = ((metrics.heightPixels - h) / 3 + cascade).coerceAtLeast(0)
        return Rect(left, top, left + w, top + h)
    }

    private fun fullRect(): Rect {
        val metrics = appContext.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun Rect.clampToScreen() {
        val metrics = appContext.resources.displayMetrics
        val w = width()
        val h = height()
        offsetTo(
            left.coerceIn(0, max(0, metrics.widthPixels - w)),
            top.coerceIn(0, max(0, metrics.heightPixels - h)),
        )
    }

    private fun Rect.clampMinSize() {
        val minPx = (MIN_SIZE_DP * density).toInt()
        if (width() < minPx) right = left + minPx
        if (height() < minPx) bottom = top + minPx
    }

    companion object {
        const val MAX_WINDOWS = 5
        private const val MIN_SIZE_DP = 200
        private const val TASK_RESOLVE_ATTEMPTS = 6
    }
}
