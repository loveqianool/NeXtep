package io.github.loveqianool.freewindow.server

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.loveqianool.freewindow.bridge.RecentsBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fake-card tap / swipe handling for FreeWindow windows, from
 * system_server.
 *
 * Fake cards themselves are injected launcher-side (see
 * RecentsHook.installCardInjection): the launcher never calls
 * ActivityTaskManagerService.getRecentTasks for its Recents list on
 * this device, so the earlier system_server getRecentTasks append
 * path was diagnosed dead and removed. What system_server still
 * owns is the other end of a fake card's lifecycle:
 * - startActivityFromRecents(fakeId) -> send RECENTS_RESTORE to
 *   SystemUI (the window is restored there) and report success, so
 *   Recents dismisses as if the task had launched.
 * - removeTask(fakeId) -> send RECENTS_CLOSE to SystemUI and report
 *   true, so the card goes away and the window closes.
 *
 * Fake ids are [FAKE_BASE] + windowId (window ids are 1..8, so fake
 * ids are 900001..900008 — far above real task ids, which stay
 * small).
 *
 * Safety, in order of importance (this runs in system_server):
 * - Every hook body is wrapped so a failure returns the original
 *   result instead of throwing: a broken fake-task path must never
 *   break real Recents, let alone crash system_server.
 * - Non-fake task ids proceed to the original method untouched.
 */
object RecentsTaskHook {
    private const val TAG = "FreeWindow"
    private const val FAKE_BASE = 900000

    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader, context: Context) {
        if (!installed.compareAndSet(false, true)) return
        if (systemContext == null) systemContext = context
        try {
            hookTaskManager(module, classLoader)
            Log.i(TAG, "recents-tasks: installed")
        } catch (e: Throwable) {
            Log.w(TAG, "recents-tasks: install failed", e)
            installed.set(false)
        }
    }

    private fun hookTaskManager(module: XposedModule, cl: ClassLoader) {
        val taskManagerClass = runCatching {
            cl.loadClass("com.android.server.wm.ActivityTaskManagerService")
        }.getOrNull() ?: runCatching {
            // Pre-ActivityTaskManager split fallback; only one class is
            // ever hooked, so a call cannot be faked twice (see below).
            cl.loadClass("com.android.server.am.ActivityManagerService")
        }.getOrNull() ?: run {
            Log.w(TAG, "recents-tasks: task manager class not found")
            return
        }
        var hooked = false
        for (method in taskManagerClass.declaredMethods) {
            try {
                when (method.name) {
                    "startActivityFromRecents" -> {
                        method.isAccessible = true
                        module.hook(method).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val taskId = chain.args.getOrNull(0) as? Int
                                if (taskId != null && isFake(taskId)) {
                                    return try {
                                        sendToSystemUi(chain, RecentsBridge.ACTION_RESTORE_WINDOW, taskId)
                                        // ActivityManager.START_SUCCESS
                                        0
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "recents-tasks: restore fake failed", e)
                                        chain.proceed()
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                        hooked = true
                    }
                    "removeTask" -> {
                        method.isAccessible = true
                        module.hook(method).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val taskId = chain.args.getOrNull(0) as? Int
                                if (taskId != null && isFake(taskId)) {
                                    return try {
                                        sendToSystemUi(chain, RecentsBridge.ACTION_CLOSE_WINDOW, taskId)
                                        true
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "recents-tasks: close fake failed", e)
                                        chain.proceed()
                                    }
                                }
                                return chain.proceed()
                            }
                        })
                        hooked = true
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "recents-tasks: hook ${method.name} failed", e)
            }
        }
        Log.i(TAG, "recents-tasks: task manager hooks installed=$hooked")
    }

    private fun isFake(taskId: Int): Boolean {
        val windowId = taskId - FAKE_BASE
        return windowId in 1..8
    }

    private fun fakeWindowId(taskId: Int): Int = taskId - FAKE_BASE

    /**
     * SystemUI is the process that owns the windows; system_server only
     * forwards the user's Recents action there. The broadcast is sent
     * from the system_server context: the task manager's mContext if it
     * can be read, else the context captured at install time.
     */
    private var systemContext: Context? = null

    private fun sendToSystemUi(chain: XposedInterface.Chain, action: String, taskId: Int) {
        // The chain's thisObject is the task manager service, whose
        // mContext field holds the system_server context. Fall back to
        // the cached context from install time if that lookup fails.
        val ctx = (getField(chain.thisObject, "mContext") as? Context) ?: systemContext
            ?: throw IllegalStateException("no system context for $action")
        val intent = Intent(action).apply {
            setPackage("com.android.systemui")
            putExtra(RecentsBridge.EXTRA_WINDOW_ID, fakeWindowId(taskId))
        }
        ctx.sendBroadcast(intent)
        Log.i(TAG, "recents-tasks: $action window=${fakeWindowId(taskId)}")
    }

    private fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(target)
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
