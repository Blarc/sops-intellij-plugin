package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsFileBasedOnContent
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class SopsEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val TYPE_ID = "SopsEditorProvider"
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid) return false
        PsiManager.getInstance(project).findFile(file) ?: return false
        return isSopsFileBasedOnContent(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return SopsEditor.create(file, project)
    }

    override fun getEditorTypeId(): String {
        return TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.HIDE_OTHER_EDITORS
    }

    class SopsEditor private constructor(
        decryptedEditor: TextEditor,
        encryptedEditor: TextEditor,
    ) : TextEditorWithPreview(decryptedEditor, encryptedEditor) {

        val originalEncryptedText = (previewEditor as TextEditor).editor.document.text
        var originalDecryptedText: String? = null

        init {
            (editor as? Disposable)?.let { Disposer.register(this, it) }
            (previewEditor as? Disposable)?.let { Disposer.register(this, it) }
            decrypt()
        }

        fun decrypt() {
            // TODO @Blarc: Is there any other way to get project?
            editor.project?.let {
                it.service<SopsService>().sopsDecrypt(file) {
                    withContext(Dispatchers.EDT) {
                        WriteAction.run<Throwable> {
                            editor.document.setText(it)
                            if (originalDecryptedText == null) {
                                originalDecryptedText = it
                            }
                        }
                    }
                }
                EditorNotifications.getInstance(it).updateAllNotifications()
            }
        }

        override fun dispose() {
            EditorFactory.getInstance().releaseEditor(editor)
            EditorFactory.getInstance().releaseEditor((previewEditor as TextEditor).editor)
        }

        override fun getFile(): VirtualFile {
            return (previewEditor as TextEditor).file
        }

        fun getDecryptedText(): String {
            return editor.document.text
        }

        companion object {
            fun create(file: VirtualFile, project: Project): SopsEditor {
                val textEditorProvider = TextEditorProvider.getInstance()
                val editorFactory = EditorFactory.getInstance()

                val encryptedViewer = editorFactory.createEditor(
                    FileDocumentManager.getInstance().getDocument(file)!!,
                    project,
                    file.fileType,
                    true
                )

                // Removes vertical line
                encryptedViewer.settings.isRightMarginShown = false
                val decryptedViewer = editorFactory.createEditor(
                    editorFactory.createDocument(""),
                    project,
                    file.fileType,
                    false
                )

                val decryptedEditor = textEditorProvider.getTextEditor(decryptedViewer)
                val encryptedPreview = textEditorProvider.getTextEditor(encryptedViewer)
                return SopsEditor(decryptedEditor, encryptedPreview)
            }
        }
    }
}
