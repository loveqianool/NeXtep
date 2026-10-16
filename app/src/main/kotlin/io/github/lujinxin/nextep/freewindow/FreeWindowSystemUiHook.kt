package io.github.lujinxin.nextep.freewindow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemUiWindowTypeResolver
import kotlin.math.max

/**
 * SystemUI-side entry for the free-window mode.
 *
 * Every UI element is its own system window — the floating ball, the app-list
 * panel, and each free window — so touches outside any of them fall through
 * to the system underneath naturally (no fullscreen overlay, no
 * touchable-insets hack; the insets approach proved unreliable on some ROMs).
 * The old workspace takeover UI is no longer installed.
 */
object FreeWindowSystemUiHook {
    private var installed = false

    fun install() {
        // Trigger via polling instead of hooking Application.onCreate: on some
        // ROMs (LineageOS 24) the concrete SystemUI Application subclass
        // overrides onCreate and a hook on the base method never fires.
        // ActivityThread.currentApplication() is a public static that returns
        // null until the app is created, so polling it on the main thread is
        // ROM-independent and needs no method hook for the trigger itself.
        runCatching {
            val mainLooper = android.os.Looper.getMainLooper() ?: run {
                NeXtepLog.warn("freewindow", "No main looper; overlay not installed")
                return
            }
            val handler = android.os.Handler(mainLooper)
            var attempts = 0
            fun poll() {
                if (installed) return
                // Reflective: ActivityThread is not visible to the compile SDK.
                val app = runCatching {
                    val atClass = Class.forName("android.app.ActivityThread")
                    atClass.getMethod("currentApplication").invoke(null) as? Context
                }.getOrNull()
                if (app != null) {
                    runCatching { onSystemUiReady(app) }
                        .onFailure { error -> NeXtepLog.warn("freewindow", "Overlay setup failed", error) }
                    return
                }
                attempts++
                if (attempts > 40) {
                    NeXtepLog.warn("freewindow", "SystemUI Application never appeared; overlay not installed")
                    return
                }
                handler.postDelayed({ poll() }, 250)
            }
            handler.post { poll() }
            NeXtepLog.info("freewindow", "Installed SystemUI overlay trigger (polling)")
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "Failed to install overlay trigger", error)
        }
    }

    private fun onSystemUiReady(rawContext: Context?) {
        val appContext = rawContext?.applicationContext ?: rawContext ?: return
        if (installed) return
        installed = true

        val windowManager = appContext.getSystemService(WindowManager::class.java)
            ?: run {
                NeXtepLog.warn("freewindow", "WindowManager unavailable")
                return
            }
        val windowType = SystemUiWindowTypeResolver.resolve()
            ?: run {
                NeXtepLog.warn("freewindow", "No overlay window type")
                return
            }
        val metrics = appContext.resources.displayMetrics
        val density = metrics.density

        fun dp(v: Int) = (v * density).toInt()

        // ---------------------------------------------------------------- ball
        val ballSize = dp(56)
        var ballX = metrics.widthPixels - ballSize - dp(12)
        var ballY = metrics.heightPixels / 2 - ballSize / 2
        var ballRef: FloatingBallView? = null
        var panelRef: AppListPanel? = null
        var panelRect: Rect? = null

        fun ballParams() = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
            title = "NeXtepFreeBall"
        }

        /** Keep the ball above windows and the panel. */
        fun ensureBallOnTop() {
            val ball = ballRef ?: return
            runCatching {
                windowManager.removeViewImmediate(ball)
                windowManager.addView(ball, ballParams())
            }.onFailure { error -> NeXtepLog.warn("freewindow", "Ball restack failed", error) }
        }

        // --------------------------------------------------------------- panel

        fun positionPanel(): Rect? {
            val bw = ballSize
            val pw = dp(320)
            val ph = (metrics.heightPixels * 0.62).toInt()
            val bcx = ballX + bw / 2
            val bcy = ballY + bw / 2
            val left = if (bcx < metrics.widthPixels / 2) {
                (ballX + bw + dp(8)).coerceAtMost(metrics.widthPixels - pw)
            } else {
                (ballX - dp(8) - pw).coerceAtLeast(0)
            }
            val top = (bcy - ph / 2).coerceIn(0, max(0, metrics.heightPixels - ph))
            return Rect(left, top, left + pw, top + ph).also { panelRect = it }
        }

        fun hidePanel() {
            panelRef?.let { panel ->
                runCatching { windowManager.removeView(panel) }
                panel.visibility = View.GONE
            }
            panelRect = null
        }

        fun showPanel() {
            val panel = panelRef ?: return
            val rect = positionPanel() ?: return
            val params = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                windowType,
                // Focusable (no NOT_FOCUSABLE) so the search field takes input;
                // NOT_TOUCH_MODAL keeps outside touches falling through.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.left
                y = rect.top
                title = "NeXtepFreePanel"
            }
            runCatching {
                runCatching { windowManager.removeViewImmediate(panel) }
                windowManager.addView(panel, params)
            }.onFailure { error ->
                NeXtepLog.warn("freewindow", "Panel addView failed", error)
                return
            }
            panel.visibility = View.VISIBLE
            panel.refresh()
            ensureBallOnTop()
        }

        fun moveBallTo(x: Int, y: Int) {
            ballX = x.coerceIn(0, max(0, metrics.widthPixels - ballSize))
            ballY = y.coerceIn(0, max(0, metrics.heightPixels - ballSize))
            ballRef?.let { ball ->
                runCatching { windowManager.updateViewLayout(ball, ballParams()) }
                    .onFailure { error -> NeXtepLog.warn("freewindow", "Ball move failed", error) }
            }
            if (panelRef?.visibility == View.VISIBLE) {
                // Keep the panel anchored while the ball moves.
                showPanel()
            }
        }

        val manager = FreeWindowManager(
            appContext,
            windowManager,
            windowType,
            ballBounds = { Rect(ballX, ballY, ballX + ballSize, ballY + ballSize) },
            restackOverlay = { ensureBallOnTop() },
        )

        /** User-requested exit: tear everything down. */
        fun exitOverlay() {
            NeXtepLog.info("freewindow", "Exiting overlay by user request")
            runCatching { manager.shutdown() }
            panelRef?.let { runCatching { windowManager.removeViewImmediate(it) } }
            ballRef?.let { runCatching { windowManager.removeViewImmediate(it) } }
            installed = false
            runCatching {
                Toast.makeText(appContext, "FreeWindow 已退出", Toast.LENGTH_SHORT).show()
            }
        }

        // --------------------------------------------------------------- hosts

        val ballHost = object : FloatingBallHost {
            override fun onBallMove(dx: Int, dy: Int) {
                moveBallTo(ballX + dx, ballY + dy)
            }

            override fun onBallTap() {
                val panel = panelRef ?: return
                if (panel.visibility == View.VISIBLE) hidePanel() else showPanel()
            }

            override fun onBallLongPress() {
                exitOverlay()
            }

            override fun onBallRelease() {
                val targetX = if (ballX + ballSize / 2 < metrics.widthPixels / 2) {
                    0
                } else {
                    metrics.widthPixels - ballSize
                }
                if (targetX == ballX) return
                val startX = ballX
                val startY = ballY
                ValueAnimator.ofInt(startX, targetX).apply {
                    duration = 200
                    addUpdateListener { anim ->
                        val v = anim.animatedValue as Int
                        moveBallTo(v, startY)
                    }
                    start()
                }
            }
        }

        val panelHost = object : AppListPanelHost {
            // Only parked (hidden) windows are listed; tap restores them.
            override fun runningWindows(): List<FreeWindowState> =
                manager.windows().filter { it.mode == FreeWindowMode.PARKED }

            override fun curatedApps(query: String): List<AppEntryLite> {
                val all = runCatching {
                    LauncherAppRepository(appContext).load(includeProfiles = false)
                }.getOrElse { emptyList() }
                return all
                    .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    .map { AppEntryLite(it.component, it.label, it.icon) }
            }

            override fun onWindowRowTap(windowId: Int) {
                manager.unparkWindow(windowId)
                hidePanel()
            }

            override fun onAppTap(entry: AppEntryLite) {
                val id = manager.launchApp(entry.component, entry.label)
                if (id < 0) {
                    NeXtepLog.warn("freewindow", "Launch rejected: window cap")
                }
                hidePanel()
            }

            override fun onPanelClose() = hidePanel()
        }

        // --------------------------------------------------------------- views

        val ball = FloatingBallView(appContext, ballHost).also { ballRef = it }
        runCatching {
            windowManager.addView(ball, ballParams())
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "Ball addView failed", error)
            return
        }

        val panel = AppListPanel(appContext, panelHost).also { panelRef = it }
        panel.visibility = View.GONE
        // Added on demand by showPanel().

        manager.setListener(object : FreeWindowManager.Listener {
            override fun onChanged() {
                ballRef?.setParkedCount(manager.parkedCount())
                panelRef?.let { if (it.visibility == View.VISIBLE) it.refresh() }
            }
        })

        NeXtepLog.info("freewindow", "Free-window overlay ready")
    }
}
