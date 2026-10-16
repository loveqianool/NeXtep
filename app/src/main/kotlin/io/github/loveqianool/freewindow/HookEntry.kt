package io.github.loveqianool.freewindow

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.loveqianool.freewindow.server.ServerHook
import io.github.loveqianool.freewindow.window.WindowSystemUiHook

class HookEntry : XposedModule() {
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val appInfo = param.javaClass.getDeclaredMethod("getApplicationInfo")
                .apply { isAccessible = true }.invoke(param) as? android.content.pm.ApplicationInfo
            Log.i(TAG, "system_server starting, module apk=${appInfo?.sourceDir}")
        } catch (e: Throwable) {
            Log.i(TAG, "system_server starting (apk path unavailable: ${e.message})")
        }
        Log.i(TAG, "system_server starting")
        ServerHook.install(this, param.classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName == "com.android.systemui") {
            Log.i(TAG, "systemui loaded")
            WindowSystemUiHook.install()
        }
    }

    companion object {
        const val TAG = "FreeWindow"
    }
}
