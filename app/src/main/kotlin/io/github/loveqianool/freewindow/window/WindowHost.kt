package io.github.loveqianool.freewindow.window

/**
 * Callbacks from [FreeWindowView]/[TitleBar] into [WindowManager].
 */
interface WindowHost {
    /** User tapped inside the window content. */
    fun onTap(id: Int)

    /** Park button (▽): hide the window, keep the app running. */
    fun onPark(id: Int)

    /** Maximize button (⛶): toggle between windowed and maximized. */
    fun onToggleMaximize(id: Int)

    /** Mute button (🔊/🔇): toggle per-window audio mute. */
    fun onToggleMute(id: Int)

    /** Close button (✕). */
    fun onClose(id: Int)

    /** Title-bar drag: move the window by (dx, dy) pixels. */
    fun onMove(id: Int, dx: Int, dy: Int)

    /** Resize drag released: commit [rect] as the new window size. */
    fun onResizeCommit(id: Int, rect: android.graphics.Rect)

    /** Current window rect, for the resize handler. */
    fun currentRect(id: Int): android.graphics.Rect

    /** Usable display area (minus system bars / cutout), for resize preview. */
    fun usableRect(id: Int): android.graphics.Rect

    /** Title bar visibility changed; content size changed, resize the VD. */
    fun onContentSizeChanged(id: Int)

    /** Device configuration changed (e.g. rotation): re-clamp to the new usable area. */
    fun onConfigurationChanged(id: Int)
}
