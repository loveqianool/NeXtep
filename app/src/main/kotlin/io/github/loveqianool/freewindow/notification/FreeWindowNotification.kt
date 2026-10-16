package io.github.loveqianool.freewindow.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Persistent notification for the free-window module.
 *
 * - Tapping the notification body sends [ACTION_SHOW_PANEL] (explicit broadcast
 *   to the SystemUI package) to open the app panel.
 * - Parked apps are listed with [Notification.InboxStyle]; each gets a "切换"
 *   action that sends [ACTION_UNPARK] carrying [EXTRA_WINDOW_ID].
 * - An "退出" action sends [ACTION_EXIT].
 *
 * The three actions are received by an internal [BroadcastReceiver] registered
 * with [Context.RECEIVER_NOT_EXPORTED] and forwarded to [listener] in the
 * process where [show] was called (the SystemUI process via the Xposed module).
 */
object FreeWindowNotification {

    const val ACTION_SHOW_PANEL = "io.github.loveqianool.freewindow.SHOW_PANEL"
    const val ACTION_UNPARK = "io.github.loveqianool.freewindow.UNPARK"
    const val ACTION_CLOSE = "io.github.loveqianool.freewindow.CLOSE"
    const val ACTION_EXIT = "io.github.loveqianool.freewindow.EXIT"

    const val EXTRA_WINDOW_ID = "io.github.loveqianool.freewindow.extra.WINDOW_ID"

    private const val TAG = "FreeWindow"
    private const val CHANNEL_ID = "freewindow"
    private const val NOTIFICATION_ID = 41

    /** A parked (hidden) or maximized free window shown in the notification. */
    data class ParkedApp(
        val windowId: Int,
        val label: String,
        val isMaximized: Boolean = false,
    )

    interface Listener {
        fun onShowPanel()
        fun onUnpark(windowId: Int)
        fun onClose(windowId: Int)
        fun onExit()
    }

    var listener: Listener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SHOW_PANEL -> {
                    Log.i(TAG, "notification: show panel")
                    listener?.onShowPanel()
                }
                ACTION_UNPARK -> {
                    val windowId = intent.getIntExtra(EXTRA_WINDOW_ID, -1)
                    Log.i(TAG, "notification: unpark window=$windowId")
                    if (windowId >= 0) listener?.onUnpark(windowId)
                }
                ACTION_CLOSE -> {
                    val windowId = intent.getIntExtra(EXTRA_WINDOW_ID, -1)
                    Log.i(TAG, "notification: close window=$windowId")
                    if (windowId >= 0) listener?.onClose(windowId)
                }
                ACTION_EXIT -> {
                    Log.i(TAG, "notification: exit")
                    listener?.onExit()
                }
            }
        }
    }

    private var receiverRegistered = false

    /**
     * Show the persistent notification and register the action receiver.
     * Safe to call multiple times.
     */
    fun show(context: Context) {
        ensureChannel(context)
        registerReceiver(context)
        update(context, emptyList())
        Log.i(TAG, "notification shown")
    }

    /** Rebuild the notification with the current parked apps. */
    fun update(context: Context, parkedApps: List<ParkedApp>) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(context, parkedApps))
        Log.i(TAG, "notification updated: ${parkedApps.size} parked")
    }

    /** Remove the notification and unregister the receiver. */
    fun hide(context: Context) {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        Log.i(TAG, "notification hidden")
    }

    private fun buildNotification(context: Context, parkedApps: List<ParkedApp>): Notification {
        val style = Notification.InboxStyle().apply {
            setBigContentTitle("FreeWindow")
            if (parkedApps.isEmpty()) {
                addLine("暂无后台应用")
            } else {
                parkedApps.forEach { addLine(it.label) }
            }
            setSummaryText(
                if (parkedApps.isEmpty()) "点击打开应用面板"
                else "后台 ${parkedApps.size} 个应用",
            )
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FreeWindow")
            .setContentText(
                if (parkedApps.isEmpty()) "点击打开应用面板"
                else "后台 ${parkedApps.size} 个应用，点击切换",
            )
            .setContentIntent(broadcastIntent(context, ACTION_SHOW_PANEL, requestCode = 1, null))
            .setStyle(style)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)

        parkedApps.forEach { app ->
            // "切换" restores a parked window (or brings a maximized one to front).
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    if (app.isMaximized) "前台" else "切换",
                    broadcastIntent(context, ACTION_UNPARK, requestCode = 100 + app.windowId) {
                        putExtra(EXTRA_WINDOW_ID, app.windowId)
                    },
                ).build(),
            )
            // "关闭" closes the window.
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "关闭",
                    broadcastIntent(context, ACTION_CLOSE, requestCode = 200 + app.windowId) {
                        putExtra(EXTRA_WINDOW_ID, app.windowId)
                    },
                ).build(),
            )
        }

        builder.addAction(
            Notification.Action.Builder(
                null,
                "退出",
                broadcastIntent(context, ACTION_EXIT, requestCode = 2, null),
            ).build(),
        )

        return builder.build()
    }

    private fun broadcastIntent(
        context: Context,
        action: String,
        requestCode: Int,
        fill: (Intent.() -> Unit)?,
    ): PendingIntent {
        val intent = Intent(action).apply {
            // Explicit: only the SystemUI process (where this module runs) handles it.
            setPackage("com.android.systemui")
            fill?.invoke(this)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FreeWindow",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "自由窗口常驻通知"
                },
            )
        }
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_PANEL)
            addAction(ACTION_UNPARK)
            addAction(ACTION_CLOSE)
            addAction(ACTION_EXIT)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }
}
