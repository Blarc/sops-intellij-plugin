package com.github.blarc.sops.intellij.plugin.notifications

import com.github.blarc.sops.intellij.plugin.SopsBundle
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.SopsError
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI

data class Notification(
    val title: String? = null,
    val message: String,
    val actions: Set<NotificationAction> = setOf(),
    val duration: Type = Type.PERSISTENT,
    val type: NotificationType = NotificationType.INFORMATION
) {
    enum class Type {
        PERSISTENT,
        TRANSIENT
    }

    companion object {
        private val DEFAULT_TITLE = message("notifications.title")

        fun welcome(version: String) = Notification(message = message("notifications.welcome", version), duration = Type.TRANSIENT)

        fun error(error: SopsError, actions: Set<NotificationAction> = emptySet()) = Notification(
            message = message("notifications.error", error.message),
            actions = actions,
            duration = Type.TRANSIENT,
            type = NotificationType.ERROR
        )

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

        fun openFile(project: Project, file: VirtualFile, title: String = message("actions.open-sops-config")) = NotificationAction(title) { dismiss ->
            dismiss()
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}
