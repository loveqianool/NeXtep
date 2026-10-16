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

    /** Resize drag preview: show the dashed outline at [rect]. */
    fun onResizePreview(id: Int, rect: android.graphics.Rect)

    /** Resize drag released: commit [rect] as the new window size. */
    fun onResizeCommit(id: Int, rect: android.graphics.Rect)

    /** Current window rect, for the resize handler. */
    fun currentRect(id: Int): android.graphics.Rect

    /** TextureView surface changed size (after resize); rebuild VD if needed. */
    fun onSurfaceSizeChanged(id: Int)
}
