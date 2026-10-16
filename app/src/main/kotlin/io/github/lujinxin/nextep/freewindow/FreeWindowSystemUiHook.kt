package io.github.lujinxin.nextep.freewindow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
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
 * No floating ball: a persistent notification is the entry point. Tapping it
 * opens the app-list panel; parked (background) windows are listed in the
 * notification itself with one-tap switch actions, plus an exit action.
 * Each free window is its own system window, so touches outside fall through
 * naturally. The old workspace takeover UI is no longer installed.
 */
object FreeWindowSystemUiHook {
    private var installed = false

    private const val CHANNEL_ID = "freewindow"
    private const val NOTIF_ID = 0x46573031 // "FW01"
    private const val ACTION_SHOW_PANEL =
        "io.github.loveqianool.nextep.freewindow.SHOW_PANEL"
    private const val ACTION_UNPARK =
        "io.github.loveqianool.nextep.freewindow.UNPARK"
    private const val ACTION_EXIT =
        "io.github.loveqianool.nextep.freewindow.EXIT"
    private const val EXTRA_WINDOW_ID = "windowId"

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
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
            ?: run {
                NeXtepLog.warn("freewindow", "NotificationManager unavailable")
                return
            }
        val metrics = appContext.resources.displayMetrics
        val density = metrics.density

        fun dp(v: Int) = (v * density).toInt()

        var panelRef: AppListPanel? = null
        var panelRect: Rect? = null
        var notifReceiver: BroadcastReceiver? = null

        val manager = FreeWindowManager(appContext, windowManager, windowType)

        // ------------------------------------------------------- notification

        runCatching {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FreeWindow", NotificationManager.IMPORTANCE_LOW)
            )
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "Notification channel failed", error)
        }

        fun pendingIntent(action: String, windowId: Int = -1): PendingIntent {
            val intent = Intent(action).apply {
                setPackage("com.android.systemui")
                if (windowId >= 0) putExtra(EXTRA_WINDOW_ID, windowId)
            }
            return PendingIntent.getBroadcast(
                appContext,
                action.hashCode() * 31 + windowId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun drawableToIcon(d: Drawable?): Icon? = runCatching {
            if (d == null) return@runCatching null
            val bitmap = if (d is BitmapDrawable && d.bitmap != null) {
                d.bitmap
            } else {
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(bmp))
                }
            } ?: return@runCatching null
            Icon.createWithBitmap(bitmap)
        }.getOrNull()

        fun updateNotification() {
            val parked = manager.windows().filter { it.mode == FreeWindowMode.PARKED }
            val builder = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("FreeWindow")
                .setOngoing(true)
                .setContentIntent(pendingIntent(ACTION_SHOW_PANEL))
            if (parked.isEmpty()) {
                builder.setContentText("点击打开应用列表")
            } else {
                builder.setContentText("${parked.size} 个后台窗口，点击切换")
                val style = Notification.InboxStyle()
                    .setBigContentTitle("FreeWindow · 后台")
                parked.take(6).forEach { state ->
                    style.addLine(state.title.ifEmpty { state.packageName ?: "?" })
                }
                builder.setStyle(style)
            }
            // One-tap switch for parked windows (system shows what fits).
            parked.forEach { state ->
                val icon = drawableToIcon(state.icon)
                    ?: Icon.createWithResource(appContext, android.R.drawable.ic_dialog_info)
                builder.addAction(
                    Notification.Action.Builder(
                        icon,
                        state.title.ifEmpty { state.packageName ?: "?" },
                        pendingIntent(ACTION_UNPARK, state.windowId),
                    ).build()
                )
            }
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(appContext, android.R.drawable.ic_dialog_info),
                    "退出",
                    pendingIntent(ACTION_EXIT),
                ).build()
            )
            runCatching {
                notificationManager.notify(NOTIF_ID, builder.build())
            }.onFailure { error ->
                NeXtepLog.warn("freewindow", "notify failed", error)
            }
        }

        // --------------------------------------------------------------- panel

        fun positionPanel(): Rect? {
            val pw = dp(320)
            val ph = (metrics.heightPixels * 0.62).toInt()
            val left = (metrics.widthPixels - pw - dp(12)).coerceAtLeast(0)
            val top = ((metrics.heightPixels - ph) / 2).coerceIn(0, max(0, metrics.heightPixels - ph))
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
        }

        /** User-requested exit: tear everything down. */
        fun exitOverlay() {
            NeXtepLog.info("freewindow", "Exiting overlay by user request")
            runCatching { manager.shutdown() }
            panelRef?.let { runCatching { windowManager.removeViewImmediate(it) } }
            notifReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            notifReceiver = null
            runCatching { notificationManager.cancel(NOTIF_ID) }
            installed = false
            runCatching {
                Toast.makeText(appContext, "FreeWindow 已退出", Toast.LENGTH_SHORT).show()
            }
        }

        // --------------------------------------------------------------- hosts

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

        // --------------------------------------------------------------- setup

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SHOW_PANEL -> showPanel()
                    ACTION_UNPARK -> {
                        val id = intent.getIntExtra(EXTRA_WINDOW_ID, -1)
                        if (id >= 0) {
                            manager.unparkWindow(id)
                            hidePanel()
                        }
                    }
                    ACTION_EXIT -> exitOverlay()
                }
            }
        }.also { notifReceiver = it }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_SHOW_PANEL)
                    addAction(ACTION_UNPARK)
                    addAction(ACTION_EXIT)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { error ->
            NeXtepLog.warn("freewindow", "Receiver register failed", error)
        }

        val panel = AppListPanel(appContext, panelHost).also { panelRef = it }
        panel.visibility = View.GONE
        // Added on demand by showPanel().

        manager.setListener(object : FreeWindowManager.Listener {
            override fun onChanged() {
                updateNotification()
                panelRef?.let { if (it.visibility == View.VISIBLE) it.refresh() }
            }
        })
        updateNotification()

        NeXtepLog.info("freewindow", "Free-window overlay ready")
    }
}
