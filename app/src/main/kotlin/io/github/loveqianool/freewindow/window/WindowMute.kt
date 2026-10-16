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

    /** OP_PLAY_AUDIO value; resolved via reflection, 28 as fallback. */
    private val opPlayAudio: Int by lazy {
        runCatching {
            AppOpsManager::class.java
                .getDeclaredField("OP_PLAY_AUDIO")
                .apply { isAccessible = true }
                .getInt(null)
        }.getOrElse {
            Log.i(TAG, "WindowMute: OP_PLAY_AUDIO reflection failed, using 28")
            28
        }
    }

    /** Cached AppOps proxy + setMode; reflection is expensive per toggle. */
    private val appOpsSetMode: Pair<Any, java.lang.reflect.Method>? by lazy {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, Context.APP_OPS_SERVICE) as? IBinder
                ?: return@lazy null
            val stub = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            val appOps = stub
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return@lazy null
            val method = appOps.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            appOps to method
        } catch (e: Throwable) {
            Log.i(TAG, "WindowMute: AppOps lookup failed: ${e.message}")
            null
        }
    }

    fun setMuted(context: Context, packageName: String, uid: Int, muted: Boolean) {
        runCatching {
            val (appOps, setMode) = appOpsSetMode ?: error("AppOpsService unavailable")
            val mode = if (muted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            Log.i(TAG, "WindowMute: invoking setMode op=$opPlayAudio uid=$uid pkg=$packageName mode=$mode")
            setMode.invoke(appOps, opPlayAudio, uid, packageName, mode)
            Log.i(TAG, "WindowMute: set muted=$muted uid=$uid package=$packageName")
        }.onFailure { error ->
            Log.i(TAG, "WindowMute: setMode failed muted=$muted uid=$uid package=$packageName " +
                "error=${error.javaClass.simpleName}: ${error.message}")
            var cause = error.cause
            while (cause != null) {
                Log.i(TAG, "WindowMute: caused by ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
            }
        }
    }
}
