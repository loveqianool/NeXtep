package io.github.loveqianool.freewindow.window

import android.content.ComponentName

/**
 * State of one free window.
 *
 * @param displayId the VirtualDisplay backing this window (-1 until created).
 * @param taskId the app task on the display (-1 until resolved).
 */
data class WindowRecord(
    val id: Int,
    val view: FreeWindowView,
    val displayId: Int,
    val taskId: Int,
    val componentName: ComponentName,
    val isParked: Boolean,
    val isMaximized: Boolean,
    val isMuted: Boolean,
)
