package io.github.loveqianool.freewindow.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
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
        // TEMPORARY PROBE6 (remove with PROBE3, 2026-11-01): PROBE5
        // settled SingleTask construction — SingleTask(Task) — but
        // the shared-recents Task class cannot be dumped at install
        // time (stub / NOT FOUND). Dump it at runtime instead, from
        // a real Task instance taken out of the applyLoadPlan list,
        // where the real class is actually loaded. Logging-only.
        installProbe6(module, classLoader)
        // Card injection (2026-11-01, PROBE6-complete): append a fake
        // SingleTask per FreeWindow window to the Recents list, in
        // RecentsView.applyLoadPlan, before the original method runs.
        // The window list comes from SystemUI's WINDOWS_CHANGED
        // broadcast (same protocol as the system_server cache); fake
        // ids are 900000+windowId, so a tap / swipe on a fake card
        // reaches the system_server startActivityFromRecents /
        // removeTask interception, which forwards RECENTS_RESTORE /
        // RECENTS_CLOSE to SystemUI. See the function KDoc for the
        // safety rules that keep real Recents intact.
        installCardInjection(module, classLoader)
        // TEMPORARY PROBE7 (remove with PROBE3, 2026-11-01): fake
        // cards inject (recents-cards injected N) but render with no
        // title and no content on device. The list injection works;
        // the display does not. TaskView's tile state is loaded
        // asynchronously (TaskTileUiState, via a TasksRepository keyed
        // by task id), and a fake id has no system task to load from,
        // so the async load likely overwrites the fake Task's title /
        // icon / thumbnail with empty state. Probe the tile-state
        // classes and log what state a fake card actually receives.
        installProbe7(module, classLoader)
        // TEMPORARY PROBE8 (remove with PROBE3, 2026-11-01): PROBE7
        // showed fake cards DO receive a TaskTileUiState (same field
        // types as real cards), so the callback is not missing — the
        // state's CONTENT must be empty. PROBE7 only logged field
        // types, not values, so it cannot tell an empty tasks list
        // from a full one. Log the values: hasHeader / isCentralTask,
        // tasks list size, and each tile element's title / icon /
        // thumbnail presence, plus the TaskView's own Task title at
        // that moment, to see whether the fake Task's title survived
        // or was cleared before the tile state was built.
        installProbe8(module, classLoader)
        // TEMPORARY PROBE9 (remove with PROBE3, 2026-11-01): PROBE8
        // settled it — the fake card's own Task is correct
        // (title 'Haven (自由窗口)', icon present), but its tile
        // state element is TaskData$NoData with only a taskId, so
        // the tile loader found no system task for the fake id and
        // gave up. The fix is to replace that NoData element with
        // a Data element built from the fake Task, which needs the
        // Data class's full name, fields and constructors. Log them
        // from a runtime NoData element's classloader, plus the Data
        // class if it can be loaded from the same package.
        installProbe9(module, classLoader)
        // Fake-card tile fix (PROBE9 gave the Data shape, 2026-11-01):
        // on TaskView.updateTaskViewState, before the original
        // method renders, replace each fake NoData tile element with
        // a Data element built from the card's own fake Task
        // (title, icon, package, colors, lock flags; thumbnail is
        // null in v1, as the fake Task itself has none yet). Real
        // cards are untouched (taskId < CARD_FAKE_BASE returns
        // early), and any failure leaves the original state in
        // place, so this can only leave a card blank, never break
        // Recents. Registered after the probes, so PROBE8/PROBE9
        // logs show the replaced state and verify the fix.
        installFakeCardTileFix(module, classLoader)
    }

    /**
     * Fake-card tile fix, launcher side. Hooks
     * TaskView.updateTaskViewState and, before proceeding, swaps
     * fake NoData elements for Data elements built from the fake
     * Task. Best-effort throughout: any failure logs and the
     * original state proceeds unchanged.
     */
    private fun installFakeCardTileFix(module: XposedModule, cl: ClassLoader) {
        try {
            val taskViewClass = cl.loadClass("com.android.quickstep.views.TaskView")
            for (method in taskViewClass.declaredMethods) {
                if (!method.name.startsWith("updateTaskViewState")) continue
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        try {
                            replaceNoDataWithData(chain)
                        } catch (_: Throwable) {
                        }
                        return chain.proceed()
                    }
                })
            }
            Log.i(TAG, "launcher: fake card tile fix installed on TaskView.updateTaskViewState")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: fake card tile fix install failed", e)
        }
    }

    private fun replaceNoDataWithData(chain: XposedInterface.Chain) {
        val state = chain.args.getOrNull(0) ?: return
        val taskView = chain.thisObject ?: return
        val firstTask = runCatching {
            val m = taskView.javaClass.methods.firstOrNull {
                it.name == "getFirstTask" && it.parameterTypes.isEmpty()
            }
            m?.isAccessible = true
            m?.invoke(taskView)
        }.getOrNull() ?: return
        val cardTaskId = runCatching {
            val key = getField(firstTask, "key")
            getField(key, "id") as? Int
        }.getOrNull() ?: return
        if (cardTaskId < CARD_FAKE_BASE) return
        val tasksField = state.javaClass.declaredFields.firstOrNull {
            it.name == "tasks"
        } ?: return
        tasksField.isAccessible = true
        val tasks = tasksField.get(state) as? List<*> ?: return
        val replaced = ArrayList<Any?>()
        var replacedCount = 0
        for (elem in tasks) {
            if (elem != null && elem.javaClass.name.endsWith("\$NoData")) {
                val elemTaskId = getField(elem, "taskId") as? Int
                if (elemTaskId != null && elemTaskId >= CARD_FAKE_BASE) {
                    val data = buildTileData(elem.javaClass.classLoader, firstTask, elemTaskId)
                    if (data != null) {
                        replaced.add(data)
                        replacedCount++
                        continue
                    }
                }
            }
            replaced.add(elem)
        }
        if (replacedCount == 0) return
        try {
            @Suppress("UNCHECKED_CAST")
            (tasks as MutableList<Any?>).clear()
            (tasks as MutableList<Any?>).addAll(replaced)
        } catch (_: Throwable) {
            try {
                tasksField.set(state, replaced)
            } catch (e: Throwable) {
                Log.w(TAG, "launcher: fake card tile fix could not apply replaced list", e)
                return
            }
        }
        Log.i(TAG, "launcher: fake card tile fix replaced $replacedCount NoData taskId=$cardTaskId")
    }

    private fun buildTileData(loader: ClassLoader?, fakeTask: Any, fakeTaskId: Int): Any? {
        try {
            val dataClass = Class.forName(
                "com.android.quickstep.recents.ui.viewmodel.TaskData\$Data", false, loader)
            val thumbnailClass = Class.forName(
                "com.android.systemui.shared.recents.model.ThumbnailData", false, loader)
            val ctor = dataClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                Drawable::class.java,
                thumbnailClass,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                java.time.Duration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            ctor.isAccessible = true
            val title = getField(fakeTask, "title") as? String ?: ""
            val titleDescription = getField(fakeTask, "titleDescription") as? String ?: title
            val packageName = fakeTaskPackageName(fakeTask)
            val icon = getField(fakeTask, "icon")
            val backgroundColor = getField(fakeTask, "colorBackground") as? Int
                ?: getField(fakeTask, "colorPrimary") as? Int ?: 0
            val isLocked = getField(fakeTask, "isLocked") as? Boolean ?: false
            val isAppLocked = getField(fakeTask, "isAppLockEnabled") as? Boolean ?: false
            val thumbnail = getField(fakeTask, "thumbnail")?.let { thumb ->
                if (thumbnailClass.isInstance(thumb)) thumb else null
            }
            try {
                ctor.newInstance(
                    fakeTaskId, title, titleDescription, packageName, icon, thumbnail,
                    backgroundColor, isLocked, isAppLocked, null, false)
            } catch (_: Throwable) {
                ctor.newInstance(
                    fakeTaskId, title, titleDescription, packageName, icon, thumbnail,
                    backgroundColor, isLocked, isAppLocked, java.time.Duration.ZERO, false)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: fake card tile fix build Data failed taskId=$fakeTaskId", e)
            null
        }
    }

    private fun fakeTaskPackageName(fakeTask: Any): String {
        val key = getField(fakeTask, "key") ?: return ""
        val fromKey = runCatching {
            val m = key.javaClass.methods.firstOrNull {
                it.name == "getPackageName" && it.parameterTypes.isEmpty()
            }
            m?.isAccessible = true
            m?.invoke(key) as? String
        }.getOrNull()
        if (!fromKey.isNullOrEmpty()) return fromKey
        val component = getField(key, "component") ?: return ""
        return runCatching {
            val m = component.javaClass.methods.firstOrNull {
                it.name == "getPackageName" && it.parameterTypes.isEmpty()
            }
            m?.isAccessible = true
            m?.invoke(component) as? String
        }.getOrNull() ?: ""
    }

    /**
     * TEMPORARY PROBE9, launcher side. Logging-only: on
     * TaskView.updateTaskViewState, for fake cards, log the tile
     * element's full class name, its classloader, and the Data
     * sibling class's methods, constructors and fields, so the fix
     * can construct a Data element without guessing its shape.
     */
    private fun installProbe9(module: XposedModule, cl: ClassLoader) {
        try {
            val taskViewClass = cl.loadClass("com.android.quickstep.views.TaskView")
            for (method in taskViewClass.declaredMethods) {
                if (!method.name.startsWith("updateTaskViewState")) continue
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            val state = chain.args.getOrNull(0) ?: return result
                            val taskView = chain.thisObject
                            val firstTask = runCatching {
                                val m = taskView?.javaClass?.methods?.firstOrNull {
                                    it.name == "getFirstTask" && it.parameterTypes.isEmpty()
                                }
                                m?.isAccessible = true
                                m?.invoke(taskView)
                            }.getOrNull()
                            val taskId = runCatching {
                                val key = getField(firstTask, "key")
                                getField(key, "id") as? Int
                            }.getOrNull()
                            if (taskId == null || taskId < CARD_FAKE_BASE) return result
                            val tasksField = state.javaClass.declaredFields.firstOrNull {
                                it.name == "tasks"
                            } ?: return result
                            tasksField.isAccessible = true
                            val tasks = tasksField.get(state) as? List<*> ?: return result
                            val firstElem = tasks.firstOrNull() ?: return result
                            val elemClass = firstElem.javaClass
                            Log.i(
                                TAG,
                                "launcher: PROBE9 tile element class=${elemClass.name} " +
                                    "loader=${elemClass.classLoader?.javaClass?.simpleName}",
                            )
                            // Sibling Data class: same outer class as
                            // NoData, with 'Data' as the nested name.
                            val outerName = elemClass.name.substringBeforeLast('$')
                            val dataNames = listOf(
                                "$outerName\$Data",
                                "${elemClass.name.substringBefore("NoData")}Data",
                            ).distinct()
                            for (dataName in dataNames) {
                                try {
                                    val dataClass = Class.forName(
                                        dataName, false, elemClass.classLoader)
                                    for (m in dataClass.declaredMethods) {
                                        Log.i(
                                            TAG,
                                            "launcher: PROBE9 dump $dataName method ${m.name}(" +
                                                m.parameterTypes.joinToString { it.name } +
                                                "):${m.returnType.name}",
                                        )
                                    }
                                    for (ctor in dataClass.declaredConstructors) {
                                        Log.i(
                                            TAG,
                                            "launcher: PROBE9 dump $dataName ctor(" +
                                                ctor.parameterTypes.joinToString { it.name } + ")",
                                        )
                                    }
                                    for (field in dataClass.declaredFields) {
                                        Log.i(
                                            TAG,
                                            "launcher: PROBE9 dump $dataName field " +
                                                "${field.name}:${field.type.name}",
                                        )
                                    }
                                } catch (e: Throwable) {
                                    Log.w(TAG, "launcher: PROBE9 dump $dataName NOT FOUND")
                                }
                            }
                            // Also dump the NoData class itself, so its
                            // taskId field name is confirmed for the fix.
                            for (field in elemClass.declaredFields) {
                                Log.i(
                                    TAG,
                                    "launcher: PROBE9 dump ${elemClass.name} field " +
                                        "${field.name}:${field.type.name}",
                                )
                            }
                        } catch (_: Throwable) {
                        }
                        return result
                    }
                })
            }
            Log.i(TAG, "launcher: PROBE9 hook installed on TaskView.updateTaskViewState")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE9 install failed", e)
        }
    }

    /**
     * TEMPORARY PROBE8, launcher side. Logging-only, same rules as
     * PROBE7: hook TaskView.updateTaskViewState, proceed, then log
     * the tile state's values (not just its field types). Bitmaps
     * are never logged by content, only by presence and size.
     */
    private fun installProbe8(module: XposedModule, cl: ClassLoader) {
        try {
            val taskViewClass = cl.loadClass("com.android.quickstep.views.TaskView")
            for (method in taskViewClass.declaredMethods) {
                if (!method.name.startsWith("updateTaskViewState")) continue
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try {
                            val state = chain.args.getOrNull(0) ?: return result
                            val taskView = chain.thisObject
                            val firstTask = runCatching {
                                val m = taskView?.javaClass?.methods?.firstOrNull {
                                    it.name == "getFirstTask" && it.parameterTypes.isEmpty()
                                }
                                m?.isAccessible = true
                                m?.invoke(taskView)
                            }.getOrNull()
                            val taskId = runCatching {
                                val key = getField(firstTask, "key")
                                getField(key, "id") as? Int
                            }.getOrNull()
                            val isFake = taskId != null && taskId >= CARD_FAKE_BASE
                            // Only fake cards and one real card per
                            // Recents open are worth logging in full;
                            // real cards otherwise spam the log.
                            if (!isFake && taskId != null && taskId % 10 != 0) return result
                            val taskTitle = runCatching {
                                getField(firstTask, "title") as? String
                            }.getOrNull()
                            val taskIconNull = runCatching {
                                getField(firstTask, "icon") == null
                            }.getOrNull()
                            val sb = StringBuilder()
                            for (field in state.javaClass.declaredFields) {
                                try {
                                    field.isAccessible = true
                                    val v = field.get(state)
                                    when {
                                        v == null -> sb.append("${field.name}=null;")
                                        v is Boolean -> sb.append("${field.name}=$v;")
                                        v is Int -> sb.append("${field.name}=$v;")
                                        v is List<*> -> {
                                            sb.append("${field.name}=List size=${v.size};")
                                            for ((i, elem) in v.withIndex()) {
                                                if (elem == null) {
                                                    sb.append("  [$i]=null;")
                                                    continue
                                                }
                                                sb.append("  [$i]${elem.javaClass.simpleName}:")
                                                for (ef in elem.javaClass.declaredFields) {
                                                    try {
                                                        ef.isAccessible = true
                                                        val ev = ef.get(elem)
                                                        sb.append(when {
                                                            ev == null -> "${ef.name}=null,"
                                                            ev is String -> "${ef.name}='${ev.take(40)}',"
                                                            ev is Int -> "${ef.name}=$ev,"
                                                            ev is Boolean -> "${ef.name}=$ev,"
                                                            ev is android.graphics.Bitmap -> "${ef.name}=Bitmap ${ev.width}x${ev.height},"
                                                            else -> "${ef.name}=${ev.javaClass.simpleName},"
                                                        })
                                                    } catch (_: Throwable) {
                                                    }
                                                }
                                                sb.append(";")
                                            }
                                        }
                                        else -> sb.append("${field.name}=${v.javaClass.simpleName};")
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                            Log.i(
                                TAG,
                                "launcher: PROBE8 updateTaskViewState taskId=$taskId isFake=$isFake " +
                                    "taskTitle='${taskTitle?.take(40)}' taskIconNull=$taskIconNull state=[$sb]",
                            )
                        } catch (_: Throwable) {
                        }
                        return result
                    }
                })
            }
            Log.i(TAG, "launcher: PROBE8 hook installed on TaskView.updateTaskViewState")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE8 install failed", e)
        }
    }

    /**
     * TEMPORARY PROBE7, launcher side. Logging-only: dump the tile
     * state classes, and log the tile state TaskView receives, so a
     * fake card's empty title / content can be traced to the loader
     * that clears it, instead of guessed at.
     */
    private fun installProbe7(module: XposedModule, cl: ClassLoader) {
        try {
            dumpProbeClass(cl, "PROBE7",
                "com.android.quickstep.recents.ui.viewmodel.TaskTileUiState")
            dumpProbeClass(cl, "PROBE7",
                "com.android.quickstep.recents.data.TasksRepository")
            dumpProbeClass(cl, "PROBE7",
                "com.android.quickstep.util.TasksRepository")
            // TaskView.updateTaskViewState is the point where async
            // tile state reaches the card UI. Log the state for every
            // card, and flag fake ids (900000+) explicitly, so a fake
            // card's empty state stands out from real cards' states.
            try {
                val taskViewClass = cl.loadClass("com.android.quickstep.views.TaskView")
                for (method in taskViewClass.declaredMethods) {
                    if (method.name != "updateTaskViewState" &&
                        !method.name.startsWith("updateTaskViewState")) continue
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            try {
                                val state = chain.args.getOrNull(0)
                                val taskView = chain.thisObject
                                val firstTask = runCatching {
                                    val m = taskView?.javaClass?.methods?.firstOrNull {
                                        it.name == "getFirstTask" && it.parameterTypes.isEmpty()
                                    }
                                    m?.isAccessible = true
                                    m?.invoke(taskView)
                                }.getOrNull()
                                val taskId = runCatching {
                                    val key = getField(firstTask, "key")
                                    getField(key, "id") as? Int
                                }.getOrNull()
                                val stateDesc = if (state == null) {
                                    "null"
                                } else {
                                    val fields = state.javaClass.declaredFields.mapNotNull { f ->
                                        runCatching {
                                            f.isAccessible = true
                                            val v = f.get(state)
                                            "${f.name}=${if (v == null) "null" else v.javaClass.simpleName}"
                                        }.getOrNull()
                                    }.joinToString()
                                    "${state.javaClass.simpleName}[$fields]"
                                }
                                Log.i(
                                    TAG,
                                    "launcher: PROBE7 updateTaskViewState taskId=$taskId " +
                                        "isFake=${taskId != null && taskId >= CARD_FAKE_BASE} state=$stateDesc",
                                )
                            } catch (_: Throwable) {
                            }
                            return result
                        }
                    })
                }
            } catch (e: Throwable) {
                Log.w(TAG, "launcher: PROBE7 hook updateTaskViewState failed", e)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE7 install failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Recents card injection (launcher side, PROBE6-complete).
    // ------------------------------------------------------------------

    private const val CARD_FAKE_BASE = 900000

    private data class CardWindow(val windowId: Int, val packageName: String, val label: String)

    private val cardCacheLock = Any()
    private var cardCachedWindows: List<CardWindow> = emptyList()
    private var cardReceiverRegistered = false

    /**
     * Inject fake SingleTasks for FreeWindow windows into Recents.
     *
     * Safety, in order of importance:
     * - The whole injection is best-effort: any failure logs and the
     *   original list proceeds unchanged, so real Recents never breaks
     *   because of a fake card.
     * - Fake ids are 900000+windowId (window ids 1..8), far above real
     *   task ids, and a fake id already in the list is never duplicated.
     * - The original list is an ArrayList (PROBE6), so fakes are added
     *   in place; if a future build hands an unmodifiable list, the
     *   hook falls back to a copy and replaces the argument.
     * - A window without a launch intent is skipped, not faked with a
     *   broken component (same rule as the system_server fake tasks).
     * - Fake cards carry a " (自由窗口)" title suffix, so a windowized
     *   app does not show two identical cards (its real main-display
     *   task stays in Recents alongside the fake FreeWindow card).
     * - The load identifier (applyLoadPlan's int argument) is left
     *   untouched; only the list is modified.
     * - The cache is async, like the system_server one: a window
     *   change broadcasts WINDOWS_CHANGED, and the next Recents open
     *   after that shows the fake card. The first Recents open after
     *   a launcher restart may miss fakes, because the REQUEST_WINDOWS
     *   reply has not arrived yet; the second open shows them.
     */
    private fun installCardInjection(module: XposedModule, cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("com.android.quickstep.views.RecentsView")
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "applyLoadPlan" && it.parameterTypes.size == 2
            } ?: run {
                Log.w(TAG, "launcher: recents-cards applyLoadPlan not found")
                return
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        injectCards(chain)
                    } catch (e: Throwable) {
                        Log.w(TAG, "launcher: recents-cards inject failed, original list kept", e)
                    }
                    return chain.proceed()
                }
            })
            Log.i(TAG, "launcher: recents-cards hook installed on RecentsView.applyLoadPlan")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: recents-cards install failed", e)
        }
    }

    private fun injectCards(chain: XposedInterface.Chain) {
        val recentsView = chain.thisObject as? View ?: return
        val context = recentsView.context ?: return
        ensureCardReceiver(context)
        val windows = synchronized(cardCacheLock) { cardCachedWindows }
        if (windows.isEmpty()) return
        val listArg = chain.args.getOrNull(0) as? MutableList<Any?> ?: run {
            // Not a MutableList (unmodifiable / Kotlin read-only view):
            // copy, inject into the copy, and replace the argument.
            val orig = chain.args.getOrNull(0) as? List<*> ?: return
            val copy = ArrayList<Any?>(orig)
            val added = addFakeCards(context, copy)
            if (added > 0) chain.args[0] = copy
            if (added > 0) Log.i(TAG, "launcher: recents-cards injected $added fake cards (copied list)")
            return
        }
        val added = addFakeCards(context, listArg)
        if (added > 0) Log.i(TAG, "launcher: recents-cards injected $added fake cards")
    }

    private fun addFakeCards(context: Context, list: MutableList<Any?>): Int {
        // Template Task for colours / defaults; null when the list is
        // empty (a Recents open with no real tasks at all). The first
        // list item itself is also kept, because its runtime class is
        // the SingleTask class to construct fakes with.
        val firstItem = list.firstOrNull()
        val templateTask = firstItem?.let { cardTaskOf(it) }
        val existingIds = list.flatMap { cardTaskIdsOf(it) }.toSet()
        var added = 0
        for (window in synchronized(cardCacheLock) { cardCachedWindows }) {
            val fakeId = CARD_FAKE_BASE + window.windowId
            if (fakeId in existingIds) continue
            try {
                val fakeSingle = buildFakeSingle(context, window, fakeId, firstItem, templateTask) ?: continue
                list.add(fakeSingle)
                added++
            } catch (e: Throwable) {
                Log.w(TAG, "launcher: recents-cards build fake for ${window.packageName} failed", e)
            }
        }
        return added
    }

    /** The Task inside a SingleTask, or null for a GroupTask / unknown item. */
    private fun cardTaskOf(item: Any?): Any? {
        if (item == null) return null
        return try {
            val getTask = item.javaClass.methods.firstOrNull {
                it.name == "getTask" && it.parameterTypes.isEmpty()
            } ?: return null
            getTask.isAccessible = true
            getTask.invoke(item)
        } catch (_: Throwable) {
            null
        }
    }

    /** All Task ids inside a list item (SingleTask: one, GroupTask: many). */
    private fun cardTaskIdsOf(item: Any?): List<Int> {
        if (item == null) return emptyList()
        return try {
            val single = cardTaskOf(item)
            if (single != null) {
                val id = cardTaskId(single)
                return if (id != null) listOf(id) else emptyList()
            }
            val getTasks = item.javaClass.methods.firstOrNull {
                it.name == "getTasks" && it.parameterTypes.isEmpty()
            } ?: return emptyList()
            getTasks.isAccessible = true
            val tasks = getTasks.invoke(item) as? List<*> ?: return emptyList()
            tasks.mapNotNull { cardTaskId(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun cardTaskId(task: Any?): Int? {
        if (task == null) return null
        return try {
            val key = getField(task, "key") ?: return null
            getField(key, "id") as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildFakeSingle(
        context: Context,
        window: CardWindow,
        fakeId: Int,
        templateSingle: Any?,
        templateTask: Any?,
    ): Any? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(window.packageName) ?: run {
                Log.w(TAG, "launcher: recents-cards no launch intent for ${window.packageName}")
                return null
            }
        val component = launchIntent.component ?: return null
        // Task / TaskKey classes must come from a runtime instance when
        // one exists (install-time loadClass hits the stub, PROBE4);
        // at Recents-open time the real classes are loaded, so the
        // fallback loadClass below also works then.
        val taskClass: Class<*> = templateTask?.javaClass
            ?: runCatching { context.classLoader.loadClass(
                "com.android.systemui.shared.recents.model.Task") }.getOrNull()
            ?: return null
        val keyClass: Class<*> = templateTask?.let { getField(it, "key")?.javaClass }
            ?: runCatching { context.classLoader.loadClass(
                "com.android.systemui.shared.recents.model.Task\$TaskKey") }.getOrNull()
            ?: return null
        // TaskKey(int id, int windowingMode, Intent baseIntent,
        // ComponentName component, int userId, long lastActiveTime).
        // windowingMode 1 = FULLSCREEN, so the card renders as a normal
        // card, not a freeform one.
        val keyCtor = keyClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 6 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Intent::class.java &&
                it.parameterTypes[3] == android.content.ComponentName::class.java &&
                it.parameterTypes[4] == Int::class.javaPrimitiveType &&
                it.parameterTypes[5] == Long::class.javaPrimitiveType
        } ?: run {
            Log.w(TAG, "launcher: recents-cards TaskKey 6-arg ctor not found")
            return null
        }
        keyCtor.isAccessible = true
        val fakeKey = keyCtor.newInstance(
            fakeId, 1, launchIntent, component, 0, System.currentTimeMillis())
        // mHashCode is computed by the constructor; refresh it anyway
        // in case this build's ctor leaves it at zero.
        runCatching {
            val update = keyClass.methods.firstOrNull {
                it.name == "updateHashCode" && it.parameterTypes.isEmpty()
            }
            update?.isAccessible = true
            update?.invoke(fakeKey)
        }
        // Task(TaskKey), then fill the display fields. All Task fields
        // are set accessible first, so a private field on some build
        // does not abort the whole fake.
        val taskCtor = taskClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == keyClass
        } ?: run {
            Log.w(TAG, "launcher: recents-cards Task(TaskKey) ctor not found")
            return null
        }
        taskCtor.isAccessible = true
        val fakeTask = taskCtor.newInstance(fakeKey)
        val fakeTitle = "${window.label} (自由窗口)"
        setFieldBestEffort(fakeTask, "title", fakeTitle)
        setFieldBestEffort(fakeTask, "titleDescription", fakeTitle)
        setFieldBestEffort(fakeTask, "topActivity", component)
        val templateIcon = if (templateTask != null) getField(templateTask, "icon") else null
        val icon = runCatching {
            context.packageManager.getApplicationIcon(window.packageName)
        }.getOrNull() ?: templateIcon
        setFieldBestEffort(fakeTask, "icon", icon)
        // Thumbnail is null in v1: the card shows its icon and title,
        // and TaskView's thumbnail loader has no real task to load from
        // for a fake id. A FreeWindow screenshot thumbnail is a
        // follow-up, not a v1 requirement.
        setFieldBestEffort(fakeTask, "thumbnail", null)
        setFieldBestEffort(
            fakeTask, "taskDescription",
            android.app.ActivityManager.TaskDescription(fakeTitle, null, 0))
        if (templateTask != null) {
            for (name in listOf(
                "colorPrimary", "colorBackground", "isDockable",
                "isLocked", "isMinimized", "isVisible", "isAppLockEnabled",
            )) {
                getField(templateTask, name)?.let { setFieldBestEffort(fakeTask, name, it) }
            }
        } else {
            setFieldBestEffort(fakeTask, "isVisible", true)
        }
        // SingleTask(Task): the wrapper applyLoadPlan's list holds.
        // Its class comes from a runtime list item when one exists;
        // the fallback loadClass only runs when the list was empty.
        val singleClass: Class<*> = templateSingle?.javaClass
            ?: runCatching { context.classLoader.loadClass(
                "com.android.quickstep.util.SingleTask") }.getOrNull()
            ?: return null
        val singleCtor = singleClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == taskClass
        } ?: run {
            Log.w(TAG, "launcher: recents-cards SingleTask(Task) ctor not found")
            return null
        }
        singleCtor.isAccessible = true
        return singleCtor.newInstance(fakeTask)
    }

    private fun ensureCardReceiver(context: Context) {
        if (cardReceiverRegistered) return
        synchronized(cardCacheLock) {
            if (cardReceiverRegistered) return
            try {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != RecentsBridge.ACTION_WINDOWS_CHANGED) return
                        try {
                            val ids = intent.getIntArrayExtra(RecentsBridge.EXTRA_WINDOW_IDS) ?: return
                            val packages = intent.getStringArrayExtra(RecentsBridge.EXTRA_PACKAGES) ?: return
                            val labels = intent.getStringArrayExtra(RecentsBridge.EXTRA_LABELS) ?: return
                            if (ids.size != packages.size || ids.size != labels.size) {
                                Log.w(TAG, "launcher: recents-cards WINDOWS_CHANGED length mismatch")
                                return
                            }
                            val list = ids.indices.map { i ->
                                CardWindow(ids[i], packages[i], labels[i])
                            }
                            synchronized(cardCacheLock) { cardCachedWindows = list }
                            Log.i(TAG, "launcher: recents-cards cached ${list.size} windows")
                        } catch (e: Throwable) {
                            Log.w(TAG, "launcher: recents-cards WINDOWS_CHANGED parse failed", e)
                        }
                    }
                }
                val filter = IntentFilter(RecentsBridge.ACTION_WINDOWS_CHANGED)
                // EXPORTED: the sender is SystemUI, a different app.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                cardReceiverRegistered = true
                // Bootstrap: SystemUI answers with WINDOWS_CHANGED.
                // The reply is async, so the current Recents open may
                // miss fakes; the next open shows them (see KDoc).
                context.sendBroadcast(
                    Intent(RecentsBridge.ACTION_REQUEST_WINDOWS).apply {
                        setPackage("com.android.systemui")
                    },
                )
            } catch (e: Throwable) {
                Log.w(TAG, "launcher: recents-cards receiver register failed", e)
            }
        }
    }

    private fun setFieldBestEffort(target: Any, name: String, value: Any?) {
        try {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    field.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
        } catch (_: Throwable) {
            // Best-effort: a missing field just leaves that attribute
            // at its constructor default.
        }
    }

    /**
     * TEMPORARY PROBE6, launcher side. Hooks RecentsView.applyLoadPlan
     * only to inspect its arguments, then proceeds unchanged. No fake
     * card is injected yet; this probe only learns how a real Task
     * is built, so the injection can clone one.
     */
    private fun installProbe6(module: XposedModule, cl: ClassLoader) {
        try {
            val clazz = cl.loadClass("com.android.quickstep.views.RecentsView")
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "applyLoadPlan" && it.parameterTypes.size == 2
            } ?: run {
                Log.w(TAG, "launcher: PROBE6 applyLoadPlan not found")
                return
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val list = chain.args.getOrNull(0) as? List<*>
                        Log.i(
                            TAG,
                            "launcher: PROBE6 applyLoadPlan listClass=${list?.javaClass?.name} " +
                                "listSize=${list?.size} intArg=${chain.args.getOrNull(1) as? Int}",
                        )
                        val first = list?.firstOrNull() ?: return result
                        Log.i(TAG, "launcher: PROBE6 firstItemClass=${first.javaClass.name}")
                        // SingleTask.getTask() is the real Task object;
                        // its class is the runtime Task class, not the
                        // install-time stub that PROBE4 could not dump.
                        val getTask = first.javaClass.methods.firstOrNull {
                            it.name == "getTask" && it.parameterTypes.isEmpty()
                        } ?: run {
                            Log.w(TAG, "launcher: PROBE6 first item has no getTask()")
                            return result
                        }
                        getTask.isAccessible = true
                        val task = getTask.invoke(first) ?: return result
                        val taskClass = task.javaClass
                        Log.i(TAG, "launcher: PROBE6 taskClass=${taskClass.name}")
                        for (m in taskClass.declaredMethods) {
                            Log.i(
                                TAG,
                                "launcher: PROBE6 dump task method ${m.name}(" +
                                    m.parameterTypes.joinToString { it.name } + "):${m.returnType.name}",
                            )
                        }
                        for (ctor in taskClass.declaredConstructors) {
                            Log.i(
                                TAG,
                                "launcher: PROBE6 dump task ctor(" +
                                    ctor.parameterTypes.joinToString { it.name } + ")",
                            )
                        }
                        for (field in taskClass.declaredFields) {
                            Log.i(
                                TAG,
                                "launcher: PROBE6 dump task field ${field.name}:${field.type.name}",
                            )
                        }
                        // TaskKey is the task's identity (id, component,
                        // baseIntent). Its class comes from the task's
                        // key field, again at runtime, not install time.
                        var keyClass: Class<*>? = null
                        for (field in taskClass.declaredFields) {
                            if (field.name == "key") {
                                keyClass = field.type
                                break
                            }
                        }
                        if (keyClass != null) {
                            for (m in keyClass.declaredMethods) {
                                Log.i(
                                    TAG,
                                    "launcher: PROBE6 dump key method ${m.name}(" +
                                        m.parameterTypes.joinToString { it.name } + "):${m.returnType.name}",
                                )
                            }
                            for (ctor in keyClass.declaredConstructors) {
                                Log.i(
                                    TAG,
                                    "launcher: PROBE6 dump key ctor(" +
                                        ctor.parameterTypes.joinToString { it.name } + ")",
                                )
                            }
                            for (field in keyClass.declaredFields) {
                                Log.i(
                                    TAG,
                                    "launcher: PROBE6 dump key field ${field.name}:${field.type.name}",
                                )
                            }
                        } else {
                            Log.w(TAG, "launcher: PROBE6 task has no key field")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "launcher: PROBE6 inspect failed", e)
                    }
                    return result
                }
            })
            Log.i(TAG, "launcher: PROBE6 hook installed on RecentsView.applyLoadPlan")
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: PROBE6 install failed", e)
        }
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

    private fun getField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return runCatching {
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
    }

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
