package io.github.loveqianool.freewindow.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.loveqianool.freewindow.notification.RecentsBridge

/**
 * Launcher (com.android.launcher3, Trebuchet) side of Recents integration.
 *
 * Only the task menu is hooked here, not the task list: in current
 * Launcher3 the task list is built around GroupTask/DesktopTask, whose
 * shape changed a lot across versions. On v17 QuickStep the menu is
 * built per item: TaskMenuView.addMenuOption is called once per
 * built-in item ("应用信息 / 分屏" etc.), while the batch
 * addMenuOptions is dead code that is never called on menu open
 * (proven by device probe, 2026-11-01). This hook therefore appends
 * the FreeWindow option from addMenuOption, moving it to the end on
 * each subsequent item so it lands last. Card injection for FreeWindow
 * windows is a later step, via the system_server recent-task list,
 * not via GroupTask.
 *
 * Everything Launcher-side is reflection-only: Launcher3 classes are not
 * on this module's compile classpath, so every class, method, field and
 * R id is looked up at runtime, best-effort. Any lookup failure logs
 * (tag FreeWindow) and skips just the FreeWindow option — the original
 * menu still works, and the launcher never crashes because of this hook.
 * That defensiveness is intentional: Trebuchet versions differ, and a
 * failed lookup on device is diagnostic information, not a crash.
 *
 * Clicking the option sends [RecentsBridge.ACTION_WINDOW_TASK] to
 * SystemUI (same broadcast protocol as the notification actions) and
 * closes the menu. It deliberately does NOT dismiss Recents in v1: the
 * free window is a SystemUI overlay, so it is already visible over
 * Recents, and the user leaves Recents with back / home as usual.
 * Dismissing Recents from here would need a version-specific RecentsView
 * call and is a follow-up, not a v1 requirement.
 */
