package io.github.loveqianool.freewindow.window

import android.app.AppOpsManager
import android.content.Context
import android.os.IBinder
import android.util.Log

/**
 * Per-window mute implemented through AppOps: setting OP_PLAY_AUDIO to
 * MODE_IGNORED for the app's uid silences its audio playback; MODE_ALLOWED
 * restores it.
 *
 * Called from the systemui process (overlay). Reaches IAppOpsService via
 * reflection; everything is wrapped in try-catch so a failure only logs.
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
     * The actual AppOps call happens in [ServerMuteReceiver].
     */
    fun setMuted(context: Context, packageName: String, uid: Int, muted: Boolean) {
        try {
            val intent = android.content.Intent(ACTION_MUTE).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_MUTED, muted)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "WindowMute: broadcast muted=$muted uid=$uid package=$packageName")
        } catch (e: Throwable) {
            Log.i(TAG, "WindowMute: broadcast failed: ${e.message}")
        }
    }
}
