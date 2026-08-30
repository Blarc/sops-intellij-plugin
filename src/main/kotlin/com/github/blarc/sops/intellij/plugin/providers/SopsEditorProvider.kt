package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsFile
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile

/** Selects [SopsEditor] for SOPS-encrypted files. */
class SopsEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || PsiManager.getInstance(project).findFile(file) == null) {
            return false
        }
        return isSopsFile(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val decryptedFile = LightVirtualFile(file.name, file.fileType, "")
        return SopsEditor.create(decryptedFile, file, project)
    }

    override fun getEditorTypeId(): String = TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

    companion object {
        const val TYPE_ID = "SopsEditorProvider"
    }
}