object RecentsHook {
    private const val TAG = "FreeWindow"
    private const val OPTION_TAG = "FreeWindowMenuOption"
    private var installed = false
    /** Launcher's classloader, for loading its R class (see getRInt). */
    private var launcherClassLoader: ClassLoader? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true
        launcherClassLoader = classLoader
        // TaskMenuViewWithArrow (tablet / grid variants) may override
        // addMenuOptions; hooking both is safe because appendOption()
        // skips when its option is already present (tag check below).
        var hooked = hookMenu(module, classLoader, "com.android.quickstep.views.TaskMenuView")
        hooked = hookMenu(module, classLoader, "com.android.quickstep.views.TaskMenuViewWithArrow") || hooked
        if (!hooked) {
            Log.w(TAG, "launcher: TaskMenuView.addMenuOptions not found, menu hook skipped")
        }
        // The real menu builder on v17 QuickStep (see class KDoc).
        hookAddMenuOption(module, classLoader, "com.android.quickstep.views.TaskMenuView")
    }

    /**
     * Hook the per-item builder TaskMenuView.addMenuOption(1 arg).
     * Device probe (2026-11-01) shows it is called once per built-in
     * menu item (4 calls per menu open), while addMenuOptions is never
     * called. The task container is not an argument here, so it is
     * resolved from the menu view itself: first the private
     * getTaskContainer() getter (Kotlin TaskMenuView has one), then
     * the mTaskContainer / taskContainer fields. appendOption's tag
     * guard moves our option to the end on later items, so after the
     * last built-in item it lands last instead of after the first.
     */
    private fun hookAddMenuOption(module: XposedModule, cl: ClassLoader, className: String) {
        try {
            val clazz = cl.loadClass(className)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "addMenuOption" && it.parameterTypes.size == 1
            } ?: run {
                Log.w(TAG, "launcher: $className.addMenuOption not found")
                return
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val menuView = chain.thisObject ?: return result
                        val container = callMethod(menuView, "getTaskContainer")
                            ?: getField(menuView, "mTaskContainer")
                            ?: getField(menuView, "taskContainer")
                        if (container == null) {
                            Log.w(TAG, "launcher: menu container not found for addMenuOption")
                            return result
                        }
                        appendOption(menuView, container)
                    } catch (e: Throwable) {
                        Log.w(TAG, "launcher: append FreeWindow option failed", e)
                    }
                    return result
                }
            })
            Log.i(TAG, "launcher: menu hook installed on $className.addMenuOption")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: hook $className.addMenuOption failed", e)
        }
    }

    private fun hookMenu(module: XposedModule, cl: ClassLoader, className: String): Boolean {
        return try {
            val clazz = cl.loadClass(className)
            // Prefer the 1-arg overload (Java TaskMenuView), fall back to
            // the 0-arg overload (Kotlin TaskMenuViewWithArrow, where the
            // container lives in a taskContainer field instead of an arg).
            // Requiring exactly 1 arg made the WithArrow class unhookable.
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "addMenuOptions" && it.parameterTypes.size == 1
            } ?: clazz.declaredMethods.firstOrNull {
                it.name == "addMenuOptions" && it.parameterTypes.isEmpty()
            } ?: run {
                Log.w(TAG, "launcher: $className.addMenuOptions not found")
                return false
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val menuView = chain.thisObject ?: return result
                        val container = chain.args.getOrNull(0)
                            ?: getField(menuView, "mTaskContainer")
                            ?: getField(menuView, "taskContainer")
                        if (container == null) {
                            Log.w(TAG, "launcher: menu container not found")
                            return result
                        }
                        appendOption(menuView, container)
                    } catch (e: Throwable) {
                        Log.w(TAG, "launcher: append FreeWindow option failed", e)
                    }
                    return result
                }
            })
            Log.i(TAG, "launcher: menu hook installed on $className (${method.parameterTypes.size}-arg)")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: hook $className failed", e)
            false
        }
    }

    private fun appendOption(menuView: Any, taskContainer: Any) {
        val menuViewAsView = menuView as? View
        if (menuViewAsView == null) {
            Log.w(TAG, "launcher: menu view is not a View")
            return
        }
        val context = menuViewAsView.context
        val task = callMethod(taskContainer, "getTask") ?: run {
            Log.w(TAG, "launcher: TaskIdAttributeContainer.getTask not found")
            return
        }
        val key = getField(task, "key") ?: run {
            Log.w(TAG, "launcher: Task.key not found")
            return
        }
        val taskId = getField(key, "id") as? Int ?: run {
            Log.w(TAG, "launcher: TaskKey.id not found")
            return
        }
        val baseIntent = getField(key, "baseIntent") as? Intent ?: run {
            Log.w(TAG, "launcher: TaskKey.baseIntent null, cannot window task")
            return
        }
        val component = baseIntent.component ?: run {
            Log.w(TAG, "launcher: baseIntent has no component, cannot window task")
            return
        }
        // mOptionLayout holds the menu option views (LinearLayout in the
        // Java TaskMenuView; the Kotlin version names it optionLayout).
        val optionLayout = (getField(menuView, "mOptionLayout") as? ViewGroup)
            ?: (getField(menuView, "optionLayout") as? ViewGroup)
            ?: findOptionLayoutById(menuView)
            ?: run {
                Log.w(TAG, "launcher: mOptionLayout not found")
                return
            }
        // One copy only: if our option is already present (later
        // built-in items after the first addMenuOption call, or a
        // second hook firing for the same menu open), move it to the
        // end instead of adding a duplicate, so it lands last.
        for (i in 0 until optionLayout.childCount) {
            val existing = optionLayout.getChildAt(i)
            if (existing?.tag == OPTION_TAG) {
                optionLayout.removeView(existing)
                optionLayout.addView(existing)
                return
            }
        }
        val optionView = inflateOptionView(context, menuView, optionLayout)
            ?: run {
                Log.w(TAG, "launcher: could not build menu option view")
                return
            }
        optionView.tag = OPTION_TAG
        optionView.setOnClickListener {
            val intent = Intent(RecentsBridge.ACTION_WINDOW_TASK).apply {
                setPackage("com.android.systemui")
                putExtra(RecentsBridge.EXTRA_PACKAGE, component.packageName)
                putExtra(RecentsBridge.EXTRA_CLASS, component.className)
                putExtra(RecentsBridge.EXTRA_TASK_ID, taskId)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "launcher: window task ${component.packageName} task=$taskId")
            closeMenu(menuView)
        }
        applyMenuLayoutParams(menuView, optionView)
        optionLayout.addView(optionView)
        Log.i(TAG, "launcher: FreeWindow option added for ${component.packageName}")
    }

    /** Fallback when the mOptionLayout field is renamed: find it by R id. */
    private fun findOptionLayoutById(menuView: Any): ViewGroup? {
        val view = menuView as? View ?: return null
        val id = getRInt("com.android.launcher3.R\$id", "menu_option_layout") ?: return null
        // Same safe-cast rule as the icon above: a hard ViewGroup cast
        // would throw if this id ever points at a plain View.
        return view.findViewById<View>(id) as? ViewGroup
    }

    /**
     * Inflate Launcher's own task_view_menu_option layout so the option
     * looks like the built-in ones. Falls back to a plain TextView row
     * when the layout or its ids cannot be resolved on this version.
     */
    private fun inflateOptionView(context: Context, menuView: Any, parent: ViewGroup): View? {
        val layoutId = getRInt("com.android.launcher3.R\$layout", "task_view_menu_option")
        if (layoutId != null) {
            val inflated = runCatching {
                LayoutInflater.from(context).inflate(layoutId, parent, false)
            }.getOrNull()
            if (inflated != null) {
                val iconId = getRInt("com.android.launcher3.R\$id", "icon")
                val textId = getRInt("com.android.launcher3.R\$id", "text")
                // v17 QuickStep: the "icon" view in task_view_menu_option
                // is a plain View, not an ImageView — a hard cast throws
                // ClassCastException and kills the whole append (device
                // probe, 2026-11-01). Safe-cast so a non-ImageView icon
                // is simply left alone; text is what the user reads.
                (if (iconId != null) inflated.findViewById<View>(iconId) as? ImageView else null)
                    ?.setImageResource(android.R.drawable.ic_dialog_info)
                val textView = if (textId != null) inflated.findViewById<View>(textId) as? TextView else null
                textView?.text = "自由窗口"
                // Text is the part the user reads: without it the row
                // would be an icon with no label, so fall through to the
                // programmatic row instead. A missing icon alone is fine.
                if (textView != null) return inflated
            }
        }
        Log.w(TAG, "launcher: task_view_menu_option layout not usable, using fallback row")
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt())
        }
        row.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
        })
        row.addView(TextView(context).apply {
            text = "自由窗口"
            textSize = 16f
            setPadding((12 * density).toInt(), 0, 0, 0)
        })
        return row
    }

    /**
     * Best-effort copy of TaskMenuView.addMenuOption's layout step:
     * PagedOrientationHandler.setLayoutParamsForTaskMenuOptionItem.
     * If any piece is missing on this version, the inflated layout's
     * own params are kept — the option still shows, just possibly
     * with default sizing.
     */
    private fun applyMenuLayoutParams(menuView: Any, optionView: View) {
        try {
            val taskView = getField(menuView, "mTaskView") ?: return
            val handler = callMethod(taskView, "getPagedOrientationHandler") ?: return
            // mActivity in the Java version, mContainer in the Kotlin
            // version; either way, getDeviceProfile is looked up by name.
            val activity = getField(menuView, "mActivity")
                ?: getField(menuView, "mContainer")
                ?: return
            val deviceProfile = callMethod(activity, "getDeviceProfile") ?: return
            val lp = optionView.layoutParams ?: return
            val method = handler.javaClass.methods.firstOrNull {
                it.name == "setLayoutParamsForTaskMenuOptionItem" && it.parameterTypes.size == 3
            } ?: return
            method.isAccessible = true
            method.invoke(handler, lp, optionView, deviceProfile)
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: menu layout params skipped", e)
        }
    }

    private fun closeMenu(menuView: Any) {
        try {
            val method = menuView.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: menuView.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            } ?: return
            method.isAccessible = true
            if (method.parameterTypes.size == 1) {
                method.invoke(menuView, true)
            } else {
                method.invoke(menuView)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: close menu failed", e)
        }
    }

    private fun getField(obj: Any, name: String): Any? = runCatching {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return@runCatching field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        null
    }.getOrNull()

    private fun callMethod(obj: Any, name: String): Any? = runCatching {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            val method = clazz.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(obj)
            }
            clazz = clazz.superclass
        }
        null
    }.getOrNull()

    private fun getRInt(className: String, fieldName: String): Int? = runCatching {
        // The R class lives in the launcher APK, so it must be loaded
        // through the launcher's classloader, not this module's: the
        // module's classloader cannot see the launcher's R class.
        val clazz = launcherClassLoader?.loadClass(className)
            ?: Class.forName(className)
        clazz.getField(fieldName).getInt(null)
    }.getOrNull()
}
