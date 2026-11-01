package io.github.loveqianool.freewindow.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Cross-process bridge between the launcher (Recents) and SystemUI, where
 * [io.github.loveqianool.freewindow.window.WindowManager] lives.
 *
 * The launcher process cannot call WindowManager directly, so it talks to
 * SystemUI through broadcasts, and SystemUI talks back the same way:
 *
 * - SystemUI -> launcher: [ACTION_WINDOWS_CHANGED] whenever the window
 *   list changes (open, close, park, unpark, collapse, expand, maximize,
 *   restore — anything that goes through WindowManager.notifyRecents
 *   or openApp). The payload is parallel arrays, not a Parcelable, so the
 *   launcher side only needs the extra keys below, not a shared class:
 *   [EXTRA_WINDOW_IDS], [EXTRA_PACKAGES], [EXTRA_LABELS], [EXTRA_FLAGS],
 *   all in the same order, one entry per window, topmost first.
 *   Flag bits in [EXTRA_FLAGS]: [FLAG_PARKED], [FLAG_COLLAPSED],
 *   [FLAG_MAXIMIZED], [FLAG_MUTED], [FLAG_CROPPED].
 * - Launcher -> SystemUI: [ACTION_WINDOW_TASK] (window a Recents task:
 *   [EXTRA_PACKAGE], [EXTRA_CLASS], [EXTRA_TASK_ID] — the Recents task id
 *   on the main display, kept for a future true task-move; v1 windows by
 *   relaunching on the VD, same as a Recents-menu open), [ACTION_RESTORE_WINDOW]
 *   and [ACTION_CLOSE_WINDOW] (both carry [EXTRA_WINDOW_ID]),
 *   and [ACTION_REQUEST_WINDOWS] (launcher asks for a fresh list, e.g.
 *   when Recents opens and its cache may be empty or stale — this also
 *   picks up mute/crop changes, which do not go through notifyRecents
 *   and therefore do not trigger a WINDOWS_CHANGED broadcast).
 *
 * Both receivers are EXPORTED, which is required for delivery between two
 * different apps (a NOT_EXPORTED receiver drops the other process's
 * broadcasts). The actions are namespaced to this module and carry only
 * window-management data — window ids, package names, labels, states —
 * no credentials or user data, and every inbound window id / component is
 * validated by WindowManager before it acts (unknown ids are ignored,
 * a bad component fails the open like a Recents-menu open would). A hostile app
 * sending these broadcasts could at worst open, restore or close a free
 * window, which the user can already do from the launcher.
 */
object RecentsBridge {

    /** SystemUI -> launcher: the window list changed; see the class KDoc. */
    const val ACTION_WINDOWS_CHANGED = "io.github.loveqianool.freewindow.WINDOWS_CHANGED"
    /** Launcher -> SystemUI: window the given Recents task. */
    const val ACTION_WINDOW_TASK = "io.github.loveqianool.freewindow.WINDOW_TASK"
    /** Launcher -> SystemUI: restore (unpark / expand) a FreeWindow window. */
    const val ACTION_RESTORE_WINDOW = "io.github.loveqianool.freewindow.RECENTS_RESTORE"
    /** Launcher -> SystemUI: close a FreeWindow window. */
    const val ACTION_CLOSE_WINDOW = "io.github.loveqianool.freewindow.RECENTS_CLOSE"
    /** Launcher -> SystemUI: send the current window list now. */
    const val ACTION_REQUEST_WINDOWS = "io.github.loveqianool.freewindow.REQUEST_WINDOWS"

    const val EXTRA_WINDOW_IDS = "io.github.loveqianool.freewindow.extra.WINDOW_IDS"
    const val EXTRA_PACKAGES = "io.github.loveqianool.freewindow.extra.PACKAGES"
    const val EXTRA_LABELS = "io.github.loveqianool.freewindow.extra.LABELS"
    const val EXTRA_FLAGS = "io.github.loveqianool.freewindow.extra.FLAGS"
    const val EXTRA_PACKAGE = "io.github.loveqianool.freewindow.extra.PACKAGE"
    const val EXTRA_CLASS = "io.github.loveqianool.freewindow.extra.CLASS"
    const val EXTRA_TASK_ID = "io.github.loveqianool.freewindow.extra.TASK_ID"
    /** Window id carried by restore/close broadcasts (was on the notification). */
    const val EXTRA_WINDOW_ID = "io.github.loveqianool.freewindow.extra.WINDOW_ID"

    /** One free window, as listed to Recents and as broadcast to the launcher. */
    data class WindowInfo(
        val windowId: Int,
        val label: String,
        val isMaximized: Boolean = false,
        val component: android.content.ComponentName? = null,
        val isParked: Boolean = false,
        val isMuted: Boolean = false,
        val isCollapsed: Boolean = false,
        val isCropped: Boolean = false,
    )

