package io.github.lujinxin.nextep.freewindow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.reflect.Proxy
import io.github.lujinxin.nextep.apps.LauncherAppRepository
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemUiWindowTypeResolver
import kotlin.math.max

/**
 * SystemUI-side entry for the free-window mode.
 *
 * Waits for the SystemUI Application to exist, then adds one fullscreen
 * overlay window (TYPE_PHONE, touchable, not focusable) and hosts inside it:
 * [FreeWindowManager] windows, the [FloatingBallView] and the [AppListPanel].
 * The old workspace takeover UI is no longer installed.
 */
object FreeWindowSystemUiHook {
    private var installed = false

    /** InternalInsetsInfo.TOUCHABLE_INSETS_REGION — hidden constant, value 3. */
    private const val TOUCHABLE_INSETS_REGION = 3

    private fun installTouchableRegion(container: FrameLayout, regionOf: () -> Region) {
        runCatching {
            val listenerClass =
                Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, arguments ->
                if (method.name == "onComputeInternalInsets") {
                    val info = arguments?.firstOrNull()
                    if (info != null) {
                        runCatching {
                            info.javaClass
                                .getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                                .invoke(info, TOUCHABLE_INSETS_REGION)
                            val region =
                                info.javaClass.getField("touchableRegion").get(info) as Region
                            region.set(regionOf())
                        }.onFailure { error ->
                            NeXtepLog.warn("freewindow", "Insets compute failed", error)
                        }
                    }
                }
                null
            }
            container.viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                .invoke(container.viewTreeObserver, proxy)
            NeXtepLog.info("freewindow", "Touchable region passthrough installed")
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "Touchable region hook failed", error)
        }
    }

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
                val app = runCatching { android.app.ActivityThread.currentApplication() }.getOrNull()
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

        val container = FrameLayout(appContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NeXtepFreeWindows"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching {
            windowManager.addView(container, params)
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "addView failed", error)
            return
        }
        NeXtepLog.info("freewindow", "Overlay container added")

        var ballRef: FloatingBallView? = null
        var panelRef: AppListPanel? = null
        var panelRect: Rect? = null

        fun dp(v: Int) = (v * density).toInt()

        fun setWindowFocusable(focusable: Boolean) {
            if (focusable) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            runCatching { windowManager.updateViewLayout(container, params) }
                .onFailure { error -> NeXtepLog.warn("freewindow", "updateViewLayout failed", error) }
        }

        val manager = FreeWindowManager(
            appContext,
            container,
            ballBounds = { ballRef?.currentRect()?.let { Rect(it) } },
            restackOverlay = {
                panelRef?.let { panel ->
                    if (panel.visibility == View.VISIBLE && panel.parent != null) {
                        container.removeView(panel)
                        container.addView(panel)
                    }
                }
                ballRef?.let { ball ->
                    if (ball.parent != null) {
                        container.removeView(ball)
                        container.addView(ball)
                    }
                }
            },
        )

        fun clampBall(rect: Rect) {
            val w = rect.width()
            val h = rect.height()
            rect.offsetTo(
                rect.left.coerceIn(0, max(0, metrics.widthPixels - w)),
                rect.top.coerceIn(0, max(0, metrics.heightPixels - h)),
            )
        }

        fun positionPanel() {
            val ball = ballRef ?: return
            val panel = panelRef ?: return
            val br = ball.currentRect()
            val pw = dp(320)
            val ph = (metrics.heightPixels * 0.62).toInt()
            val left = if (br.centerX() < metrics.widthPixels / 2) {
                (br.right + dp(8)).coerceAtMost(metrics.widthPixels - pw)
            } else {
                (br.left - dp(8) - pw).coerceAtLeast(0)
            }
            val top = (br.centerY() - ph / 2).coerceIn(0, max(0, metrics.heightPixels - ph))
            panelRect = Rect(left, top, left + pw, top + ph)
            panel.setPanelRect(panelRect!!)
        }

        fun hidePanel() {
            panelRef?.visibility = View.GONE
            panelRect = null
            setWindowFocusable(false)
        }

        fun showPanel() {
            val panel = panelRef ?: return
            positionPanel()
            panel.visibility = View.VISIBLE
            setWindowFocusable(true)
            panel.refresh()
            if (panel.parent != null) {
                container.removeView(panel)
                container.addView(panel)
            }
            ballRef?.let { ball ->
                if (ball.parent != null) {
                    container.removeView(ball)
                    container.addView(ball)
                }
            }
        }

        val ballHost = object : FloatingBallHost {
            override fun onBallMove(dx: Int, dy: Int) {
                val ball = ballRef ?: return
                val rect = ball.currentRect().apply {
                    offset(dx, dy)
                    clampBall(this)
                }
                ball.setBallRect(rect)
                if (panelRef?.visibility == View.VISIBLE) positionPanel()
            }

            override fun onBallTap() {
                val panel = panelRef ?: return
                if (panel.visibility == View.VISIBLE) hidePanel() else showPanel()
            }

            override fun onBallRelease() {
                val ball = ballRef ?: return
                val rect = ball.currentRect()
                val targetLeft = if (rect.centerX() < metrics.widthPixels / 2) {
                    0
                } else {
                    metrics.widthPixels - rect.width()
                }
                if (targetLeft == rect.left) return
                ValueAnimator.ofInt(rect.left, targetLeft).apply {
                    duration = 200
                    addUpdateListener { anim ->
                        val v = anim.animatedValue as Int
                        ball.setBallRect(Rect(v, rect.top, v + rect.width(), rect.top + rect.height()))
                    }
                    start()
                }
            }
        }

        val panelHost = object : AppListPanelHost {
            override fun runningWindows(): List<FreeWindowState> = manager.windows()

            override fun curatedApps(query: String): List<AppEntryLite> {
                val all = runCatching {
                    LauncherAppRepository(appContext).load(includeProfiles = false)
                }.getOrElse { emptyList() }
                return all
                    .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    .map { AppEntryLite(it.component, it.label, it.icon) }
            }

            override fun onWindowRowTap(windowId: Int) {
                manager.bringToFront(windowId)
                hidePanel()
            }

            override fun onWindowRowLongPress(windowId: Int) {
                val state = manager.windows().firstOrNull { it.windowId == windowId } ?: return
                if (state.mode == FreeWindowMode.MAXIMIZED) {
                    manager.restoreWindowed(windowId)
                } else {
                    manager.closeWindow(windowId)
                }
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

        val ballSize = dp(56)
        val ball = FloatingBallView(appContext, ballHost).also { ballRef = it }
        container.addView(ball)
        ball.setBallRect(
            Rect(
                metrics.widthPixels - ballSize - dp(12),
                metrics.heightPixels / 2 - ballSize / 2,
                metrics.widthPixels - dp(12),
                metrics.heightPixels / 2 + ballSize / 2,
            ),
        )

        val panel = AppListPanel(appContext, panelHost).also { panelRef = it }
        panel.visibility = View.GONE
        container.addView(panel)

        // The overlay is fullscreen: restrict its touchable region to the ball,
        // open panel and live windows so touches elsewhere fall through to the
        // system underneath. Same mechanism TopAppStripView used for passthrough.
        installTouchableRegion(container) {
            val region = Region()
            for (state in manager.windows()) {
                if (state.mode == FreeWindowMode.PARKED) continue
                region.union(
                    if (state.mode == FreeWindowMode.MAXIMIZED) {
                        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                    } else {
                        Rect(state.rect)
                    },
                )
            }
            ballRef?.let { region.union(it.currentRect()) }
            if (panel.visibility == View.VISIBLE) {
                panelRect?.let { region.union(it) }
            }
            region
        }

        manager.setListener(object : FreeWindowManager.Listener {
            override fun onChanged() {
                ballRef?.setParkedCount(manager.parkedCount())
                panelRef?.let { if (it.visibility == View.VISIBLE) it.refresh() }
            }
        })

        NeXtepLog.info("freewindow", "Free-window overlay ready")
    }
}
