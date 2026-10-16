package io.github.loveqianool.freewindow.window

import io.github.loveqianool.freewindow.FwLog

/**
 * Per-window mute via `appops set` as root.
 * Runs in systemui; no system_server component needed.
 */
object WindowMute {
    private const val TAG = "FreeWindow"

    /**
     * Mutes via `appops set` as root. Runs in systemui; no system_server
     * component needed (avoids the Vector module-reload issue).
     * Executed on a bg thread; never block the UI.
     */
    fun setMuted(packageName: String, uid: Int, muted: Boolean) {
        Thread {
            try {
                val mode = if (muted) "ignore" else "allow"
                val proc = Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "appops set $packageName PLAY_AUDIO $mode"))
                val exit = proc.waitFor()
                FwLog.i("WindowMute: appops set $packageName PLAY_AUDIO $mode exit=$exit")
            } catch (e: Throwable) {
                FwLog.w("WindowMute: appops failed", e)
            }
        }.start()
    }

    // Legacy broadcast API (unused, kept for reference).
    const val ACTION_MUTE = "io.github.loveqianool.freewindow.action.MUTE"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_UID = "uid"
    const val EXTRA_MUTED = "muted"
}
