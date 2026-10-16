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

    fun create(id: Int, width: Int, height: Int, dpi: Int, surface: Surface): VirtualDisplay? {
        release(id)
        val dm = context.getSystemService(DisplayManager::class.java)
        if (dm == null) {
            Log.w(TAG, "DisplayManager unavailable")
            return null
        }
        return try {
            val vd = dm.createVirtualDisplay("$PREFIX$id", width, height, dpi, surface, resolveFlags())
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

    fun releaseAll() {
        displays.keys.toList().forEach(::release)
    }

    private fun resolveFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        // TRUSTED, OWN_FOCUS etc. may be hidden; read via reflection.
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
        flags = flags or readFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP")
        return flags
    }

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
