package io.github.loveqianool.freewindow.server

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.loveqianool.freewindow.notification.FreeWindowNotification
import io.github.loveqianool.freewindow.notification.RecentsBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Card injection for FreeWindow windows, from system_server.
 *
 * Launcher's own task list (GroupTask/DesktopTask in current Launcher3)
 * changes shape across versions, so faking a task there is version-bound.
 * The recent-task list itself is stable: Launcher gets it from
 * ActivityTaskManagerService.getRecentTasks, so this hook appends a fake
 * RecentTaskInfo per FreeWindow window to that list, and intercepts the
 * two calls Launcher makes for a card it shows:
 * - startActivityFromRecents(fakeId) -> send RECENTS_RESTORE to SystemUI
 *   (the window is restored there) and report success, so Recents
 *   dismisses as if the task had launched.
 * - removeTask(fakeId) -> send RECENTS_CLOSE to SystemUI and report
 *   true, so the card goes away and the window closes.
 *
 * Fake ids are [FAKE_BASE] + windowId (window ids are 1..8, so fake ids
 * are 900001..900008 — far above real task ids, which stay small).
 *
 * The window list comes from SystemUI via RecentsBridge.WINDOWS_CHANGED
 * (already broadcast on every window change, plus on REQUEST_WINDOWS,
 * which this hook sends once at install to bootstrap its cache). The
 * cache is the only thing getRecentTasks can use: that call is
 * synchronous, it cannot wait for an async broadcast reply, so a card
 * appears on the next Recents open after a window change at the latest.
 *
 * Safety, in order of importance (this runs in system_server):
 * - Every hook body is wrapped so a failure returns the original result
 *   instead of throwing: a broken fake-task path must never break real
 *   Recents, let alone crash system_server.
 * - Fake tasks are only appended for the launcher's own Binder calls
 *   (calling UID == launcher UID). Other callers (SystemUI, Settings,
 *   …) get the original list untouched.
 * - The original list is never mutated in place: fakes go into a copy,
 *   so a shared / cached list in system_server cannot be corrupted.
 * - If the returned list already contains a fake id (e.g. one
 *   getRecentTasks overload calling another internally), fakes are not
 *   appended a second time.
 * - Field sets on RecentTaskInfo are best-effort per field: only id,
 *   baseIntent and taskDescription are essential; a window whose launch
 *   intent cannot be resolved is skipped, not faked with a broken intent.
 */
object RecentsTaskHook {
    private const val TAG = "FreeWindow"
    private const val FAKE_BASE = 900000
    private const val LAUNCHER_PACKAGE = "com.android.launcher3"

    private val installed = AtomicBoolean(false)
    private val cacheLock = Any()
    private var cachedWindows: List<CachedWindow> = emptyList()
    private var launcherUid = -1

    private data class CachedWindow(
        val windowId: Int,
        val packageName: String,
        val label: String,
    )

    fun install(module: XposedModule, classLoader: ClassLoader, context: Context) {
        if (!installed.compareAndSet(false, true)) return
        if (systemContext == null) systemContext = context
        try {
            launcherUid = runCatching {
                context.packageManager.getApplicationInfo(LAUNCHER_PACKAGE, 0).uid
            }.getOrNull() ?: -1
            if (launcherUid < 0) {
                Log.w(TAG, "recents-tasks: launcher uid not found, fake tasks disabled")
            }
            registerWindowReceiver(context)
            // Bootstrap: SystemUI answers with WINDOWS_CHANGED, which fills
            // the cache below. Without this, the cache stays empty until
            // the next window change after system_server starts.
            context.sendBroadcast(
                Intent(RecentsBridge.ACTION_REQUEST_WINDOWS).apply {
                    setPackage("com.android.systemui")
                },
            )
            hookTaskManager(module, classLoader)
            Log.i(TAG, "recents-tasks: installed, launcherUid=$launcherUid")
        } catch (e: Throwable) {
            Log.w(TAG, "recents-tasks: install failed", e)
            installed.set(false)
        }
    }

