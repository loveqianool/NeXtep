package io.github.loveqianool.freewindow

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.loveqianool.freewindow.launcher.RecentsHook
import io.github.loveqianool.freewindow.server.ServerHook
import io.github.loveqianool.freewindow.window.WindowSystemUiHook

class HookEntry : XposedModule() {
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        Log.i(TAG, "system_server starting")
        ServerHook.install(this, param.classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Launcher branch first, deliberately BEFORE the isFirstPackage
        // gate below. Device evidence: on the latest build, with the
        // launcher scoped and after a reboot, operating Recents produced
        // zero FreeWindow logs at all — not even "launcher loaded", and
        // RecentsHook.install itself logs on success and on every
        // failure, so a hook failure cannot explain total silence. The
        // only silent gate before those logs is isFirstPackage: if the
        // launcher process loads launcher3 with isFirstPackage=false
        // (e.g. another package loads first in that process), the old
        // code returned before logging anything. Bypassing the gate for
        // the launcher only is safe: RecentsHook.install guards against
        // double-install, and the SystemUI branch below keeps its gate
        // unchanged (it works — SystemUI logs prove it).
        if (param.packageName == "com.android.launcher3") {
            Log.i(TAG, "launcher loaded")
            val classLoader = packageClassLoader(param)
            if (classLoader != null) {
                RecentsHook.install(this, classLoader)
            } else {
                Log.w(TAG, "launcher: no package classloader, menu hook skipped")
            }
            return
        }
        if (!param.isFirstPackage) return
        if (param.packageName == "com.android.systemui") {
            Log.i(TAG, "systemui loaded")
            WindowSystemUiHook.install()
        }
    }

    /**
     * The package classloader accessor differs across libxposed versions:
     * on the pinned api:102.0.0, `param.classLoader` does not compile for
     * PackageLoadedParam (CI: Unresolved reference 'classLoader'), while
     * SystemServerStartingParam still has it. Resolve it reflectively
     * instead of hard-coding a method name that may not exist in this
     * version: prefer getClassLoader, fall back to getDefaultClassLoader,
     * then to any no-arg method returning a ClassLoader. If none resolves,
     * the launcher hook is skipped (menu item missing) but nothing else
     * breaks — never crash the launcher process over a loader lookup.
     */
    private fun packageClassLoader(param: PackageLoadedParam): ClassLoader? {
        return try {
            val methods = param.javaClass.methods
            val preferred = methods.firstOrNull { it.name == "getClassLoader" }
                ?: methods.firstOrNull { it.name == "getDefaultClassLoader" }
                ?: methods.firstOrNull {
                    it.returnType == ClassLoader::class.java && it.parameterTypes.isEmpty()
                }
                ?: return null
            preferred.isAccessible = true
            preferred.invoke(param) as? ClassLoader
        } catch (e: Throwable) {
            Log.w(TAG, "launcher: package classloader lookup failed", e)
            null
        }
    }

    companion object {
        const val TAG = "FreeWindow"
    }
}
