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
import io.github.loveqianool.freewindow.bridge.RecentsBridge
import io.github.loveqianool.freewindow.util.Reflect

/**
 * Launcher (com.android.launcher3, Trebuchet) side of Recents integration.
 *
 * Only the task menu is hooked here, not the task list: in current
 * Launcher3 the task list is built around GroupTask/DesktopTask, whose
 * shape changed a lot across versions. On v17 QuickStep the menu is
 * built per item: TaskMenuView.addMenuOption is called once per
 * built-in item ("应用信息 / 分屏" etc.), while the batch
 * addMenuOptions is dead code that is never called on menu open
 * (proven on device, 2026-11-01). This hook therefore appends
 * the FreeWindow option from addMenuOption, moving it to the end on
 * each subsequent item so it lands last. Card injection for FreeWindow
 * windows is launcher-side, in RecentsView.applyLoadPlan, not via
 * GroupTask and not via the system_server recent-task list.
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
 * SystemUI (same broadcast protocol as the Recents card actions) and
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
        // Same for the tablet / grid variant: it was only hooked via
        // the dead addMenuOptions path, so on a WithArrow build the
        // FreeWindow option never appeared. Safe to add — the hook
        // logs and returns if the 1-arg method is absent there.
        hookAddMenuOption(module, classLoader, "com.android.quickstep.views.TaskMenuViewWithArrow")
        // Card injection (2026-11-01): append a fake
        // SingleTask per FreeWindow window to the Recents list, in
        // RecentsView.applyLoadPlan, before the original method runs.
        // The window list comes from SystemUI's WINDOWS_CHANGED
        // broadcast; fake
        // ids are 900000+windowId, so a tap / swipe on a fake card
        // reaches the system_server startActivityFromRecents /
        // removeTask interception, which forwards RECENTS_RESTORE /
        // RECENTS_CLOSE to SystemUI. See the function KDoc for the
        // safety rules that keep real Recents intact.
        installCardInjection(module, classLoader)
        // Fake-card tile fix (Data shape proven on device, 2026-11-01):
        // on TaskView.updateTaskViewState, before the original
        // method renders, replace each fake NoData tile element with
        // a Data element built from the card's own fake Task
        // (title, icon, package, colors, lock flags; thumbnail is
        // null in v1, as the fake Task itself has none yet). Real
        // cards are untouched (taskId < RecentsBridge.FAKE_TASK_BASE returns
        // early), and any failure leaves the original state in
        // place, so this can only leave a card blank, never break
        // Recents.
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

    // updateTaskViewState runs on the launcher UI thread for every
    // visible card on every state update, so the reflective lookups
    // below are cached per class instead of re-scanned each call.
    // Classes are stable for the launcher process lifetime, so the
    // cache never goes stale; a miss just means "not found".
    private val firstTaskMethodCache = mutableMapOf<Class<*>, java.lang.reflect.Method?>()
    private val tasksFieldCache = mutableMapOf<Class<*>, java.lang.reflect.Field?>()
    private var tileCtorCache: Triple<ClassLoader?, java.lang.reflect.Constructor<*>, Class<*>>? = null

    private fun replaceNoDataWithData(chain: XposedInterface.Chain) {
        val state = chain.args.getOrNull(0) ?: return
        val taskView = chain.thisObject ?: return
        val firstTask = runCatching {
            val m = firstTaskMethodCache.getOrPut(taskView.javaClass) {
                taskView.javaClass.methods.firstOrNull {
                    it.name == "getFirstTask" && it.parameterTypes.isEmpty()
                }?.apply { isAccessible = true }
            }
            m?.invoke(taskView)
        }.getOrNull() ?: return
        val cardTaskId = runCatching {
            val key = Reflect.getField(firstTask, "key")
            Reflect.getField(key, "id") as? Int
        }.getOrNull() ?: return
        if (cardTaskId < RecentsBridge.FAKE_TASK_BASE) return
        val tasksField = tasksFieldCache.getOrPut(state.javaClass) {
            state.javaClass.declaredFields.firstOrNull {
                it.name == "tasks"
            }?.apply { isAccessible = true }
        } ?: return
        val tasks = tasksField.get(state) as? List<*> ?: return
        val replaced = ArrayList<Any?>()
        var replacedCount = 0
        for (elem in tasks) {
            if (elem != null && elem.javaClass.name.endsWith("\$NoData")) {
                val elemTaskId = Reflect.getField(elem, "taskId") as? Int
                if (elemTaskId != null && elemTaskId >= RecentsBridge.FAKE_TASK_BASE) {
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
            val mutableTasks = tasks as MutableList<Any?>
            mutableTasks.clear()
            mutableTasks.addAll(replaced)
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
        return try {
            // Class.forName + getDeclaredConstructor on every card,
            // every update, was the hot part of this fix; cache the
            // constructor per loader (stable per process).
            val cached = tileCtorCache
            val (ctor, thumbnailClass) = if (cached != null && cached.first === loader) {
                cached.second to cached.third
            } else {
                val dataClass = Class.forName(
                    "com.android.quickstep.recents.ui.viewmodel.TaskData\$Data", false, loader)
                val thumbClass = Class.forName(
                    "com.android.systemui.shared.recents.model.ThumbnailData", false, loader)
                val c = dataClass.getDeclaredConstructor(
                    java.lang.Integer.TYPE,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Drawable::class.java,
                    thumbClass,
                    java.lang.Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                    java.time.Duration::class.java,
                    java.lang.Boolean.TYPE,
                ).apply { isAccessible = true }
                tileCtorCache = Triple(loader, c, thumbClass)
                c to thumbClass
            }
            val title = Reflect.getField(fakeTask, "title") as? String ?: ""
            val titleDescription = Reflect.getField(fakeTask, "titleDescription") as? String ?: title
            val packageName = fakeTaskPackageName(fakeTask)
            val icon = Reflect.getField(fakeTask, "icon")
            val backgroundColor = Reflect.getField(fakeTask, "colorBackground") as? Int
                ?: Reflect.getField(fakeTask, "colorPrimary") as? Int ?: 0
            val isLocked = Reflect.getField(fakeTask, "isLocked") as? Boolean ?: false
            val isAppLocked = Reflect.getField(fakeTask, "isAppLockEnabled") as? Boolean ?: false
            val thumbnail = Reflect.getField(fakeTask, "thumbnail")?.let { thumb ->
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
        val key = Reflect.getField(fakeTask, "key") ?: return ""
        // Same method search as Reflect.callNoArg, reused instead of
        // a third copy of it.
        val fromKey = Reflect.callNoArg(key, "getPackageName") as? String
        if (!fromKey.isNullOrEmpty()) return fromKey
        val component = Reflect.getField(key, "component") ?: return ""
        return Reflect.callNoArg(component, "getPackageName") as? String ?: ""
    }

    // Recents card injection (launcher side).
    // ------------------------------------------------------------------


    private data class CardWindow(
        val windowId: Int,
        val packageName: String,
        val label: String,
        val flags: Int = 0,
    )

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
     * - Fake ids are 900000+windowId (window ids are monotonic, not
     *   capped at 8), far above real task ids, and a fake id already
     *   in the list is never duplicated.
     * - The original list is an ArrayList, so fakes are added
     *   in place; if a future build hands an unmodifiable list, the
     *   hook falls back to a copy and replaces the argument.
     * - A window without a launch intent is skipped, not faked with a
     *   broken component.
     * - Fake cards carry a " (自由窗口)" title suffix, so a windowized
     *   app does not show two identical cards (its real main-display
     *   task stays in Recents alongside the fake FreeWindow card).
     * - The load identifier (applyLoadPlan's int argument) is left
     *   untouched; only the list is modified.
     * - The cache is async: a window
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
            val fakeId = RecentsBridge.FAKE_TASK_BASE + window.windowId
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
            val key = Reflect.getField(task, "key") ?: return null
            Reflect.getField(key, "id") as? Int
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
        // one exists (install-time loadClass hits the stub);
        // at Recents-open time the real classes are loaded, so the
        // fallback loadClass below also works then.
        val taskClass: Class<*> = templateTask?.javaClass
            ?: runCatching { context.classLoader.loadClass(
                "com.android.systemui.shared.recents.model.Task") }.getOrNull()
            ?: return null
        val keyClass: Class<*> = templateTask?.let { Reflect.getField(it, "key")?.javaClass }
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
        // The flags are broadcast with the window list; use them so a
        // card shows its state, not just "(自由窗口)" for every state.
        val stateMarks = buildString {
            if (window.flags and RecentsBridge.FLAG_PARKED != 0) append("·已隐藏")
            if (window.flags and RecentsBridge.FLAG_COLLAPSED != 0) append("·已收起")
            if (window.flags and RecentsBridge.FLAG_MUTED != 0) append("·已静音")
        }
        val fakeTitle = "${window.label} (自由窗口$stateMarks)"
        setFieldBestEffort(fakeTask, "title", fakeTitle)
        setFieldBestEffort(fakeTask, "titleDescription", fakeTitle)
        setFieldBestEffort(fakeTask, "topActivity", component)
        val templateIcon = if (templateTask != null) Reflect.getField(templateTask, "icon") else null
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
                Reflect.getField(templateTask, name)?.let { setFieldBestEffort(fakeTask, name, it) }
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
                // Application context, not the RecentsView's Activity
                // context: an Activity-registered receiver is unregistered
                // by the system when the Activity is destroyed (config
                // change, dark mode, fold/unfold), while the
                // cardReceiverRegistered flag would stay true and the
                // cache would stop updating until the launcher process
                // restarted. An application-context receiver lives for
                // the process lifetime, so the flag stays valid.
                val appContext = context.applicationContext ?: context
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != RecentsBridge.ACTION_WINDOWS_CHANGED) return
                        // Sender check: this receiver is EXPORTED, so
                        // without a check any app could forge a
                        // WINDOWS_CHANGED broadcast and plant fake
                        // cards (title and icon of its choosing) in
                        // Recents. Only SystemUI may send this. Fail
                        // CLOSED: an unknown sender (INVALID_UID, i.e.
                        // identity not shared) or a uid lookup failure
                        // is dropped, never accepted. The real API is
                        // getSentFromUid() (34+); a previous build
                        // checked a non-existent getSendingUid() and
                        // never actually filtered anything.
                        val senderUid = getSentFromUid()
                        val systemUiUid = RecentsBridge.packageUid(context, "com.android.systemui")
                        if (senderUid == android.os.Process.INVALID_UID) {
                            // See RecentsBridge: INVALID_UID after an
                            // update usually means SystemUI is still on
                            // an old build (no full reboot), so its
                            // WINDOWS_CHANGED carries no identity and
                            // is dropped here — cards stop updating.
                            Log.w(TAG, "launcher: recents-cards dropped WINDOWS_CHANGED: sender did not share identity (INVALID_UID) — likely version skew: SystemUI still on an old build, full reboot required")
                            return
                        }
                        if (systemUiUid == null || senderUid != systemUiUid) {
                            Log.w(TAG, "launcher: recents-cards dropped WINDOWS_CHANGED from unlisted uid=$senderUid")
                            return
                        }
                        try {
                            val ids = intent.getIntArrayExtra(RecentsBridge.EXTRA_WINDOW_IDS) ?: return
                            val packages = intent.getStringArrayExtra(RecentsBridge.EXTRA_PACKAGES) ?: return
                            val labels = intent.getStringArrayExtra(RecentsBridge.EXTRA_LABELS) ?: return
                            val flags = intent.getIntArrayExtra(RecentsBridge.EXTRA_FLAGS)
                            if (ids.size != packages.size || ids.size != labels.size ||
                                (flags != null && flags.size != ids.size)
                            ) {
                                Log.w(TAG, "launcher: recents-cards WINDOWS_CHANGED length mismatch")
                                return
                            }
                            val list = ids.indices.map { i ->
                                CardWindow(ids[i], packages[i], labels[i], flags?.get(i) ?: 0)
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
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                cardReceiverRegistered = true
                // Bootstrap: SystemUI answers with WINDOWS_CHANGED.
                // The reply is async, so the current Recents open may
                // miss fakes; the next open shows them (see KDoc).
                RecentsBridge.sendWithIdentity(
                    appContext,
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
     * Hook the per-item builder TaskMenuView.addMenuOption(1 arg).
     * On device (2026-11-01) it is called once per built-in
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
                        val container = Reflect.callNoArg(menuView, "getTaskContainer")
                            ?: Reflect.getField(menuView, "mTaskContainer")
                            ?: Reflect.getField(menuView, "taskContainer")
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
                            ?: Reflect.getField(menuView, "mTaskContainer")
                            ?: Reflect.getField(menuView, "taskContainer")
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
        val task = Reflect.callNoArg(taskContainer, "getTask") ?: run {
            Log.w(TAG, "launcher: task container getTask not found")
            return
        }
        val key = Reflect.getField(task, "key") ?: run {
            Log.w(TAG, "launcher: Task.key not found")
            return
        }
        val taskId = Reflect.getField(key, "id") as? Int ?: run {
            Log.w(TAG, "launcher: TaskKey.id not found")
            return
        }
        val baseIntent = Reflect.getField(key, "baseIntent") as? Intent ?: run {
            Log.w(TAG, "launcher: TaskKey.baseIntent null, cannot window task")
            return
        }
        val component = baseIntent.component ?: run {
            Log.w(TAG, "launcher: baseIntent has no component, cannot window task")
            return
        }
        // mOptionLayout holds the menu option views (LinearLayout in the
        // Java TaskMenuView; the Kotlin version names it optionLayout).
        val optionLayout = (Reflect.getField(menuView, "mOptionLayout") as? ViewGroup)
            ?: (Reflect.getField(menuView, "optionLayout") as? ViewGroup)
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
            RecentsBridge.sendWithIdentity(context, intent)
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
                // ClassCastException and kills the whole append (seen on
                // device, 2026-11-01). Safe-cast so a non-ImageView icon
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
            val taskView = Reflect.getField(menuView, "mTaskView") ?: return
            val handler = Reflect.callNoArg(taskView, "getPagedOrientationHandler") ?: return
            // mActivity in the Java version, mContainer in the Kotlin
            // version; either way, getDeviceProfile is looked up by name.
            val activity = Reflect.getField(menuView, "mActivity")
                ?: Reflect.getField(menuView, "mContainer")
                ?: return
            val deviceProfile = Reflect.callNoArg(activity, "getDeviceProfile") ?: return
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

    private fun getRInt(className: String, fieldName: String): Int? = runCatching {
        // The R class lives in the launcher APK, so it must be loaded
        // through the launcher's classloader, not this module's: the
        // module's classloader cannot see the launcher's R class.
        val clazz = launcherClassLoader?.loadClass(className)
            ?: Class.forName(className)
        clazz.getField(fieldName).getInt(null)
    }.getOrNull()
}
