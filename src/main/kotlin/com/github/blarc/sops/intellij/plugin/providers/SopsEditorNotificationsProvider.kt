package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.function.Function

class SopsEditorNotificationsProvider(private val cs: CoroutineScope) : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ) = Function { _: FileEditor ->

        val error = project.service<SopsService>().error
        if (error.isNotBlank()) {
            val panel = EditorNotificationPanel(HintUtil.ERROR_COLOR_KEY)
            panel.text = error
            panel.createActionLabel("Try again") {
                val editor = FileEditorManager.getInstance(project).getSelectedEditor(file)
                if (editor is SopsEditorProvider.SopsEditor) {
                    editor.decrypt()
                }
            }
            return@Function panel
        }
        return@Function null
    }
}
