package io.github.loveqianool.freewindow.window

import android.util.Log

/**
 * Per-window mute via broadcast to system_server.
 * See [setMuted] for the Vector caching caveat.
 */
object WindowMute {
    private const val TAG = "FreeWindow"

    /** Broadcast action for mute requests (handled in system_server). */
    const val ACTION_MUTE = "io.github.loveqianool.freewindow.action.MUTE"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_UID = "uid"
    const val EXTRA_MUTED = "muted"

    /**
     * Sends a mute request to system_server (which holds MANAGE_APP_OPS_MODES).
     * The actual AppOps call happens in ServerHook's receiver.
     *
     * NOTE: Requires the system_server to run the new module code. If Vector
     * caches the old dex, the receiver won't exist and mute will silently fail.
     * Workaround: full reboot after APK update (not just `stop; start`).
     */
    fun setMuted(context: android.content.Context, packageName: String, uid: Int, muted: Boolean) {
        try {
            val intent = android.content.Intent(ACTION_MUTE).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_MUTED, muted)
            }
            context.sendBroadcast(intent)
            Log.i("WindowMute: broadcast muted=$muted uid=$uid package=$packageName")
        } catch (e: Throwable) {
            Log.w("WindowMute: broadcast failed", e)
        }
    }
}
