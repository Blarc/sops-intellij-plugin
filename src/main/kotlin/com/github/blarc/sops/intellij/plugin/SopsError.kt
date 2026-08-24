package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.notifications.NotificationAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** An error that can be shown to a user after running SOPS. */
sealed class SopsError(open val message: String) {
    data object ExecutableNotSet : SopsError(message("error.executable-not-set"))

    data class ProcessNotCreated(override val message: String) : SopsError(message)

    data class CommandFailed(override val message: String) : SopsError(message)

    data object FileNotChanged : SopsError("")

    /** Returns actions applicable to this error in the given file/project context. */
    fun getActions(
        file: VirtualFile? = null,
        project: Project? = null
    ): Set<NotificationAction> = when (this) {
        is CommandFailed -> when (message.trim()) {
            NO_MATCHING_CREATION_RULES -> {
                if (file == null || project == null) {
                    emptySet()
                } else {
                    SopsConfigResolver.findConfigFile(file, project)
                        ?.let { setOf(NotificationAction.openFile(project, it)) }
                        .orEmpty()
                }
            }

            else -> emptySet()
        }

        else -> emptySet()
    }

    private companion object {
        const val NO_MATCHING_CREATION_RULES = "error loading config: no matching creation rules found"
    }
}
