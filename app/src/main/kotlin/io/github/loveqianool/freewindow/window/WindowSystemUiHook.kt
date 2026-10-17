package io.github.loveqianool.freewindow.window

import android.app.Application
import android.util.Log
import io.github.loveqianool.freewindow.notification.AppPanel
import io.github.loveqianool.freewindow.notification.FreeWindowNotification
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

    fun install() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val app = waitForApplication()
            if (app == null) {
                Log.i(TAG, "systemui application not found, giving up")
                return@Thread
            }
            WindowManager.init(app)
            FreeWindowNotification.listener = object : FreeWindowNotification.Listener {
                override fun onShowPanel() {
                    Log.i(TAG, "show panel requested")
                    try {
                        val ctx = app ?: return
                        val panel = AppPanel(
                            ctx,
                            WindowManager.getParkedApps(),
                            onAppSelected = { component ->
                                WindowManager.openApp(ctx, component)
                            },
                            onParkedSelected = { windowId ->
                                WindowManager.unparkWindow(windowId)
                            },
                            onParkedMaximized = { windowId ->
                                // Unpark first (if parked), then toggle maximize/restore.
                                WindowManager.unparkWindow(windowId)
                                WindowManager.toggleMaximize(windowId)
                            },
                            onParkedClosed = { windowId ->
                                WindowManager.closeWindow(windowId)
                            },
                        )
                        panel.show()
                    } catch (e: Throwable) {
                        Log.i(TAG, "show panel failed: ${e.message}")
                    }
                }

                override fun onUnpark(windowId: Int) = WindowManager.unparkWindow(windowId)

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