    private fun registerWindowReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != RecentsBridge.ACTION_WINDOWS_CHANGED) return
                try {
                    val ids = intent.getIntArrayExtra(RecentsBridge.EXTRA_WINDOW_IDS) ?: return
                    val packages = intent.getStringArrayExtra(RecentsBridge.EXTRA_PACKAGES) ?: return
                    val labels = intent.getStringArrayExtra(RecentsBridge.EXTRA_LABELS) ?: return
                    if (ids.size != packages.size || ids.size != labels.size) {
                        Log.w(TAG, "recents-tasks: WINDOWS_CHANGED length mismatch")
                        return
                    }
                    val list = ids.indices.map { i ->
                        CachedWindow(ids[i], packages[i], labels[i])
                    }
                    synchronized(cacheLock) { cachedWindows = list }
                    Log.i(TAG, "recents-tasks: cached ${list.size} windows")
                } catch (e: Throwable) {
                    Log.w(TAG, "recents-tasks: WINDOWS_CHANGED parse failed", e)
                }
            }
        }
        val filter = IntentFilter(RecentsBridge.ACTION_WINDOWS_CHANGED)
        // EXPORTED: the sender is SystemUI, a different app; a
        // NOT_EXPORTED receiver would drop its broadcasts.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
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
                    "getRecentTasks" -> {
                        method.isAccessible = true
                        module.hook(method).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                // If the original call itself fails, let
                                // it fail as before: do not mask it.
                                val original = chain.proceed()
                                return try {
                                    appendFakes(chain, original)
                                } catch (e: Throwable) {
                                    // Never break real Recents because of
                                    // a fake-task failure.
                                    Log.w(TAG, "recents-tasks: append fakes failed", e)
                                    original
                                }
                            }
                        })
                        hooked = true
                    }
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
            putExtra(FreeWindowNotification.EXTRA_WINDOW_ID, fakeWindowId(taskId))
        }
        ctx.sendBroadcast(intent)
        Log.i(TAG, "recents-tasks: $action window=${fakeWindowId(taskId)}")
    }

    private fun appendFakes(chain: XposedInterface.Chain, original: Any?): Any? {
        val list = original as? List<*> ?: return original
        if (launcherUid < 0) return original
        // Only the launcher's own calls get fake cards; internal and
        // other callers see the real list untouched.
        if (Binder.getCallingUid() != launcherUid) return original
        // Already faked (one overload calling another): do not duplicate.
        if (list.any { isFakeIdOf(it) }) return original
        val windows = synchronized(cacheLock) { cachedWindows }
        if (windows.isEmpty()) return original
        val context = (getField(chain.thisObject, "mContext") as? Context)
            ?: systemContext ?: return original
        val fakes = windows.mapNotNull { buildFakeTask(context, it) }
        if (fakes.isEmpty()) return original
        val result = ArrayList<Any?>(fakes.size + list.size)
        // Fakes first, topmost window first: they are the windows the
        // user is actively working with.
        result.addAll(fakes)
        result.addAll(list)
        // Respect the caller's maxNum (first int arg in every known
        // overload); without this a small request could grow past it.
        val maxNum = chain.args.getOrNull(0) as? Int ?: 0
        val truncated = if (maxNum > 0 && result.size > maxNum) {
            result.subList(0, maxNum)
        } else {
            result
        }
        Log.i(TAG, "recents-tasks: appended ${fakes.size} fake tasks")
        return truncated
    }

    private fun isFakeIdOf(taskInfo: Any?): Boolean {
        if (taskInfo == null) return false
        val id = getField(taskInfo, "id") as? Int ?: return false
        return isFake(id)
    }

    private fun buildFakeTask(context: Context, window: CachedWindow): Any? {
        return try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(window.packageName) ?: run {
                    Log.w(TAG, "recents-tasks: no launch intent for ${window.packageName}")
                    return null
                }
            val clazz = Class.forName("android.app.ActivityManager\$RecentTaskInfo")
            val info = clazz.getDeclaredConstructor().newInstance()
            val fakeId = FAKE_BASE + window.windowId
            setField(info, "id", fakeId)
            setField(info, "persistentId", fakeId)
            setField(info, "baseIntent", launchIntent)
            setField(info, "origActivity", launchIntent.component)
            setField(info, "topActivity", launchIntent.component)
            setField(
                info, "taskDescription",
                ActivityManager.TaskDescription(window.label, null, 0),
            )
            // Best-effort only; these fields vary by version and none
            // of them is required for Launcher to show the card.
            setField(info, "userId", 0)
            setField(info, "displayId", 0)
            // WINDOWING_MODE_FULLSCREEN: a normal card, not a freeform one.
            setField(info, "windowingMode", 1)
            val now = System.currentTimeMillis()
            setField(info, "firstActiveTime", now)
            setField(info, "lastActiveTime", now)
            info
        } catch (e: Throwable) {
            Log.w(TAG, "recents-tasks: build fake for ${window.packageName} failed", e)
            null
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        try {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    field.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
        } catch (_: Throwable) {
            // Best-effort: a missing / inaccessible field on this version
            // just means that attribute is left at its default.
        }
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
