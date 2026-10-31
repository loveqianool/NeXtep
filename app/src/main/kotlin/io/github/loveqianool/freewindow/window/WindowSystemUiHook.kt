package io.github.loveqianool.freewindow.window

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.Toast
import io.github.loveqianool.freewindow.notification.AppPanel
import io.github.loveqianool.freewindow.notification.FreeWindowNotification
import io.github.loveqianool.freewindow.notification.RecentsBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point for the SystemUI side of the free-window module.
 *
 * The Xposed entry ([io.github.loveqianool.freewindow.HookEntry]) calls
 * [install] when `com.android.systemui` is loaded. SystemUI's
 * [Application] is not available yet at that point, so we poll
 * `ActivityThread.currentApplication()` (via reflection — it is not visible
 * in the compile SDK) until it appears, then initialize [WindowManager] and
 * the persistent notification.
 */
object WindowSystemUiHook {
    private const val TAG = "FreeWindow"
    private val started = AtomicBoolean(false)

    // Singleton guard: onShowPanel() used to create a fresh AppPanel on every
    // broadcast, so a double delivery stacked two dialogs (AppPanel.show()'s
    // isShowing check only guards one instance). A dismissed panel reports
    // isShowing() == false, so the next tap still creates a new one.
    private var currentPanel: AppPanel? = null

    fun install() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val app = waitForApplication()
            if (app == null) {
                Log.i(TAG, "systemui application not found, giving up")
                return@Thread
            }
            WindowManager.init(app)
            // Shared by the panel's app-list tap and the Recents menu's
            // "FreeWindow" item: already open -> restore that window,
            // otherwise open a new one (or complain about the limit/failure).
            val openOrRestore: (Context, ComponentName) -> Unit = { ctx, component ->
                val existing = WindowManager.findWindowId(component)
                if (existing != null) {
                    // restoreWindow (not a bare unpark) so a collapsed
                    // window expands instead of just showing its icon again.
                    WindowManager.restoreWindow(existing)
                } else if (WindowManager.isAtLimit()) {
                    Toast.makeText(
                        ctx,
                        "FreeWindow：已达 ${WindowManager.MAX_WINDOWS} 个窗口上限，先关掉一个再开",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (WindowManager.openApp(ctx, component) < 0) {
                    Toast.makeText(
                        ctx,
                        "FreeWindow：打开窗口失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            RecentsBridge.listener = object : RecentsBridge.Listener {
                override fun onWindowTask(packageName: String, className: String, taskId: Int) {
                    // taskId is the Recents task on the main display; v1
                    // windows by relaunching on the VD (same as panel open).
                    // A true task-move would use it; that is a later step.
                    openOrRestore(app, ComponentName(packageName, className))
                }

                override fun onRestoreWindow(windowId: Int) =
                    WindowManager.restoreWindow(windowId)

                override fun onCloseWindow(windowId: Int) =
                    WindowManager.closeWindow(windowId)

                override fun onRequestWindows() {
                    // Bypass WindowManager.notifyRecents()'s dedup: a
                    // launcher that just started has an empty cache, so it
                    // needs the current list even if nothing changed.
                    RecentsBridge.broadcastWindows(app, WindowManager.getPanelWindows())
                }
            }
            RecentsBridge.register(app)
            FreeWindowNotification.listener = object : FreeWindowNotification.Listener {
                override fun onShowPanel() {
                    Log.i(TAG, "show panel requested")
                    if (currentPanel?.isShowing() == true) {
                        Log.i(TAG, "panel already showing, ignore")
                        return
                    }
                    // Safety net for rotation: if the view configuration
                    // dispatch did not fire for some window, opening the
                    // panel (reachable via the notification regardless of
                    // window positions) pulls every window back in bounds.
                    WindowManager.reclampAllWindows()
                    try {
                        val ctx = app ?: return
                        val panel = AppPanel(
                            ctx,
                            getWindows = { WindowManager.getPanelWindows() },
                            onAppSelected = { component ->
                                openOrRestore(ctx, component)
                            },
                            onWindowSelected = { windowId ->
                                WindowManager.restoreWindow(windowId)
                            },
                            onWindowMaximized = { windowId ->
                                // Unpark first (if parked), then toggle maximize/restore.
                                WindowManager.unparkWindow(windowId)
                                WindowManager.toggleMaximize(windowId)
                            },
                            onWindowClosed = { windowId ->
                                WindowManager.closeWindow(windowId)
                            },
                            onWindowParked = { windowId ->
                                WindowManager.parkWindow(windowId)
                            },
                            onWindowMuted = { windowId ->
                                WindowManager.onToggleMute(windowId)
                            },
                            onWindowCropReset = { windowId ->
                                WindowManager.resetCrop(windowId)
                            },
                        )
                        currentPanel = panel
                        panel.show()
                    } catch (e: Throwable) {
                        Log.i(TAG, "show panel failed: ${e.message}")
                    }
                }

                override fun onUnpark(windowId: Int) = WindowManager.unparkWindow(windowId)

                override fun onRestore(windowId: Int) = WindowManager.restoreWindow(windowId)

                override fun onExit() = WindowManager.exit()
            }
            FreeWindowNotification.show(app)
            Log.i(TAG, "WindowSystemUiHook installed")
        }, "FreeWindowInit").apply { isDaemon = true }.start()
    }

    private fun waitForApplication(): Application? {
        repeat(600) {
            currentApplication()?.let { return it }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun currentApplication(): Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Application
    }.getOrNull()
}
