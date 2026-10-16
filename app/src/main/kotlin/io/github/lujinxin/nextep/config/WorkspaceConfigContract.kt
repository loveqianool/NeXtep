package io.github.lujinxin.nextep.config

/**
 * Minimal config contract. Only the action used by
 * [io.github.lujinxin.nextep.config.AppLanguage] is kept; the workspace
 * settings provider was removed with the old workspace UI.
 */
object WorkspaceConfigContract {
    const val ACTION_CHANGED = "io.github.lujinxin.nextep.action.WORKSPACE_CONFIG_CHANGED"
}
