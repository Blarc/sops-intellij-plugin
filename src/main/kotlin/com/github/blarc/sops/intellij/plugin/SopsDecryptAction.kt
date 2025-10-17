package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SopsDecryptAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        project.service<SopsService>().decrypt(file, true, {
            withContext(Dispatchers.EDT) {
                file.refresh(false, false)
                val fileEditorManager = FileEditorManager.getInstance(project)
                val isOpen = fileEditorManager.isFileOpen(file)

                if (isOpen) {
                    // Close and reopen the editor to use the correct editor provider
                    fileEditorManager.closeFile(file)
                    fileEditorManager.openFile(file, true)
                }
            }
        }, { message -> println("Failed: $message") })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.icon = Icons.KEY_ICON.getThemeBasedIcon()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}