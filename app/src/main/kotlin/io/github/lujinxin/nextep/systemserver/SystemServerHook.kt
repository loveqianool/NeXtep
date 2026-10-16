package io.github.lujinxin.nextep.systemserver

import android.content.res.Configuration
import android.util.Log
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.xposed.HookGuard
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System-server hooks for the free-window mode. Only the VirtualDisplay
 * engine hooks are installed:
 * - slot display configuration (so apps render correctly on NeXtep-slot-* displays)
 * - slot video fullscreen exit
 * - per-window mute
 *
 * The old workspace UI hooks were removed with the workspace.
 */
object SystemServerHook {
    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val slotVideoExitInstalled = HookGuard.run("slot_video_exit") {
            check(FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled)
            val activityClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
            val method = activityClass.declaredMethods.single {
                it.name == "onDisplayChanged" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "com.android.server.wm.DisplayContent"
            }.apply { isAccessible = true }
            module.hook(method).intercept(SlotVideoFullscreenExitHooker { message ->
                module.log(Log.INFO, "NeXtep", message)
            })
        }
        val slotConfigInstalled = HookGuard.run("slot_display_config") {
            val displayClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = displayClass.declaredMethods.single {
                it.name == "computeScreenConfiguration" &&
                    it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
            }
            method.isAccessible = true
            module.hook(method).intercept(SlotDisplayConfigurationHooker())
            NeXtepLog.info("slot_display_config", "Installed package-independent slot capability matching")
        }
        HookGuard.run("freewindow_mute") { FreeWindowMuteHook.install() }
        // NB: FreeWindowImeRedirectHook.install() has its own HookGuard; do not wrap again.
        FreeWindowImeRedirectHook.install(module, classLoader)
        if (!slotConfigInstalled && !slotVideoExitInstalled) {
            // Nothing was installed; keep the guard open for a potential future retry.
            installed.set(false)
            module.log(Log.ERROR, "NeXtep", "system_server hooks failed open: no compatible target found")
        } else {
            module.log(
                Log.INFO,
                "NeXtep",
                "system_server hooks installed: " +
                    "slot config=$slotConfigInstalled slot video exit=$slotVideoExitInstalled",
            )
        }
    }
}
