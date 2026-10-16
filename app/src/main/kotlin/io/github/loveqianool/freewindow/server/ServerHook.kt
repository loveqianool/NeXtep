package io.github.loveqianool.freewindow.server

import android.content.res.Configuration
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.loveqianool.freewindow.FwLog
import io.github.loveqianool.freewindow.window.WindowMute

/**
 * system_server hooks. Currently only the display-capability matching:
 * FreeWindow-* virtual displays inherit color mode / touchscreen / keyboard /
 * navigation from the physical display so apps render correctly.
 */
object ServerHook {
    private const val TAG = "FreeWindow"
    private var installed = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val codeSource = ServerHook::class.java.protectionDomain?.codeSource?.location
            FwLog.i("ServerHook loaded from: $codeSource")
        } catch (e: Throwable) {
            FwLog.i("ServerHook codeSource unavailable: ${e.message}")
        }
        installDisplayHook(module, classLoader)
        installMuteReceiver()
    }

    /**
     * Registers the mute broadcast receiver. Runs in system_server (uid 1000),
     * which holds MANAGE_APP_OPS_MODES — systemui does not.
     */
    private fun installMuteReceiver() {
        FwLog.i("installMuteReceiver: starting")
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? android.content.Context
                ?: run {
                    FwLog.w("mute receiver: no app context")
                    return
                }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val pkg = intent.getStringExtra(WindowMute.EXTRA_PACKAGE) ?: return
                    val uid = intent.getIntExtra(WindowMute.EXTRA_UID, -1)
                    val muted = intent.getBooleanExtra(WindowMute.EXTRA_MUTED, false)
                    if (uid < 0) return
                    doSetMuted(pkg, uid, muted)
                }
            }
            app.registerReceiver(
                receiver,
                android.content.IntentFilter(WindowMute.ACTION_MUTE),
                android.content.Context.RECEIVER_EXPORTED,
            )
            FwLog.i("mute receiver registered")
        } catch (e: Throwable) {
            FwLog.w("mute receiver install failed", e)
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
            val mode = if (muted) android.app.AppOpsManager.MODE_IGNORED
                       else android.app.AppOpsManager.MODE_ALLOWED
            appOps.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
                .invoke(appOps, op, uid, packageName, mode)
            FwLog.i("mute: set muted=$muted uid=$uid pkg=$packageName")
        } catch (e: Throwable) {
            FwLog.i("mute failed: ${e.javaClass.simpleName}: ${e.message}")
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
