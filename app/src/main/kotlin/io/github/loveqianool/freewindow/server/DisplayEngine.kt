package io.github.loveqianool.freewindow.server

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface

/**
 * Creates and tracks the VirtualDisplays backing free windows.
 * Display name: "FreeWindow-<id>".
 */
class DisplayEngine(private val context: Context) {
    private val displays = mutableMapOf<Int, VirtualDisplay>()

    /**
     * VirtualDisplay flags never change at runtime; resolve them once
     * instead of reflecting three fields on every display creation.
     */
    private val displayFlags: Int by lazy {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        // TRUSTED, OWN_FOCUS etc. may be hidden; read via reflection.
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP")
        flags
    }

    fun create(id: Int, width: Int, height: Int, dpi: Int, surface: Surface): VirtualDisplay? {
        return create(id, width, height, dpi, surface, releaseFirst = true)
    }

    /**
     * Create a VirtualDisplay. If [releaseFirst] is false, the existing display
     * for [id] is kept alive — used by rebuild where the task must be moved
     * before the old display is released.
     */
    fun create(
        id: Int,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        releaseFirst: Boolean,
    ): VirtualDisplay? {
        if (releaseFirst) release(id)
        val dm = context.getSystemService(DisplayManager::class.java)
        if (dm == null) {
            Log.w(TAG, "DisplayManager unavailable")
            return null
        }
        return try {
            val vd = dm.createVirtualDisplay("$PREFIX$id", width, height, dpi, surface, displayFlags)
            if (vd != null) {
                displays[id] = vd
                Log.i(TAG, "Created display id=$id ${width}x${height} dpi=$dpi")
            } else {
                Log.w(TAG, "DisplayManager returned null id=$id")
            }
            vd
        } catch (e: Throwable) {
            Log.w(TAG, "create failed id=$id", e)
            null
        }
    }

    /** The display backing [id], if one was created and not yet released. */
    fun get(id: Int): VirtualDisplay? = displays[id]

    fun release(id: Int) {
        displays.remove(id)?.let { vd ->
            try {
                vd.release()
                Log.i(TAG, "Released display id=$id")
            } catch (e: Throwable) {
                Log.w(TAG, "release failed id=$id", e)
            }
        }
    }

    // OWN_CONTENT_ONLY removed: with LOCAL IME policy the keyboard shows
    // inside the window, so the virtual display needs to allow system
    // windows (IME). Flag resolution lives in displayFlags above.

    private fun readFlag(name: String): Int = try {
        DisplayManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.getInt(null)
    } catch (e: Throwable) {
        Log.i(TAG, "Flag unavailable: $name")
        0
    }

    companion object {
        const val TAG = "FreeWindow"
        const val PREFIX = "FreeWindow-"
    }
}
