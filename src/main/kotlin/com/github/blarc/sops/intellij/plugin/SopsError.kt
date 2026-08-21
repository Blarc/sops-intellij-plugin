package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.SopsBundle.message

/** An error that can be shown to a user after running SOPS. */
sealed class SopsError(open val message: String) {
    data object ExecutableNotSet : SopsError(message("error.executable-not-set"))

    data class ProcessNotCreated(override val message: String) : SopsError(message)

    data class CommandFailed(override val message: String) : SopsError(message)

    data object FileNotChanged : SopsError("")
}
