package com.github.blarc.sops.intellij.plugin.notifications

import com.github.blarc.sops.intellij.plugin.SopsBundle
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URI

data class Notification(
    val title: String? = null,
    val message: String,
    val actions: Set<NotificationAction> = setOf(),
    val type: Type = Type.PERSISTENT
) {
    enum class Type {
        PERSISTENT,
        TRANSIENT
    }

    companion object {
        private val DEFAULT_TITLE = message("notifications.title")

        fun welcome(version: String) = Notification(message = message("notifications.welcome", version), type = Type.TRANSIENT)

        fun star() = Notification(
            message = """
                Finding SOPS plugin useful? Show your support 💖 and ⭐ the repository 🙏.
            """.trimIndent(),
            actions = setOf(
                NotificationAction.openRepository() {
                    service<AppSettings>().requestSupport = false;
                },
                NotificationAction.doNotAskAgain() {
                    service<AppSettings>().requestSupport = false;
                }
            )
        )
    }
}

data class NotificationAction(val title: String, val run: (dismiss: () -> Unit) -> Unit) {
    companion object {
        fun settings(project: Project, title: String = message("settings.title")) = NotificationAction(title) { dismiss ->
            dismiss()
            SopsBundle.openPluginSettings(project)
        }

        fun openRepository(onComplete: () -> Unit) = NotificationAction(message("actions.sure-take-me-there")) { dismiss ->
            SopsBundle.openRepository()
            dismiss()
            onComplete()
        }

        fun doNotAskAgain(onComplete: () -> Unit) = NotificationAction(message("actions.do-not-ask-again")) { dismiss ->
            dismiss()
            onComplete()
        }

        fun openUrl(url: URI, title: String = message("actions.take-me-there")) = NotificationAction(title) { dismiss ->
            dismiss()
            BrowserLauncher.instance.open(url.toString());
        }
    }
}
