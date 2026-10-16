package io.github.lujinxin.nextep.trigger

/**
 * Minimal broadcast contract. Only the extras used by
 * [io.github.lujinxin.nextep.apps.LauncherAppRepository] for multi-user
 * app launches are kept; the workspace trigger helpers were removed with
 * the old workspace UI.
 */
object TriggerBroadcastContract {
    const val EXTRA_TARGET_USER = "io.github.lujinxin.nextep.extra.TARGET_USER"
    const val EXTRA_TARGET_USER_ID = "io.github.lujinxin.nextep.extra.TARGET_USER_ID"
    const val EXTRA_BYPASS_MULTI_APP_CHOOSER =
        "io.github.lujinxin.nextep.extra.BYPASS_MULTI_APP_CHOOSER"
}
