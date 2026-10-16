package io.github.lujinxin.nextep.freewindow

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * App list panel expanded from the floating ball.
 *
 * Top section lists running free windows (with mode/mute badges); bottom
 * section lists curated apps filtered by the search box. Host callbacks:
 * [AppListPanelHost.onWindowRowTap] restores a parked window or brings a
 * windowed/maximized one to front, [AppListPanelHost.onWindowRowLongPress]
 * demotes a maximized window back to windowed mode.
 */
class AppListPanel(context: Context, private val host: AppListPanelHost) : FrameLayout(context) {

    private val searchBox: EditText
    private val runningContainer: LinearLayout
    private val appsContainer: LinearLayout

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(0xF2242424.toInt())
            setStroke(dp(1), 0x44FFFFFF)
        }
        isClickable = true
        isFocusable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Header: title + close button.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        column.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val title = TextView(context).apply {
            text = "应用"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { host.onPanelClose() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT))

        // Search box.
        searchBox = EditText(context).apply {
            hint = "搜索应用"
            maxLines = 1
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(0x33FFFFFF)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    refresh()
                }
            })
        }
        column.addView(searchBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        // Running-windows section.
        column.addView(sectionLabel("运行中"))
        runningContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(runningContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // App catalogue section.
        column.addView(sectionLabel("应用"))
        appsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroller = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(appsContainer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        column.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refresh()
    }

    /** Rebuild both sections from the host. Keeps the current search query. */
    fun refresh() {
        val query = searchBox.text?.toString().orEmpty()

        runningContainer.removeAllViews()
        val windows = host.runningWindows()
        if (windows.isEmpty()) {
            runningContainer.addView(TextView(context).apply {
                text = "暂无运行中的窗口"
                setTextColor(0xFF777777.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            for (state in windows) {
                val badge = buildList {
                    if (state.mode == FreeWindowMode.PARKED) add("后台")
                    if (state.mode == FreeWindowMode.MAXIMIZED) add("最大化")
                    if (state.muted) add("🔇")
                }.joinToString(" · ")
                val row = makeRow(
                    icon = state.icon,
                    label = state.title.ifEmpty { state.packageName ?: "?" },
                    badge = badge,
                )
                row.setOnClickListener { host.onWindowRowTap(state.windowId) }
                row.setOnLongClickListener {
                    host.onWindowRowLongPress(state.windowId)
                    true
                }
                runningContainer.addView(row)
            }
        }

        appsContainer.removeAllViews()
        for (entry in host.curatedApps(query)) {
            val row = makeRow(icon = entry.icon, label = entry.label, badge = "")
            row.setOnClickListener { host.onAppTap(entry) }
            appsContainer.addView(row)
        }
    }

    /**
     * No-op kept for API compatibility: the panel now fills its own window,
     * whose position is owned by the hook via WindowManager params.
     */
    fun setPanelRect(rect: Rect) {
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFF999999.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun makeRow(icon: android.graphics.drawable.Drawable?, label: String, badge: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icon)
        }
        row.addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })

        val nameView = TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (badge.isNotEmpty()) {
            val badgeView = TextView(context).apply {
                text = badge
                setTextColor(0xFF999999.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
            }
            row.addView(badgeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        return row
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    @Suppress("unused")
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
