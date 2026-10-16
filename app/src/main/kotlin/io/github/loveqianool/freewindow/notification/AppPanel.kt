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
 * App panel: a dialog with two sections.
 *
 * - "后台": parked windows; tapping one restores it via [onParkedSelected].
 * - "应用": launchable apps queried from PackageManager, filterable through
 *   the search box at the top; tapping one fires [onAppSelected].
 *
 * Either callback dismisses the panel. The dialog uses
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] so it can be shown
 * from the SystemUI process, and it is focusable so the search box gets IME.
 */
class AppPanel(
    private val context: Context,
    private val parkedApps: List<FreeWindowNotification.ParkedApp>,
    private val onAppSelected: (ComponentName) -> Unit,
    private val onParkedSelected: (windowId: Int) -> Unit,
) {

    private data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )

    private var dialog: Dialog? = null
    private var appRows: LinearLayout? = null
    private var allApps: List<AppEntry> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // Section 1: parked windows.
        if (parkedApps.isNotEmpty()) {
            listContainer.addView(sectionHeader("后台"))
            parkedApps.forEach { app ->
                listContainer.addView(
                    row(label = app.label, icon = null) {
                        Log.i(TAG, "panel: parked selected window=${app.windowId}")
                        onParkedSelected(app.windowId)
                        dismiss()
                    },
                )
            }
        }

        // Section 2: launchable apps.
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
                renderAppRows(s?.toString().orEmpty())
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
        appRows = null
    }

    private fun loadAppsAsync() {
        bgExecutor.execute {
            val apps = queryLauncherApps()
            mainHandler.post {
                // The dialog may have been dismissed while loading.
                if (!isShowing()) return@post
                allApps = apps
                renderAppRows("")
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
                        ri.loadIcon(pm),
                    )
                }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            Log.i(TAG, "query apps failed: ${e.message}")
            emptyList()
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
        filtered.forEach { app ->
            rows.addView(
                row(label = app.label, icon = app.icon) {
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

    private fun row(label: String, icon: Drawable?, onClick: () -> Unit): LinearLayout {
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
        if (icon != null) {
            layout.addView(ImageView(context).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
            })
        }
        layout.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        layout.setOnClickListener { onClick() }
        return layout
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FreeWindow"
        val bgExecutor = Executors.newSingleThreadExecutor()
    }
}
