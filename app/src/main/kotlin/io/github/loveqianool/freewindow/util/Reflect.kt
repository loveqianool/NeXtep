package io.github.loveqianool.freewindow.util

/**
 * Reflection helpers shared by the injected processes (system_server,
 * SystemUI, launcher). Previously each file had its own copy of
 * getField/callNoArg with identical logic; they are merged here so a
 * fix lands once. All functions return null on any failure — callers
 * already treat a missing field/method as "feature unavailable" and
 * fall back to the original behaviour, never crash the host process.
 */
object Reflect {
    /** Field value, searching superclasses, or null. */
    fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField(name)
                    field.isAccessible = true
                    return@runCatching field.get(target)
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            null
        }.getOrNull()
    }

    /** No-arg method result, searching superclasses (public then declared), or null. */
    fun callNoArg(target: Any, name: String): Any? = runCatching {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            val method = clazz.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(target)
            }
            clazz = clazz.superclass
        }
        null
    }.getOrNull()
}
