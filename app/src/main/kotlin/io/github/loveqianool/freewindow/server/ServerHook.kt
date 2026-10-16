package io.github.loveqianool.freewindow.server

import android.content.res.Configuration
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.loveqianool.freewindow.window.WindowMute

/**
 * system_server hooks. Currently only the display-capability matching:
 * FreeWindow-* virtual displays inherit color mode / touchscreen / keyboard /
 * navigation from the physical display so apps render correctly.
 */
object ServerHook {
    private const val TAG = "FreeWindow"
    private const val PREFS = "freewindow_mute"
    private const val MUTED_PKGS = "muted_pkgs"
    private var installed = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true
        installDisplayHook(module, classLoader)
        hookSystemReady(module, classLoader)
    }

    /**
     * Hooks AMS.systemReady() to register the mute receiver.
     * onSystemServerStarting is too early: ActivityThread.currentApplication()
     * returns null in system_server, and AMS isn't up yet.
     */
    private fun hookSystemReady(module: XposedModule, cl: ClassLoader) {
        try {
            val ams = cl.loadClass("com.android.server.am.ActivityManagerService")
            // Match the systemReady(Runnable) overload; some ROMs have multiple.
            val m = ams.declaredMethods.firstOrNull {
                it.name == "systemReady" &&
                    it.parameterTypes.any { p -> p == Runnable::class.java }
            } ?: ams.declaredMethods.firstOrNull { it.name == "systemReady" } ?: run {
                Log.w(TAG, "systemReady not found")
                return
            }
            m.isAccessible = true
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val ctx = getField(chain.thisObject!!, "mContext") as? android.content.Context
                        if (ctx != null) {
                            installMuteReceiver(ctx)
                        } else {
                            Log.w(TAG, "systemReady: mContext null")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "installMuteReceiver from systemReady failed", e)
                    }
                    return r
                }
            })
            Log.i(TAG, "systemReady hook installed")
        } catch (e: Throwable) {
            Log.w(TAG, "hook systemReady failed", e)
        }
    }

    /**
     * Registers the mute broadcast receiver. Runs in system_server (uid 1000),
     * which holds MANAGE_APP_OPS_MODES — systemui does not.
     *
     * The receiver requires STATUS_BAR_SERVICE permission (held by systemui),
     * so third-party apps cannot mute arbitrary packages.
     *
     * Muted packages are persisted; on registration any leftovers from a
     * crash/reboot (where closeWindow never ran) are restored to default.
     */
    private fun installMuteReceiver(ctx: android.content.Context) {
        try {
            // Restore: clear any persistent mute left by a crash/reboot.
            val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            prefs.getStringSet(MUTED_PKGS, emptySet())?.forEach { pkg ->
                try {
                    val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
                    doSetMuted(pkg, uid, false)
                } catch (e: Throwable) {
                    Log.w(TAG, "restore mute failed for $pkg", e)
                }
            }
            prefs.edit().remove(MUTED_PKGS).apply()

            val thread = android.os.HandlerThread("FreeWindowMute").apply { start() }
            val handler = android.os.Handler(thread.looper)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    Log.d(TAG, "mute receiver: got broadcast ${intent.action}")
                    if (intent.action != WindowMute.ACTION_MUTE) return
                    val pkg = intent.getStringExtra(WindowMute.EXTRA_PACKAGE) ?: return
                    val muted = intent.getBooleanExtra(WindowMute.EXTRA_MUTED, false)
                    // Compute uid in system_server for the current user.
                    // (Multi-user / app clones need getApplicationInfoAsUser;
                    // not yet supported — only the current user is handled.)
                    val uid = try {
                        ctx.packageManager.getApplicationInfo(pkg, 0).uid
                    } catch (e: Throwable) {
                        Log.w(TAG, "mute: uid lookup failed for $pkg", e)
                        return
                    }
                    // Persist the muted set so a crash/reboot can be cleaned up.
                    val set = prefs.getStringSet(MUTED_PKGS, emptySet())?.toMutableSet()
                        ?: mutableSetOf()
                    if (muted) set.add(pkg) else set.remove(pkg)
                    prefs.edit().putStringSet(MUTED_PKGS, set).apply()
                    doSetMuted(pkg, uid, muted)
                }
            }
            ctx.registerReceiver(
                receiver,
                android.content.IntentFilter(WindowMute.ACTION_MUTE),
                "android.permission.STATUS_BAR_SERVICE",
                handler,
                android.content.Context.RECEIVER_EXPORTED,
            )
            Log.i(TAG, "mute receiver registered")
        } catch (e: Throwable) {
            Log.w(TAG, "mute receiver install failed", e)
        }
    }

    /** Runs in system_server; has MANAGE_APP_OPS_MODES. */
    private fun doSetMuted(packageName: String, uid: Int, muted: Boolean) {
        try {
            val op = try {
                android.app.AppOpsManager::class.java
                    .getDeclaredField("OP_PLAY_AUDIO")
                    .apply { isAccessible = true }.getInt(null)
            } catch (e: Throwable) { 28 }
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, android.content.Context.APP_OPS_SERVICE) as? android.os.IBinder
                ?: return
            val stub = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            val appOps = stub.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return
            // MODE_DEFAULT (3) clears our override and restores system default,
            // instead of leaving a persistent explicit MODE_ALLOWED.
            val mode = if (muted) android.app.AppOpsManager.MODE_IGNORED
                       else android.app.AppOpsManager.MODE_DEFAULT
            appOps.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
                .invoke(appOps, op, uid, packageName, mode)
            Log.i(TAG, "mute: set muted=$muted uid=$uid pkg=$packageName")
        } catch (e: Throwable) {
            Log.w(TAG, "mute failed", e)
        }
    }

    private fun installDisplayHook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val displayClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = displayClass.declaredMethods.firstOrNull {
                it.name == "computeScreenConfiguration" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Configuration::class.java
            }
            if (method == null) {
                Log.w(TAG, "computeScreenConfiguration not found")
                return
            }
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    try {
                        val display = chain.thisObject ?: return result
                        val config = chain.getArg(0) as? Configuration ?: return result
                        val info = callNoArg(display, "getDisplayInfo") ?: return result
                        val name = getField(info, "name") as? String ?: return result
                        if (!name.startsWith(DisplayEngine.PREFIX)) return result

                        val wmService = getField(display, "mWmService") ?: return result
                        val root = getField(wmService, "mRoot") ?: return result
                        val physical = callNoArg(root, "getDefaultDisplay") ?: return result
                        val ref = callNoArg(physical, "getConfiguration") as? Configuration
                            ?: return result

                        config.colorMode = ref.colorMode
                        config.touchscreen = ref.touchscreen
                        config.keyboard = ref.keyboard
                        config.keyboardHidden = ref.keyboardHidden
                        config.hardKeyboardHidden = ref.hardKeyboardHidden
                        config.navigation = ref.navigation
                        config.navigationHidden = ref.navigationHidden
                        Log.i(TAG, "$name matched physical capabilities")
                    } catch (e: Throwable) {
                        Log.w(TAG, "display config hook failed open", e)
                    }
                    return result
                }
            })
            Log.i(TAG, "ServerHook installed")
        } catch (e: Throwable) {
            Log.w(TAG, "ServerHook install failed", e)
        }
    }

    private fun getField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun callNoArg(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(target)
            }
            cls = cls.superclass
        }
        return null
    }
}