    const val FLAG_PARKED = 1
    const val FLAG_COLLAPSED = 2
    const val FLAG_MAXIMIZED = 4
    const val FLAG_MUTED = 8
    const val FLAG_CROPPED = 16

    interface Listener {
        fun onWindowTask(packageName: String, className: String, taskId: Int)
        fun onRestoreWindow(windowId: Int)
        fun onCloseWindow(windowId: Int)
        fun onRequestWindows()
    }

    var listener: Listener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Sender check: this receiver is EXPORTED (cross-process
            // delivery requires it), so without a check any app could
            // send WINDOW_TASK and have SystemUI open an activity in
            // a topmost system overlay, or close/restore the user's
            // windows. Only the launcher, system_server (uid 1000) and
            // SystemUI itself may send these. getSendingUid is looked
            // up reflectively so an old stub cannot break the build;
            // if it is unavailable, fall back to accepting (old
            // behaviour) rather than breaking Recents on this device.
            val senderUid = sendingUidOf(this)
            if (senderUid != null) {
                val allowed = mutableSetOf(1000)
                packageUid(context, "com.android.systemui")?.let { allowed.add(it) }
                packageUid(context, "com.android.launcher3")?.let { allowed.add(it) }
                if (senderUid !in allowed) {
                    Log.w(TAG, "recents: dropped ${intent.action} from uid=$senderUid")
                    return
                }
            }
            when (intent.action) {
                ACTION_WINDOW_TASK -> {
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                    val cls = intent.getStringExtra(EXTRA_CLASS) ?: return
                    if (pkg.isEmpty() || cls.isEmpty()) return
                    val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
                    Log.i(TAG, "recents: window task $pkg/$cls task=$taskId")
                    listener?.onWindowTask(pkg, cls, taskId)
                }
                ACTION_RESTORE_WINDOW -> {
                    val windowId = intent.getIntExtra(EXTRA_WINDOW_ID, -1)
                    Log.i(TAG, "recents: restore window=$windowId")
                    if (windowId >= 0) listener?.onRestoreWindow(windowId)
                }
                ACTION_CLOSE_WINDOW -> {
                    val windowId = intent.getIntExtra(EXTRA_WINDOW_ID, -1)
                    Log.i(TAG, "recents: close window=$windowId")
                    if (windowId >= 0) listener?.onCloseWindow(windowId)
                }
                ACTION_REQUEST_WINDOWS -> {
                    Log.i(TAG, "recents: windows requested")
                    listener?.onRequestWindows()
                }
            }
        }
    }

    private var receiverRegistered = false
    private const val TAG = "FreeWindow"

    /** Sending uid of the current broadcast, or null if unavailable. */
    fun sendingUidOf(receiver: BroadcastReceiver): Int? = runCatching {
        val m = BroadcastReceiver::class.java.getDeclaredMethod("getSendingUid")
        m.isAccessible = true
        m.invoke(receiver) as? Int
    }.getOrNull()

    private fun packageUid(context: Context, packageName: String): Int? = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    /** Broadcast the current window list to the launcher. No setPackage: the
     * launcher package is not known on this side yet (it varies by ROM and
     * by the user's default launcher), and the launcher's receiver is
     * registered EXPORTED for exactly this broadcast. */
    fun broadcastWindows(context: Context, windows: List<WindowInfo>) {
        val flags = IntArray(windows.size) { i ->
            val w = windows[i]
            var f = 0
            if (w.isParked) f = f or FLAG_PARKED
            if (w.isCollapsed) f = f or FLAG_COLLAPSED
            if (w.isMaximized) f = f or FLAG_MAXIMIZED
            if (w.isMuted) f = f or FLAG_MUTED
            if (w.isCropped) f = f or FLAG_CROPPED
            f
        }
        val intent = Intent(ACTION_WINDOWS_CHANGED).apply {
            putExtra(EXTRA_WINDOW_IDS, windows.map { it.windowId }.toIntArray())
            putExtra(EXTRA_PACKAGES, windows.map { it.component?.packageName ?: "" }.toTypedArray())
            putExtra(EXTRA_LABELS, windows.map { it.label }.toTypedArray())
            putExtra(EXTRA_FLAGS, flags)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "recents: broadcast ${windows.size} windows")
    }

    /** Register the launcher -> SystemUI receiver. Safe to call multiple times. */
    fun register(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_WINDOW_TASK)
            addAction(ACTION_RESTORE_WINDOW)
            addAction(ACTION_CLOSE_WINDOW)
            addAction(ACTION_REQUEST_WINDOWS)
        }
        // EXPORTED: the sender is the launcher, a different app; a
        // NOT_EXPORTED receiver would drop its broadcasts. See the class
        // KDoc for why this is acceptable here.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }
}
