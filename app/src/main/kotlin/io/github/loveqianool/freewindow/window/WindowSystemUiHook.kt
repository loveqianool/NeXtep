package io.github.loveqianool.freewindow.window

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.Toast
import io.github.loveqianool.freewindow.bridge.RecentsBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point for the SystemUI side of the free-window module.
 *
 * The Xposed entry ([io.github.loveqianool.freewindow.HookEntry]) calls
 * [install] when `com.android.systemui` is loaded. SystemUI's
 * [Application] is not available yet at that point, so we poll
 * `ActivityThread.currentApplication()` (via reflection — it is not visible
 * in the compile SDK) until it appears, then initialize [WindowManager]
 * and the Recents bridge. Windows are managed from system Recents;
 * there is no notification and no panel anymore (removed 2026-11-01,
 * once Recents cards proved out on device).
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
            // The Recents menu's "FreeWindow" item: already open ->
            // restore that window, otherwise open a new one (or complain
            // about the limit/failure).
            val openOrRestore: (Context, ComponentName, Int) -> Unit =
                { ctx, component, originalTaskId ->
                    val existing = WindowManager.findWindowId(component)
                    if (existing != null) {
                        // restoreWindow (not a bare unpark) so a collapsed
                        // window expands instead of just showing its icon again.
                        // Remember the task this menu tap came from, so
                        // closing the window can auto-swipe that task.
                        if (originalTaskId > 0) {
                            WindowManager.setOriginalTaskId(existing, originalTaskId)
                        }
                        WindowManager.restoreWindow(existing)
                    } else if (WindowManager.isAtLimit()) {
                        Toast.makeText(
                            ctx,
                            "FreeWindow：已达 ${WindowManager.MAX_WINDOWS} 个窗口上限，先关掉一个再开",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else if (WindowManager.openApp(ctx, component, originalTaskId) < 0) {
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
                    // windows by relaunching on the VD, and the task id
                    // is kept so closing the window can remove it.
                    // A true task-move would use it; that is a later step.
                    openOrRestore(app, ComponentName(packageName, className), taskId)
                }

                override fun onRestoreWindow(windowId: Int) =
                    WindowManager.restoreWindow(windowId)

                override fun onCloseWindow(windowId: Int) =
                    WindowManager.closeWindow(windowId)

                override fun onRequestWindows() {
                    // Bypass WindowManager.notifyRecents()'s dedup: a
                    // launcher that just started has an empty cache, so it
                    // needs the current list even if nothing changed.
                    RecentsBridge.broadcastWindows(app, WindowManager.getRecentsWindows())
                }
            }
            RecentsBridge.register(app)
            // One-time migration, kept idempotent: the old build showed
            // an ongoing notification (id 41041, legacy 41). Without a
            // cancel here it would linger in the shade after updating
            // until the next SystemUI restart. Cancelling a
            // non-existent id is a no-op, so this is safe every start.
            runCatching {
                val nm = app.getSystemService(android.app.NotificationManager::class.java)
                nm.cancel(41041)
                nm.cancel(41)
            }
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
