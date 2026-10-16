package io.github.lujinxin.nextep.systemserver

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.xposed.HookGuard

/**
 * Debug hook: logs when InputMethodManagerService.showSoftInput is called.
 * Operation-time logging (not install-time) to verify the method exists and is hit.
 */
internal object FreeWindowImeRedirectHook {
    fun install(module: XposedModule, classLoader: ClassLoader): Boolean =
        HookGuard.run("freewindow_ime_redirect") {
            check(FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled)
            val candidates = listOf(
                "com.android.server.inputmethod.InputMethodManagerService",
                "com.android.server.InputMethodManagerService",
            )
            var immsClass: Class<*>? = null
            for (name in candidates) {
                immsClass = runCatching { classLoader.loadClass(name) }.getOrNull()
                if (immsClass != null) break
            }
            checkNotNull(immsClass) { "IMMS class not found in $candidates" }
            val methods = immsClass.declaredMethods.filter { it.name == "showSoftInput" }
            check(methods.isNotEmpty()) {
                "showSoftInput not found; have: " +
                    immsClass.declaredMethods.map { it.name }.distinct().sorted().take(30).joinToString(",")
            }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // Operation-time log: proves the hook is installed and the method is hit.
                        NeXtepLog.info("freewindow_ime", "showSoftInput called, args=${chain.getArgCount()}")
                        return chain.proceed()
                    }
                })
            }
            // Operation-time marker: log via a hook that fires later (DisplayContent).
            // Install-time logs don't survive early boot, so we log here too for diagnostics.
            NeXtepLog.info("freewindow_ime", "Hook installed for ${methods.size} overload(s)")
        }
}
