package io.github.loveqianool.freewindow.input

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Forwards touch events received on a free window's TextureView into the
 * VirtualDisplay backing that window.
 *
 * The TextureView is laid out at exactly the VirtualDisplay's size, so
 * coordinates map 1:1 and need no translation — only the target display id
 * has to be stamped on the event before injection.
 */
object TouchForwarder {
    private const val TAG = "FreeWindow"

    /**
     * AOSP InputManager inject modes: ASYNC=0, WAIT_FOR_RESULT=1,
     * WAIT_FOR_FINISH=2. Must be ASYNC — touch runs at 60-120Hz on the UI
     * thread and must never block on dispatch.
     */
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    private val injectMethod: java.lang.reflect.Method? by lazy {
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.i(TAG, "injectInputEvent lookup failed: ${e.message}")
            null
        }
    }

    private val setDisplayIdMethod: java.lang.reflect.Method? by lazy {
        try {
            InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.i(TAG, "setDisplayId lookup failed: ${e.message}")
            null
        }
    }

    /** Cached — the instance never changes; don't reflect per touch event. */
    private val inputManager: Any? by lazy {
        try {
            InputManager::class.java.getMethod("getInstance")
                .apply { isAccessible = true }
                .invoke(null)
        } catch (e: Throwable) {
            Log.i(TAG, "InputManager.getInstance failed: ${e.message}")
            null
        }
    }

    /**
     * Inject [event] as-is, without copying it. The caller owns [event] and
     * must recycle it after this returns. Use this only when the event is
     * already a caller-owned copy (see [FreeWindowView.forwardOrdered]);
     * async inject copies internally before returning, exactly as it does
     * for the copy made by [forward], so recycling after return is safe.
     */
    fun forwardOwned(event: MotionEvent, displayId: Int): Boolean {
        val manager = inputManager ?: return false
        val inject = injectMethod ?: return false
        if (!stampDisplayId(event, displayId)) return false
        return try {
            inject.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.i(TAG, "injectInputEvent failed: ${e.message}")
            false
        }
    }

    /**
     * Stamps [displayId] on a copy of [event] and injects it asynchronously
     * into the input system. Returns true if the event was accepted.
     *
     * The framework owns [event] (pooled); we inject a copy so async dispatch
     * can't race the pool recycle.
     */
    fun forward(event: MotionEvent, displayId: Int): Boolean {
        val manager = inputManager ?: return false
        val inject = injectMethod ?: return false
        val copy = MotionEvent.obtain(event)
        try {
            if (!stampDisplayId(copy, displayId)) return false
            return try {
                inject.invoke(manager, copy, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
            } catch (e: Throwable) {
                Log.i(TAG, "injectInputEvent failed: ${e.message}")
                false
            }
        } finally {
            copy.recycle()
        }
    }

    /**
     * Routes the event at the target display via reflection.
     */
    private fun stampDisplayId(event: MotionEvent, displayId: Int): Boolean {
        return try {
            val method = setDisplayIdMethod ?: return false
            method.invoke(event, displayId)
            true
        } catch (e: Throwable) {
            Log.i(TAG, "reflective setDisplayId failed: ${e.message}")
            false
        }
    }
}
