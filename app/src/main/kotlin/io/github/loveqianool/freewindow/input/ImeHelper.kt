package io.github.loveqianool.freewindow.input

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Manages window focusability for free windows so the system IME works
 * without breaking apps underneath.
 *
 * How it works:
 * - A free window defaults to NOT_FOCUSABLE: touches inside are forwarded to
 *   the VirtualDisplay, touches outside fall through (NOT_TOUCH_MODAL), and
 *   the overlay never steals input focus from the system underneath — so the
 *   IME keeps working for underlying apps.
 * - When the user taps inside the window ([MotionEvent.ACTION_DOWN]),
 *   [setFocusable] clears NOT_FOCUSABLE. The window can then take focus, and
 *   the system shows the IME on the main display for the app running on the
 *   VirtualDisplay (the VirtualDisplay carries OWN_CONTENT_ONLY, which keeps
 *   system windows such as the IME off it).
 * - When the user taps outside the window ([MotionEvent.ACTION_OUTSIDE],
 *   delivered via FLAG_WATCH_OUTSIDE_TOUCH), [setFocusable] restores
 *   NOT_FOCUSABLE so focus returns to whatever is underneath.
 */
object ImeHelper {
    private const val TAG = "FreeWindow"

    /** Default flags for a free window's LayoutParams. */
    const val DEFAULT_FLAGS: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    /** True if the window's view is currently focusable (no NOT_FOCUSABLE). */
    fun isFocusable(view: View): Boolean {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        return params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
    }

    /**
     * Dynamically toggles FLAG_NOT_FOCUSABLE on an already-attached window
     * view. No-op when the requested state is already active.
     */
    fun setFocusable(view: View, focusable: Boolean) {
        try {
            val params = view.layoutParams as? WindowManager.LayoutParams
                ?: run {
                    Log.i(TAG, "setFocusable: view has no WindowManager.LayoutParams")
                    return
                }
            if (isFocusable(view) == focusable) return
            params.flags = if (focusable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            val wm = view.context.getSystemService(WindowManager::class.java)
                ?: run {
                    Log.i(TAG, "setFocusable: WindowManager unavailable")
                    return
                }
            wm.updateViewLayout(view, params)
            Log.i(TAG, "setFocusable: focusable=$focusable")
        } catch (e: Throwable) {
            Log.i(TAG, "setFocusable failed: ${e.message}")
        }
    }
}
