package io.github.loveqianool.freewindow.notification

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * App panel: a dialog with window sections plus the app list.
 *
 * - "当前": visible windows (including maximized ones).
 * - "后台": parked windows.
 * - "应用": launchable apps queried from PackageManager, filterable through
 *   the search box at the top; tapping one fires [onAppSelected].
 *
 * Window rows are built from [getWindows] on every refresh, so maximize /
 * park / close actions can stay in the panel (they refresh the rows instead
 * of dismissing it). Only tapping a row (go to a window) or an app (launch
 * it) dismisses the panel. The dialog uses
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] so it can be shown
 * from the SystemUI process, and it is focusable so the search box gets IME.
 */
class AppPanel(
    private val context: Context,
    private val getWindows: () -> List<FreeWindowNotification.ParkedApp>,
    private val onAppSelected: (ComponentName) -> Unit,
    private val onWindowSelected: (windowId: Int) -> Unit,
    private val onWindowMaximized: (windowId: Int) -> Unit,
    private val onWindowClosed: (windowId: Int) -> Unit,
    private val onWindowParked: (windowId: Int) -> Unit,
) {

    private data class AppEntry(
        val component: ComponentName,
        val label: String,
        val resolveInfo: android.content.pm.ResolveInfo,
    )

    private var dialog: Dialog? = null
    private var windowContainer: LinearLayout? = null
    private var appRows: LinearLayout? = null
    private var allApps: List<AppEntry> = emptyList()
    private var currentQuery: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun show() {
        if (isShowing()) return
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(TextView(context).apply {
            text = "FreeWindow"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(8))
        })

        val search = EditText(context).apply {
            hint = "搜索应用"
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(search)

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(listContainer)
        }
        root.addView(scroll)

        // Window sections (rebuilt by refreshWindowSections() after every
        // in-panel action, so the panel stays open for batch operations).
        val windowBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        listContainer.addView(windowBox)
        windowContainer = windowBox
        refreshWindowSections()

        // Launchable apps.
        listContainer.addView(sectionHeader("应用"))
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        listContainer.addView(rows)
        appRows = rows
        rows.addView(TextView(context).apply {
            text = "加载中…"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                // Debounce: recreating every row per keystroke is the
                // panel's most expensive operation; wait until typing pauses.
                pendingSearch?.let { mainHandler.removeCallbacks(it) }
                val task = Runnable { renderAppRows(currentQuery) }
                pendingSearch = task
                mainHandler.postDelayed(task, 150)
            }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.85).toInt(), (metrics.heightPixels * 0.7).toInt())
            setGravity(Gravity.CENTER)
        }

        this.dialog = dialog
        dialog.show()
        Log.i(TAG, "app panel shown")
        loadAppsAsync()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        windowContainer = null
        appRows = null
    }

    /** Rebuild the "当前"/"后台" window sections from the current state. */
    fun refreshWindowSections() {
        val container = windowContainer ?: return
        container.removeAllViews()
        val windows = getWindows()
        val current = windows.filter { !it.isParked }
        val background = windows.filter { it.isParked }
        if (current.isNotEmpty()) {
            container.addView(sectionHeader("当前"))
            current.forEach { app -> container.addView(windowRow(app, isCurrent = true)) }
        }
        if (background.isNotEmpty()) {
            container.addView(sectionHeader("后台"))
            background.forEach { app -> container.addView(windowRow(app, isCurrent = false)) }
        }
        // Keep the "已打开" badges in the app list in sync after a close.
        renderAppRows(currentQuery)
    }

    private fun loadAppsAsync() {
        bgExecutor.execute {
            val apps = queryLauncherApps()
            mainHandler.post {
                // The dialog may have been dismissed while loading.
                if (!isShowing()) return@post
                allApps = apps
                renderAppRows(currentQuery)
                Log.i(TAG, "app panel loaded ${apps.size} apps")
            }
        }
    }

    private fun queryLauncherApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { ri ->
                    val ai = ri.activityInfo ?: return@mapNotNull null
                    AppEntry(
                        ComponentName(ai.packageName, ai.name),
                        ri.loadLabel(pm).toString(),
                        ri, // Icon loaded lazily in row()
                    )
                }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            Log.i(TAG, "query apps failed: ${e.message}")
            emptyList()
        }
    }

    /** Loads the icon for [entry] on a bg thread, then sets it on [imageView]. */
    private fun loadIconAsync(entry: AppEntry, imageView: ImageView) {
        loadIconCached(entry.component, imageView) { pm ->
            entry.resolveInfo.loadIcon(pm)
        }
    }

    /** Loads a window's app icon (activity icon, falling back to the app icon). */
    private fun loadWindowIconAsync(component: ComponentName, imageView: ImageView) {
        loadIconCached(component, imageView) { pm ->
            try {
                pm.getActivityInfo(component, 0).loadIcon(pm)
            } catch (e: Throwable) {
                pm.getApplicationIcon(component.packageName)
            }
        }
    }

    /** Shared icon loader: cache hit is synchronous, misses decode on a bg thread. */
    private fun loadIconCached(
        component: ComponentName,
        imageView: ImageView,
        load: (PackageManager) -> Drawable,
    ) {
        val cached = iconCache.get(component)
        if (cached != null) {
            imageView.setImageDrawable(cached)
            return
        }
        // Tag guards against recycled views (not a RecyclerView, but cheap).
        imageView.tag = component
        bgExecutor.execute {
            val icon = try {
                load(context.packageManager)
            } catch (e: Throwable) {
                null
            } ?: return@execute
            iconCache.put(component, icon)
            mainHandler.post {
                if (imageView.tag == component) {
                    imageView.setImageDrawable(icon)
                }
            }
        }
    }

    private fun renderAppRows(query: String) {
        val rows = appRows ?: return
        rows.removeAllViews()
        val q = query.trim().lowercase()
        val filtered =
            if (q.isEmpty()) allApps
            else allApps.filter { it.label.lowercase().contains(q) }
        if (filtered.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = if (allApps.isEmpty()) "加载中…" else "没有匹配的应用"
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        val openComponents = getWindows().mapNotNull { it.component }.toSet()
        filtered.forEach { app ->
            rows.addView(
                row(app, isOpen = app.component in openComponents) {
                    Log.i(TAG, "panel: app selected ${app.component.flattenToShortString()}")
                    onAppSelected(app.component)
                    dismiss()
                },
            )
        }
    }

    private fun sectionHeader(title: String): TextView =
        TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }

    private fun row(app: AppEntry, isOpen: Boolean, onClick: () -> Unit): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }
        // Icon loads async; placeholder until ready.
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        }
        layout.addView(iconView)
        loadIconAsync(app, iconView)
        layout.addView(TextView(context).apply {
            text = app.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (isOpen) {
            layout.addView(TextView(context).apply {
                text = "已打开"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
        layout.setOnClickListener { onClick() }
        return layout
    }

    /**
     * Window row: app icon + label + mute badge, then action buttons.
     * Tapping the row goes to the window and dismisses the panel; the
     * buttons refresh the sections instead, so several windows can be
     * managed without reopening the panel.
     */
    private fun windowRow(
        app: FreeWindowNotification.ParkedApp,
        isCurrent: Boolean,
    ): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                setBackgroundResource(out.resourceId)
            }
        }
        app.component?.let { component ->
            val iconView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
            }
            layout.addView(iconView)
            loadWindowIconAsync(component, iconView)
        }
        layout.addView(TextView(context).apply {
            text = app.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (app.isMuted) {
            layout.addView(TextView(context).apply {
                text = "🔇"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
        // Park button: only for visible windows (background ones are parked already).
        if (isCurrent) {
            layout.addView(TextView(context).apply {
                text = "▽"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener {
                    Log.i(TAG, "panel: window parked window=${app.windowId}")
                    onWindowParked(app.windowId)
                    refreshWindowSections()
                }
            })
        }
        // Maximize/restore button: same glyphs as the title bar
        // (TitleBar.setMaximized: maximized -> 🗗, windowed -> ⛶).
        layout.addView(TextView(context).apply {
            text = if (app.isMaximized) "🗗" else "⛶"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                Log.i(TAG, "panel: window maximized window=${app.windowId}")
                onWindowMaximized(app.windowId)
                refreshWindowSections()
            }
        })
        // ✕ button for close.
        layout.addView(TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                Log.i(TAG, "panel: window closed window=${app.windowId}")
                onWindowClosed(app.windowId)
                refreshWindowSections()
            }
        })
        layout.setOnClickListener {
            Log.i(TAG, "panel: window selected window=${app.windowId}")
            onWindowSelected(app.windowId)
            dismiss()
        }
        return layout
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FreeWindow"
        /**
         * Icon decoding used to be single-threaded: with ~200 apps the
         * last icons took seconds to appear. Four threads decode in
         * parallel; the per-view tag guard still prevents a stale icon
         * from landing on a rebuilt row, and LruCache is synchronized.
         */
        val bgExecutor = Executors.newFixedThreadPool(4)
        /**
         * Shared across panel instances (it used to be per-instance, so
         * every panel open re-decoded all icons). The app list itself is
         * still queried on each open, so installs/uninstalls stay fresh.
         */
        val iconCache = object : android.util.LruCache<ComponentName, Drawable>(64) {
            override fun sizeOf(key: ComponentName, value: Drawable): Int = 1
        }
    }
}
