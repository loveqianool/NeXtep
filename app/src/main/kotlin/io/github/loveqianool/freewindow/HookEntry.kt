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
        if (!param.isFirstPackage) return
        if (param.packageName == "com.android.systemui") {
            Log.i(TAG, "systemui loaded")
            WindowSystemUiHook.install()
        }
        if (param.packageName == "com.android.launcher3") {
            Log.i(TAG, "launcher loaded")
            RecentsHook.install(this, param.classLoader)
        }
    }

    companion object {
        const val TAG = "FreeWindow"
    }
}
