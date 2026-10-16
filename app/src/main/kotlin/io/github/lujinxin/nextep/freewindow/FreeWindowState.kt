package io.github.lujinxin.nextep.freewindow

import android.content.ComponentName
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Surface

/** Title-bar height in dp. The app content (TextureView / VirtualDisplay)
 * sits strictly below it so the bar never covers app UI. */
const val TITLE_BAR_DP = 44

/** Window lifecycle mode for a free window. */
enum class FreeWindowMode {
    /** Normal floating window with a free rect. */
    WINDOWED,

    /** Fullscreen-sized window; other windows and the ball stay above it. */
    MAXIMIZED,

    /** Hidden from the overlay; the VirtualDisplay and task are kept alive. */
    PARKED,
}

/** Immutable snapshot of one free window, handed to UI layers. */
data class FreeWindowState(
    val windowId: Int,
    val component: ComponentName?,
    val packageName: String?,
    val uid: Int,
    val taskId: Int = -1,
    val displayId: Int = -1,
    /** Windowed-mode rect in screen pixels. Ignored while MAXIMIZED/PARKED. */
    val rect: Rect = Rect(),
    val mode: FreeWindowMode = FreeWindowMode.WINDOWED,
    val muted: Boolean = false,
    val title: String = "",
    val icon: Drawable? = null,
)

/**
 * Callbacks from [FreeWindowView] into [FreeWindowManager].
 * All coordinates are screen pixels; the overlay container is fullscreen at (0,0).
 */
interface FreeWindowViewHost {
    fun onWindowTap(windowId: Int)
    fun onWindowMove(windowId: Int, dx: Int, dy: Int)
    fun onWindowResizePreview(windowId: Int, rect: Rect)
    fun onWindowResizeCommit(windowId: Int, rect: Rect)
    fun onWindowClose(windowId: Int)
    fun onWindowPark(windowId: Int)
    fun onWindowMaximizeToggle(windowId: Int)
    fun onWindowMuteToggle(windowId: Int)
    fun onWindowSurfaceReady(windowId: Int, surface: Surface)
    /** The VirtualDisplay id backing the window, or null if none. */
    fun displayIdForWindow(windowId: Int): Int?
}

interface AppListPanelHost {
    /** Parked (hidden) windows, for one-tap restore. */
    fun runningWindows(): List<FreeWindowState>
    fun curatedApps(query: String): List<AppEntryLite>
    fun onWindowRowTap(windowId: Int)
    fun onAppTap(entry: AppEntryLite)
    fun onPanelClose()
}

/** Minimal app info for the panel; avoids depending on the full repository type. */
data class AppEntryLite(
    val component: ComponentName,
    val label: String,
    val icon: Drawable?,
)
