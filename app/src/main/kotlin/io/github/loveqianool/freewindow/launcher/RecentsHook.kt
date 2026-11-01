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
        // TEMPORARY PROBE3 (remove after card diagnosis, 2026-11-01):
        // ATMS.getRecentTasks is hooked and correct but never called, so
        // the launcher must load its Recents task list another way. Probe
        // everything the launcher-side injection will need, in one build:
        // method tables for the task-list classes, plus logging-only hooks
        // for the task query / click / swipe entry points, so the next
        // implementation does not need another probe round.
        installProbe3(module, classLoader)
        // TEMPORARY PROBE4 (remove with PROBE3, 2026-11-01): PROBE3
        // settled the injection point — RecentsView.applyLoadPlan(List,
        // int) — but not how to build a fake Task for it. Probe the
        // Task object itself: TaskView.bind's Task type, the
        // RecentTasksList holder, and the Task / TaskKey constructors
        // and fields, plus the applyLoadPlan argument values.
        installProbe4(module, classLoader)
        // TEMPORARY PROBE5 (remove with PROBE3, 2026-11-01): PROBE4
        // showed TaskView.bind takes a SingleTask, not a Task, and
        // createTaskContainer takes the shared-recents Task. The list
        // applyLoadPlan receives is therefore a list of SingleTask /
        // GroupTask wrappers, and a fake card needs a fake SingleTask.
        // Probe SingleTask and GroupTask construction only; the Task /
        // TaskKey tables are already in PROBE4's dump.
        installProbe5(module, classLoader)
    }

    /**
     * TEMPORARY PROBE5, launcher side. Logging-only, same rules as
     * PROBE3/PROBE4: dump tables only, no hooks, no behaviour change.
     */
    private fun installProbe5(module: XposedModule, cl: ClassLoader) {
        try {
            dumpProbeClass(cl, "PROBE5", "com.android.quickstep.util.SingleTask")
            dumpProbeClass(cl, "PROBE5", "com.android.quickstep.util.GroupTask")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE5 install failed", e)
        }
    }

    /**
     * TEMPORARY PROBE4, launcher side. Logging-only, same rules as
     * PROBE3: proceed first, log, return the original result unchanged.
     */
    private fun installProbe4(module: XposedModule, cl: ClassLoader) {
        try {
            // TaskView.bind's parameter type is the Task class name;
            // its mTask field type says the same thing. RecentTasksList
            // is where RecentsModel keeps its task list (field taskList).
            dumpProbeClass(cl, "PROBE4", "com.android.quickstep.views.TaskView")
            dumpProbeClass(cl, "PROBE4", "com.android.quickstep.RecentTasksList")
            // Candidate Task / TaskKey classes from the shared recents
            // library; NOT FOUND here just means TaskView.bind's type
            // (above) is the real one to dump next.
            dumpProbeClass(cl, "PROBE4", "com.android.systemui.shared.recents.model.Task")
            dumpProbeClass(cl, "PROBE4", "com.android.systemui.shared.recents.model.TaskKey")
            // applyLoadPlan(List, int): log the list size and the int
            // argument value (display id? running task id? load id?).
            hookProbe4ApplyLoadPlan(module, cl)
            // RecentsModel.getTasks(Consumer) returns an Integer; log
            // its value, not just its class name, to tell a task count
            // apart from a load identifier.
            hookProbe4GetTasks(module, cl)
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE4 install failed", e)
        }
    }

    private fun dumpProbeClass(cl: ClassLoader, tag: String, className: String) {
        try {
            val clazz = cl.loadClass(className)
            for (method in clazz.declaredMethods) {
                Log.i(
                    TAG,
                    "launcher: $tag dump $className method ${method.name}(" +
                        method.parameterTypes.joinToString { it.name } + "):${method.returnType.name}",
                )
            }
            // Constructors matter here: a fake Task must be built, and
            // its constructor signature is the only way to build one.
            for (ctor in clazz.declaredConstructors) {
                Log.i(
                    TAG,
                    "launcher: $tag dump $className ctor(" +
                        ctor.parameterTypes.joinToString { it.name } + ")",
                )
            }
            for (field in clazz.declaredFields) {
                Log.i(
                    TAG,
                    "launcher: $tag dump $className field ${field.name}:${field.type.name}",
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: $tag dump $className NOT FOUND")
        }
    }

    private fun hookProbe4ApplyLoadPlan(module: XposedModule, cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("com.android.quickstep.views.RecentsView")
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "applyLoadPlan" && it.parameterTypes.size == 2
            } ?: run {
                Log.w(TAG, "launcher: PROBE4 applyLoadPlan not found")
                return
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val listSize = (chain.args.getOrNull(0) as? List<*>)?.size
                        val intArg = chain.args.getOrNull(1) as? Int
                        Log.i(
                            TAG,
                            "launcher: PROBE4 applyLoadPlan listSize=$listSize intArg=$intArg",
                        )
                    } catch (_: Throwable) {
                    }
                    return result
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE4 hook applyLoadPlan failed", e)
        }
    }

    private fun hookProbe4GetTasks(module: XposedModule, cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("com.android.quickstep.RecentsModel")
            var matched = 0
            for (method in clazz.declaredMethods) {
                if (method.name != "getTasks") continue
                matched++
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            val argTypes = chain.args.joinToString {
                                it?.javaClass?.simpleName ?: "null"
                            }
                            val resultDesc = when (result) {
                                is Int -> "Int=$result"
                                is List<*> -> "List size=${result.size}"
                                else -> result?.javaClass?.simpleName ?: "null"
                            }
                            Log.i(
                                TAG,
                                "launcher: PROBE4 RecentsModel.getTasks args=[$argTypes] result=$resultDesc",
                            )
                        } catch (_: Throwable) {
                        }
                        return result
                    }
                })
            }
            if (matched == 0) Log.w(TAG, "launcher: PROBE4 hook RecentsModel.getTasks no matching methods")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE4 hook getTasks failed", e)
        }
    }

    /**
     * TEMPORARY PROBE3, launcher side. All hooks here are logging-only:
     * proceed first, log, return the original result unchanged. Any
     * failure logs and skips just that method; the launcher never
     * crashes because of this probe.
     */
    private fun installProbe3(module: XposedModule, cl: ClassLoader) {
        try {
            // 1. Method + field tables: what task-list classes exist, what
            // task methods they declare, and where the task list lives.
            // Full parameter type names (not just simple names) so the
            // next probe can load the LoadPlan class by its real name.
            dumpProbe3Class(cl, "com.android.quickstep.RecentsModel")
            dumpProbe3Class(cl, "com.android.quickstep.views.RecentsView")
            dumpProbe3Class(cl, "com.android.quickstep.views.RecentsView\$LoadPlan")
            dumpProbe3Class(cl, "com.android.quickstep.RecentsModel\$LoadPlan")
            // 2. Client-side task query / click / swipe entry points.
            // ActivityTaskManager is the newer client wrapper; the older
            // ActivityManager wrapper may not even exist on this build.
            hookProbe3ByName(module, "android.app.ActivityManager") { name ->
                name == "getRecentTasks" || name == "startActivityFromRecents" || name == "removeTask"
            }
            hookProbe3ByName(module, "android.app.ActivityTaskManager") { name ->
                name == "getTasks" || name == "getRecentTasks"
            }
            // 3. Data-layer task methods: load / change / remove tasks.
            hookProbe3Class(module, cl, "com.android.quickstep.RecentsModel") { name ->
                "Task" in name
            }
            // 4. UI-layer task-list methods (plural Tasks, to avoid the
            // per-card getTaskView spam of the singular form).
            hookProbe3Class(module, cl, "com.android.quickstep.views.RecentsView") { name ->
                "Tasks" in name || "LoadPlan" in name
            }
            // 5. Card click / dismiss handlers, for later fake-card taps.
            // PROBE3 fix: the old "launch" substring matched the synthetic
            // shouldShowSplash$...Launcher3... name and spammed the log.
            // Only methods starting with launch, plus dismissTask, are
            // real card actions; dismiss-button UI methods are excluded.
            hookProbe3Class(module, cl, "com.android.quickstep.views.TaskView") { name ->
                val lower = name.lowercase()
                lower.startsWith("launch") || "dismisstask" in lower
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE3 install failed", e)
        }
    }

    private fun dumpProbe3Class(cl: ClassLoader, className: String) {
        try {
            val clazz = cl.loadClass(className)
            for (method in clazz.declaredMethods) {
                Log.i(
                    TAG,
                    "launcher: PROBE3 dump $className method ${method.name}(" +
                        method.parameterTypes.joinToString { it.name } + ")",
                )
            }
            for (field in clazz.declaredFields) {
                Log.i(
                    TAG,
                    "launcher: PROBE3 dump $className field ${field.name}:${field.type.name}",
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE3 dump $className NOT FOUND")
        }
    }

    private fun hookProbe3ByName(module: XposedModule, className: String, match: (String) -> Boolean) {
        try {
            val clazz = Class.forName(className)
            var matched = 0
            for (method in clazz.declaredMethods) {
                if (!match(method.name)) continue
                matched++
                hookProbe3Method(module, "PROBE3 $className.${method.name}", method)
            }
            if (matched == 0) Log.w(TAG, "launcher: PROBE3 hook $className no matching methods")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE3 hook $className failed", e)
        }
    }

    private fun hookProbe3Class(module: XposedModule, cl: ClassLoader, className: String, match: (String) -> Boolean) {
        try {
            val clazz = cl.loadClass(className)
            var matched = 0
            for (method in clazz.declaredMethods) {
                if (!match(method.name)) continue
                matched++
                hookProbe3Method(module, "PROBE3 $className.${method.name}", method)
            }
            if (matched == 0) Log.w(TAG, "launcher: PROBE3 hook $className no matching methods")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE3 hook $className failed", e)
        }
    }

    private fun hookProbe3Method(module: XposedModule, label: String, method: java.lang.reflect.Method) {
        try {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val size = (result as? List<*>)?.size
                        val firstInt = chain.args.getOrNull(0) as? Int
                        Log.i(
                            TAG,
                            "launcher: $label called args=${chain.args.size} " +
                                (if (firstInt != null) "firstInt=$firstInt " else "") +
                                (if (size != null) "resultSize=$size" else "result=${result?.javaClass?.simpleName}"),
                        )
                    } catch (_: Throwable) {
                        // Logging must never change the result.
                    }
                    return result
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE3 hook $label failed", e)
        }
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
