package com.github.blarc.sops.intellij.plugin.listener

import com.github.blarc.sops.intellij.plugin.providers.SopsEditorProvider
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications

class SopsSaveActionListener(
    private val project: Project,
) : FileDocumentManagerListener {

    override fun beforeAllDocumentsSaving() {
        val editors = FileEditorManager.getInstance(project).allEditors

        editors.filter { it.isValid }.forEach {
            if (it is SopsEditorProvider.SopsEditor) {
                project.service<SopsService>().editEncrypt(it.file, it.getDecryptedText(), it.originalDecryptedText, it.originalEncryptedText)
            }
        }

        EditorNotifications.getInstance(project).updateAllNotifications()
        super.beforeAllDocumentsSaving()
    }
}
