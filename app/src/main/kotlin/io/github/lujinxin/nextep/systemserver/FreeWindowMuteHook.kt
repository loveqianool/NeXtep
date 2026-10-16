package io.github.lujinxin.nextep.systemserver

import android.content.Context
import io.github.lujinxin.nextep.freewindow.MuteController
import io.github.lujinxin.nextep.logging.NeXtepLog

/**
 * Registers [MuteController.FreeWindowMuteReceiver] inside system_server so
 * per-window mute requests from the overlay can call AppOpsService in-process.
 */
object FreeWindowMuteHook {
    fun install() {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
                ?: error("ActivityThread unavailable")
            val context = activityThread.javaClass
                .getMethod("getSystemContext")
                .invoke(activityThread) as? Context
                ?: error("system context unavailable")
            context.registerReceiver(
                MuteController.FreeWindowMuteReceiver(),
                MuteController.FreeWindowMuteReceiver.newFilter(),
                Context.RECEIVER_NOT_EXPORTED,
            )
            NeXtepLog.info("freewindow_mute", "Mute receiver registered in system_server")
        }.onFailure { error ->
            NeXtepLog.warn("freewindow_mute", "Failed to register mute receiver", error)
        }
    }
}
