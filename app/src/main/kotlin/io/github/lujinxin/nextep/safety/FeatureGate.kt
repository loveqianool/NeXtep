package io.github.lujinxin.nextep.safety

enum class FeatureGate(val defaultEnabled: Boolean) {
    SYSTEM_UI(true),
    VIRTUAL_DISPLAY_SLOTS(true),
    SYSTEM_SERVER_PATCHES(true),
}
