package io.github.loveqianool.freewindow.server

import android.content.res.Configuration
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

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
