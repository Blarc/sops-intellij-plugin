package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsFile
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.services.SopsVcsService
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ex.SimpleLocalLineStatusTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SopsEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val TYPE_ID = "SopsEditorProvider"
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid) return false
        PsiManager.getInstance(project).findFile(file) ?: return false
        return isSopsFile(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val decryptedFile = LightVirtualFile(file.name, file.fileType, "")
        return SopsEditor.create(decryptedFile, file, project)
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

        var originalEncryptedText = (previewEditor as TextEditor).editor.document.text
        var originalDecryptedText: String? = null

        /**
         * Shows the difference between the decrypted content and the decrypted content of the last commit
         * in the gutter of the decrypted editor. The decrypted editor is backed by a [LightVirtualFile],
         * which is not under version control, so the platform does not track it on its own.
         */
        private var lineStatusTracker: SimpleLocalLineStatusTracker? = null
        private var isDecryptedTextLoaded = false
        private var isBaseRevisionLoading = false

        init {
            (editor as? Disposable)?.let { Disposer.register(this, it) }
            (previewEditor as? Disposable)?.let { Disposer.register(this, it) }

            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    if (AppSettings.instance.sopsEncryptOnChange) {
                        editor.project?.service<SopsService>()
                            ?.editEncrypt(file, getDecryptedText(), originalDecryptedText, originalEncryptedText)
                    }
                }
            })

            loadBaseRevision(isInitialLoad = true)

            // This will show an error if the current content is not valid
            decrypt()
        }

        fun decrypt() {
            editor.project?.let { project ->
                project.service<SopsService>().decrypt(file, false, { decryptedContent ->
                    withContext(Dispatchers.EDT) {
                        WriteAction.run<Throwable> {
                            editor.document.setText(decryptedContent)
                        }
                        isDecryptedTextLoaded = true
                        updateLineStatusTracker()
                    }
                })
            }
        }

        override fun selectNotify() {
            super.selectNotify()
            // The last commit changes when the file is committed, checked out or updated,
            // which leaves the gutter of the decrypted editor showing an outdated difference.
            loadBaseRevision(isInitialLoad = false)
        }

        /**
         * Decrypts the content of the last commit, which is both the base revision of the gutter markers
         * and how [SopsService.editEncrypt] recognizes that the content has not changed.
         */
        private fun loadBaseRevision(isInitialLoad: Boolean) {
            val project = editor.project ?: return
            if (isBaseRevisionLoading) {
                return
            }
            isBaseRevisionLoading = true

            project.service<SopsVcsService>().getLastCommitContent(file) { content ->
                if (!isInitialLoad && (content == null || content == originalEncryptedText)) {
                    isBaseRevisionLoading = false
                    return@getLastCommitContent
                }

                if (content != null) {
                    originalEncryptedText = content
                }
                // Content might not be encrypted, so decryption might fail
                // But since this content might be outdated, we do not want to show an error
                project.service<SopsService>().decrypt(
                    text = originalEncryptedText,
                    // SOPS picks the store from the file extension, so decrypting the content of the last
                    // commit as anything else returns it in another format, which the gutter would then
                    // show as a difference. A binary store file, for example, comes back wrapped in YAML.
                    extension = file.extension,
                    workingDirectory = file.parent?.path,
                    onSuccess = { decryptedText ->
                        originalDecryptedText = decryptedText
                        isBaseRevisionLoading = false
                        updateLineStatusTracker()
                    },
                    onError = {
                        isBaseRevisionLoading = false
                    }
                )
            }
        }

        private suspend fun updateLineStatusTracker() {
            val project = editor.project ?: return
            val baseDecryptedText = originalDecryptedText ?: return
            // Setting the base revision before the editor shows the decrypted content
            // would mark the whole file as changed
            if (!isDecryptedTextLoaded) {
                return
            }

            withContext(Dispatchers.EDT) {
                val decryptedFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return@withContext
                val tracker = lineStatusTracker
                    ?: SimpleLocalLineStatusTracker.createTracker(project, editor.document, decryptedFile)
                        .also { lineStatusTracker = it }

                tracker.setBaseRevision(baseDecryptedText)
            }
        }

        override fun dispose() {
            lineStatusTracker?.release()
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
            fun create(decryptedFile: VirtualFile, encryptedFile: VirtualFile, project: Project): SopsEditor {
                val textEditorProvider = TextEditorProvider.getInstance()
                val editorFactory = EditorFactory.getInstance()

                val encryptedViewer = editorFactory.createEditor(
                    FileDocumentManager.getInstance().getDocument(encryptedFile)!!,
                    project,
                    encryptedFile.fileType,
                    false
                )
                // Removes vertical line
                encryptedViewer.settings.isRightMarginShown = false
                val decryptedViewer = editorFactory.createEditor(
                    FileDocumentManager.getInstance().getDocument(decryptedFile)!!,
                    project,
                    encryptedFile.fileType,
                    false
                )

                val decryptedEditor = textEditorProvider.getTextEditor(decryptedViewer)
                val encryptedPreview = textEditorProvider.getTextEditor(encryptedViewer)
                return SopsEditor(decryptedEditor, encryptedPreview)
            }
        }
    }
}
