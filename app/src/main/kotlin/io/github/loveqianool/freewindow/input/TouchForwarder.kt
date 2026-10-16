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

    /** Mirrors the hidden InputManager.INJECT_INPUT_EVENT_MODE_ASYNC. */
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 2

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

    /**
     * Stamps [displayId] on [event] and injects it asynchronously into the
     * input system. Returns true if the event was accepted.
     */
    fun forward(event: MotionEvent, displayId: Int): Boolean {
        val inputManager = try {
            InputManager.getInstance()
        } catch (e: Throwable) {
            Log.i(TAG, "InputManager.getInstance failed: ${e.message}")
            return false
        }
        val inject = injectMethod ?: return false
        if (!stampDisplayId(event, displayId)) return false
        return try {
            inject.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.i(TAG, "injectInputEvent failed: ${e.message}")
            false
        }
    }

    /**
     * Routes the event at the target display. InputEvent.setDisplayId is a
     * public API since 34; the reflection path is kept for older platforms.
     */
    private fun stampDisplayId(event: MotionEvent, displayId: Int): Boolean {
        try {
            event.setDisplayId(displayId)
            return true
        } catch (e: Throwable) {
            Log.i(TAG, "direct setDisplayId failed, trying reflection: ${e.message}")
        }
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
