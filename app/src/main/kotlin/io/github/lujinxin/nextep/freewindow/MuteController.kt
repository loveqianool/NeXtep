package io.github.lujinxin.nextep.freewindow

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Per-window mute implemented through AppOps: setting OP_PLAY_AUDIO to
 * MODE_IGNORED for the app's uid silences its audio playback.
 *
 * The actual AppOpsService call must run inside system_server, so the
 * systemui-side [setMuted] sends an explicit broadcast that
 * [FreeWindowMuteReceiver] (registered in system_server by
 * systemserver/FreeWindowMuteHook) handles in-process.
 */
object MuteController {
    const val ACTION_MUTE = "io.github.lujinxin.nextep.freewindow.MUTE"
    const val EXTRA_UID = "uid"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_MUTED = "muted"

    /** Call from the systemui process (overlay). Fire-and-forget. */
    fun setMuted(context: Context, uid: Int, packageName: String, muted: Boolean) {
        runCatching {
            val intent = Intent(ACTION_MUTE)
                .setPackage("android")
                .putExtra(EXTRA_UID, uid)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_MUTED, muted)
            context.sendBroadcast(intent)
            NeXtepLog.info("freewindow_mute", "Requested muted=$muted uid=$uid package=$packageName")
        }.onFailure { error ->
            NeXtepLog.warn("freewindow_mute", "Failed to send mute broadcast", error)
        }
    }

    /**
     * Runs inside system_server. Registered by FreeWindowMuteHook.
     * Calls IAppOpsService.setMode directly via reflection; no permission
     * check applies to in-process calls from system_server itself.
     */
    class FreeWindowMuteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MUTE) return
            val uid = intent.getIntExtra(EXTRA_UID, -1)
            val packageName = intent.getStringExtra(EXTRA_PACKAGE)
            val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
            if (uid < 0 || packageName.isNullOrEmpty()) {
                NeXtepLog.warn("freewindow_mute", "Dropping mute request uid=$uid package=$packageName")
                return
            }
            runCatching {
                val opCode = AppOpsManager::class.java
                    .getDeclaredField("OP_PLAY_AUDIO")
                    .apply { isAccessible = true }
                    .getInt(null)
                val serviceManager = Class.forName("android.os.ServiceManager")
                val binder = serviceManager
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.APP_OPS_SERVICE) as? IBinder
                    ?: error("AppOpsService binder unavailable")
                val stub = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
                val appOps = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    ?: error("IAppOpsService unavailable")
                val mode = if (muted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
                appOps.javaClass
                    .getMethod(
                        "setMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(appOps, opCode, uid, packageName, mode)
                NeXtepLog.info(
                    "freewindow_mute",
                    "Applied muted=$muted uid=$uid package=$packageName op=$opCode mode=$mode",
                )
            }.onFailure { error ->
                NeXtepLog.warn("freewindow_mute", "Failed to apply mute uid=$uid package=$packageName", error)
            }
        }

        companion object {
            fun newFilter() = IntentFilter(ACTION_MUTE)
        }
    }
}
