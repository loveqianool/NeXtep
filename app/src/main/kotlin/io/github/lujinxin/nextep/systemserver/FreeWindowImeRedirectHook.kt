package io.github.lujinxin.nextep.systemserver

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.safety.FeatureGate
import io.github.lujinxin.nextep.xposed.HookGuard

/**
 * Redirects IME requests from NeXtep-slot-* VirtualDisplays to the main
 * display (display 0).
 *
 * Apps running on our VirtualDisplay can focus text fields and request the
 * IME, but InputMethodManagerService won't create a session for clients on
 * a VirtualDisplay (no curSession in dumpsys). The IME communicates with the
 * app via Binder InputConnection, which works cross-display. So when a slot
 * client asks for the keyboard, we show it on the main display instead —
 * the user taps a text field in the free window, the keyboard pops up on the
 * main screen, and typed text goes to the app in the window.
 */
internal object FreeWindowImeRedirectHook {
    private const val SLOT_PREFIX = "NeXtep-slot-"

    fun install(module: XposedModule, classLoader: ClassLoader): Boolean =
        HookGuard.run("freewindow_ime_redirect") {
            check(FeatureGate.VIRTUAL_DISPLAY_SLOTS.defaultEnabled)
            val immsClass = runCatching {
                classLoader.loadClass("com.android.server.inputmethod.InputMethodManagerService")
            }.getOrElse { e ->
                NeXtepLog.warn("freewindow_ime", "IMMS class not found", e)
                // Try alternative package (some ROMs relocate it).
                classLoader.loadClass("com.android.server.InputMethodManagerService")
            }
            // showSoftInput has several overloads across versions; hook them all.
            val methods = immsClass.declaredMethods.filter { it.name == "showSoftInput" }
            if (methods.isEmpty()) {
                NeXtepLog.warn(
                    "freewindow_ime",
                    "showSoftInput not found; available: " +
                        immsClass.declaredMethods.map { it.name }.distinct().take(20).joinToString(","),
                )
            }
            check(methods.isNotEmpty()) { "showSoftInput not found" }
            methods.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return try {
                            if (isSlotClient(chain)) {
                                NeXtepLog.info("freewindow_ime", "Redirecting IME request from slot to display 0")
                                redirectToMainDisplay(chain)
                            } else {
                                chain.proceed()
                            }
                        } catch (e: Throwable) {
                            NeXtepLog.warn("freewindow_ime", "Redirect failed, falling through", e)
                            chain.proceed()
                        }
                    }
                })
            }
            NeXtepLog.info("freewindow_ime", "Installed IME redirect for ${methods.size} showSoftInput overload(s)")
        }

    /**
     * Returns true if the IME client in this call lives on a NeXtep slot display.
     * The first arg of showSoftInput is typically the IInputMethodClient; we
     * resolve its display via the service's client map.
     */
    private fun isSlotClient(chain: XposedInterface.Chain): Boolean {
        val imms = chain.thisObject ?: return false
        val client = chain.getArg(0) ?: return false
        // mClients maps IInputMethodClient -> ClientState; ClientState has mSelfReportedDisplayId
        // and the display ID can be resolved to a Display via DisplayManager.
        val clients = getField(imms, "mClients") as? Map<*, *> ?: return false
        val state = clients[client] ?: return false
        val displayId = getField(state, "mSelfReportedDisplayId") as? Int
            ?: getField(state, "selfReportedDisplayId") as? Int
            ?: return false
        if (displayId == 0) return false
        // Resolve display name via the service's context.
        val context = getField(imms, "mContext") as? android.content.Context ?: return false
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?: return false
        val display = dm.getDisplay(displayId) ?: return false
        return display.name?.startsWith(SLOT_PREFIX) == true
    }

    /**
     * Re-invokes showSoftInput with the display overridden to 0.
     * We do this by temporarily patching the ClientState's reported display ID,
     * proceeding, then restoring. This is surgical: only the IME path sees display 0.
     */
    private fun redirectToMainDisplay(chain: XposedInterface.Chain): Any? {
        val imms = chain.thisObject ?: return chain.proceed()
        val client = chain.getArg(0) ?: return chain.proceed()
        val clients = getField(imms, "mClients") as? Map<*, *> ?: return chain.proceed()
        val state = clients[client] ?: return chain.proceed()

        // Find the display ID field (name varies by version).
        val field = findField(state.javaClass, "mSelfReportedDisplayId")
            ?: findField(state.javaClass, "selfReportedDisplayId")
            ?: return chain.proceed()
        field.isAccessible = true
        val original = field.getInt(state)
        return try {
            field.setInt(state, 0)
            chain.proceed()
        } finally {
            field.setInt(state, original)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.apply { isAccessible = true }?.get(target)

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? =
        generateSequence(cls) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
}
