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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
        /** Lowercased once at query time; search/sort must not redo it per app. */
        /** Pinned locale: default-locale lowercase breaks for some locales (e.g. Turkish I). */
        val lowerLabel: String = label.lowercase(java.util.Locale.ROOT),
    )

    /** One app row: the entry plus its "已打开" state at render time. */
    private data class AppRow(
        val entry: AppEntry,
        val isOpen: Boolean,
    )

    private var dialog: Dialog? = null
    private var windowContainer: LinearLayout? = null
    private var windowScroll: ScrollView? = null
    private var appRecyclerView: RecyclerView? = null
    private var appEmptyView: TextView? = null
    private val appAdapter by lazy { AppAdapter() }
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
            // Outside touch / back dismisses without going through
            // dismiss(), so clean up here too (idempotent with it).
            setOnDismissListener { clearPanelFields() }
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
            // Search is live-filtered, so Enter has no submit action;
            // make it dismiss the keyboard instead of doing nothing.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(search)

        // Window sections (rebuilt by refreshWindowSections() after every
        // in-panel action, so the panel stays open for batch operations).
        // Only a handful of windows exist, so they stay a plain layout;
        // the app list below is the part that needs recycling.
        val windowBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // Capped scroll area (see capWindowSection): window rows stay
        // above the app list, but they must not squeeze it to nothing.
        val windowScrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(windowBox)
        }
        root.addView(windowScrollView)
        windowScroll = windowScrollView
        windowContainer = windowBox
        refreshWindowSections()

        // Launchable apps: a RecyclerView with a bounded height (weight 1
        // inside the fixed-size dialog), so only visible rows are inflated.
        // Putting it inside a ScrollView with WRAP_CONTENT would measure
        // every row and defeat recycling, so it gets its own scroll area.
        root.addView(sectionHeader("应用"))
        val emptyView = TextView(context).apply {
            text = "加载中…"
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(emptyView)
        appEmptyView = emptyView
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appAdapter
            // Hidden until the first non-empty render; otherwise the
            // "加载中…" text sits above a blank list area.
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(recyclerView)
        appRecyclerView = recyclerView

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                // Debounce: filtering + diffing on every keystroke is
                // wasted work; wait until typing pauses.
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
            // The search box is the first focusable view; without this,
            // the keyboard can pop up on its own when the panel opens and
            // cover half the list. Only show it when the user taps search.
            // ADJUST_RESIZE keeps the list above the keyboard once it does.
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }

        this.dialog = dialog
        dialog.show()
        Log.i(TAG, "app panel shown")
        loadAppsAsync()
    }

    fun dismiss() {
        dialog?.dismiss()
        clearPanelFields()
    }

    private fun clearPanelFields() {
        dialog = null
        windowContainer = null
        windowScroll = null
        appRecyclerView = null
        appEmptyView = null
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        pendingSearch = null
        appAdapter.submitList(emptyList())
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
        capWindowSection()
    }

    /**
     * Keep the window section from squeezing the app list: if its content
     * is taller than the cap, cap the scroll area to that height and let
     * the section scroll internally; otherwise let it wrap its content
     * so a short section does not waste space.
     * Measured synchronously, not via post: a posted read can run before
     * the new layout pass and see the previous rows' height, which caps
     * the section at the wrong value until the next refresh. The width
     * must be exact (label wrapping depends on it): dialog width is 85%
     * of the screen, minus the root's 16dp padding on each side.
     * The cap is relative, not a fixed 240dp: in landscape the dialog
     * is only ~70% of a short screen height, so a fixed cap would leave
     * the app list with almost no space.
     */
    private fun capWindowSection() {
        val scroll = windowScroll ?: return
        val box = windowContainer ?: return
        val metrics = context.resources.displayMetrics
        val dialogWidth = (metrics.widthPixels * 0.85).toInt()
        val dialogHeight = (metrics.heightPixels * 0.7).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            dialogWidth - dp(32),
            View.MeasureSpec.EXACTLY,
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        box.measure(widthSpec, heightSpec)
        val contentHeight = box.measuredHeight
        val cap = minOf(dp(WINDOW_SECTION_MAX_HEIGHT_DP), (dialogHeight * 0.4).toInt())
        val params = scroll.layoutParams
        val target =
            if (contentHeight > cap) cap
            else ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.height != target) {
            params.height = target
            scroll.layoutParams = params
        }
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
                .sortedBy { it.lowerLabel }
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

    /**
     * A fresh instance sharing the cached bitmap/state, so no ImageView
     * ever mutates the Drawable stored in the cache (or one another view
     * is showing). Falls back to the original when the drawable has no
     * constant state. Sharing the bitmap is still cheap — no re-decode.
     */
    private fun drawableCopy(drawable: Drawable): Drawable =
        drawable.constantState?.newDrawable(context.resources) ?: drawable

    /** Shared icon loader: cache hit is synchronous, misses decode on a bg thread. */
    private fun loadIconCached(
        component: ComponentName,
        imageView: ImageView,
        load: (PackageManager) -> Drawable,
    ) {
        // Tag must be set before the cache check too: with recycled views
        // a cache hit that left the old tag would let a stale async load
        // for the previous row pass the tag guard and overwrite this icon.
        imageView.tag = component
        val cached = iconCache.get(component)
        if (cached != null) {
            imageView.setImageDrawable(drawableCopy(cached))
            return
        }
        bgExecutor.execute {
            val icon = try {
                load(context.packageManager)
            } catch (e: Throwable) {
                null
            } ?: return@execute
            // The cache keeps the template; views only ever get copies.
            iconCache.put(component, icon)
            mainHandler.post {
                if (imageView.tag == component) {
                    imageView.setImageDrawable(drawableCopy(icon))
                }
            }
        }
    }

    private fun renderAppRows(query: String) {
        val recyclerView = appRecyclerView ?: return
        val emptyView = appEmptyView ?: return
        val q = query.trim().lowercase(java.util.Locale.ROOT)
        val filtered =
            if (q.isEmpty()) allApps
            else allApps.filter { it.lowerLabel.contains(q) }
        if (filtered.isEmpty()) {
            emptyView.text = if (allApps.isEmpty()) "加载中…" else "没有匹配的应用"
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            appAdapter.submitList(emptyList())
            return
        }
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val openComponents = getWindows().mapNotNull { it.component }.toSet()
        // DiffUtil (not removeAllViews + re-inflate) keeps visible rows
        // in place on search and on "已打开" badge changes.
        appAdapter.submitList(
            filtered.map { app -> AppRow(app, isOpen = app.component in openComponents) },
        )
    }

    /** App list adapter: ViewHolders are recycled, only visible rows exist. */
    private inner class AppAdapter : ListAdapter<AppRow, AppViewHolder>(AppDiffCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
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
            val iconView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
            }
            layout.addView(iconView)
            val labelView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            layout.addView(labelView)
            val badgeView = TextView(context).apply {
                text = "已打开"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            }
            layout.addView(badgeView)
            return AppViewHolder(layout, iconView, labelView, badgeView)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private inner class AppViewHolder(
        private val layout: LinearLayout,
        private val iconView: ImageView,
        private val labelView: TextView,
        private val badgeView: TextView,
    ) : RecyclerView.ViewHolder(layout) {
        fun bind(row: AppRow) {
            labelView.text = row.entry.label
            badgeView.visibility = if (row.isOpen) View.VISIBLE else View.GONE
            // Clear the recycled icon first; loadIconCached sets the new
            // one (synchronously on cache hit, async otherwise, tag-guarded).
            iconView.setImageDrawable(null)
            loadIconAsync(row.entry, iconView)
            layout.setOnClickListener {
                Log.i(TAG, "panel: app selected ${row.entry.component.flattenToShortString()}")
                onAppSelected(row.entry.component)
                dismiss()
            }
        }
    }

    private object AppDiffCallback : DiffUtil.ItemCallback<AppRow>() {
        override fun areItemsTheSame(oldItem: AppRow, newItem: AppRow): Boolean =
            oldItem.entry.component == newItem.entry.component

        override fun areContentsTheSame(oldItem: AppRow, newItem: AppRow): Boolean =
            oldItem.entry.label == newItem.entry.label && oldItem.isOpen == newItem.isOpen
    }

    private fun sectionHeader(title: String): TextView =
        TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), dp(12), dp(4), dp(4))
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
            // Single line + ellipsis: with 3 x 48dp buttons, a long label
            // on a narrow panel would otherwise wrap and inflate the row.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (app.isMuted) {
            layout.addView(TextView(context).apply {
                text = "🔇"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
        if (app.isCollapsed) {
            layout.addView(TextView(context).apply {
                text = "已收起"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
        if (app.isCropped) {
            layout.addView(TextView(context).apply {
                text = "已裁剪"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
        // Park button: only for visible windows (background ones are parked already).
        if (isCurrent) {
            layout.addView(actionButton("▽", "最小化到后台") {
                Log.i(TAG, "panel: window parked window=${app.windowId}")
                onWindowParked(app.windowId)
                refreshWindowSections()
            })
        }
        // Maximize/restore button: same glyphs as the title bar
        // (TitleBar.setMaximized: maximized -> 🗗, windowed -> ⛶).
        layout.addView(actionButton(if (app.isMaximized) "🗗" else "⛶", if (app.isMaximized) "恢复窗口大小" else "最大化") {
            Log.i(TAG, "panel: window maximized window=${app.windowId}")
            onWindowMaximized(app.windowId)
            refreshWindowSections()
        })
        // ✕ button for close.
        layout.addView(actionButton("✕", "关闭窗口") {
            Log.i(TAG, "panel: window closed window=${app.windowId}")
            onWindowClosed(app.windowId)
            refreshWindowSections()
        })
        layout.setOnClickListener {
            Log.i(TAG, "panel: window selected window=${app.windowId}")
            onWindowSelected(app.windowId)
            dismiss()
        }
        return layout
    }

    /**
     * Window-row action button: the glyph alone is ~18dp wide, so give
     * every button a 48dp minimum touch target (centered glyph) instead
     * of the old text-only padding, which was easy to miss.
     */
    private fun actionButton(text: String, description: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            contentDescription = description
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FreeWindow"
        const val WINDOW_SECTION_MAX_HEIGHT_DP = 240
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
