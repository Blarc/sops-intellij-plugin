package com.github.blarc.sops.intellij.plugin.listeners

import com.github.blarc.sops.intellij.plugin.SopsBundle
import com.github.blarc.sops.intellij.plugin.notifications.Notification
import com.github.blarc.sops.intellij.plugin.notifications.sendNotification
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ApplicationStartupListener : ProjectActivity {

    private var firstTime = true
    override suspend fun execute(project: Project) {
        showVersionNotification(project)
    }
    private fun showVersionNotification(project: Project) {
        val settings = AppSettings.instance
        val version = SopsBundle.plugin()?.version

        if (version == settings.lastVersion) {
            return
        }

        settings.lastVersion = version
        if (firstTime && version != null) {
            sendNotification(Notification.welcome(version), project)
        }
        firstTime = false
    }
}