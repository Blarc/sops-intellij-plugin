package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsFile
import com.github.blarc.sops.intellij.plugin.equalsIgnoreIndent
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.services.SopsVcsService
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
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

        private val contentState = SopsEditorContentState(
            (previewEditor as TextEditor).editor.document.text
        )

        /**
         * Shows the difference between the decrypted content and the decrypted content of the last commit
         * in the gutter of the decrypted editor. The decrypted editor is backed by a [LightVirtualFile],
         * which is not under version control, so the platform does not track it on its own.
         */
        private var lineStatusTracker: SimpleLocalLineStatusTracker? = null
        private var isDecryptedTextLoaded = false
        private var isUpdatingDecryptedText = false
        private var isBaseRevisionLoading = false
        private var baseRevisionEncryptedText: String? = null
        private var baseRevisionDecryptedText: String? = null
        private var syncedEncryptedText = (previewEditor as TextEditor).editor.document.text
        private var syncedDecryptedText: String? = null
        private var externalChangeConflict: ExternalChangeConflict? = null

        private data class ExternalChangeConflict(
            val encryptedText: String,
            val decryptedText: String,
        )

        init {
            (editor as? Disposable)?.let { Disposer.register(this, it) }
            (previewEditor as? Disposable)?.let { Disposer.register(this, it) }

            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    if (!isUpdatingDecryptedText && AppSettings.instance.sopsEncryptOnChange) {
                        encryptIfChanged()
                    }
                }
            })

            editor.project?.messageBus?.connect(this)?.subscribe(
                FileDocumentManagerListener.TOPIC,
                object : FileDocumentManagerListener {
                    override fun fileContentReloaded(reloadedFile: VirtualFile, document: Document) {
                        if (reloadedFile == file && document === (previewEditor as TextEditor).editor.document) {
                            decrypt()
                        }
                    }
                }
            )

            loadBaseRevision(isInitialLoad = true)

            // This will show an error if the current content is not valid
            decrypt()
        }

        fun decrypt() {
            editor.project?.let { project ->
                val encryptedDocument = (previewEditor as TextEditor).editor.document
                val encryptedText = encryptedDocument.text
                val request = contentState.beginDecryption(encryptedText)

                if (externalChangeConflict?.encryptedText?.let { it != encryptedText } == true) {
                    externalChangeConflict = null
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }

                // Until this exact version of the encrypted file has been decrypted, saving the old
                // decrypted document must not be allowed to overwrite it.
                isDecryptedTextLoaded = false

                project.service<SopsService>().decrypt(file, false, { decryptedContent ->
                    withContext(Dispatchers.EDT) {
                        // A second external update may have arrived while SOPS was running.
                        if (!contentState.completeDecryption(request, encryptedDocument.text)) {
                            return@withContext
                        }

                        adoptEncryptedBaselineIfPlaintextMatches(encryptedText, decryptedContent)
                        val localDecryptedText = editor.document.text
                        val previousSyncedText = syncedDecryptedText
                        val localMatchesSynced = previousSyncedText == null ||
                            localDecryptedText == previousSyncedText
                        val externalMatchesLocal = decryptedContent == localDecryptedText

                        // The comparisons above can suspend while another reload arrives.
                        if (!contentState.completeDecryption(request, encryptedDocument.text)) {
                            return@withContext
                        }

                        if (decideExternalChange(
                                hasSyncedContent = previousSyncedText != null,
                                localMatchesSynced = localMatchesSynced,
                                externalMatchesLocal = externalMatchesLocal,
                            ) == ExternalChangeDecision.CONFLICT
                        ) {
                            externalChangeConflict = ExternalChangeConflict(encryptedText, decryptedContent)
                            EditorNotifications.getInstance(project).updateAllNotifications()
                            return@withContext
                        }

                        acceptExternalContent(encryptedText, decryptedContent)
                        isDecryptedTextLoaded = true
                        updateLineStatusTracker()
                    }
                })
            }
        }

        private fun acceptExternalContent(encryptedText: String, decryptedText: String) {
            syncedEncryptedText = encryptedText
            syncedDecryptedText = decryptedText
            externalChangeConflict = null

            if (editor.document.text != decryptedText) {
                isUpdatingDecryptedText = true
                try {
                    WriteAction.run<Throwable> {
                        editor.document.setText(decryptedText)
                    }
                } finally {
                    isUpdatingDecryptedText = false
                }
            }

            editor.project?.let { EditorNotifications.getInstance(it).updateAllNotifications() }
        }

        override fun selectNotify() {
            super.selectNotify()
            // The last commit changes when the file is committed, checked out or updated,
            // which leaves the gutter of the decrypted editor showing an outdated difference.
            loadBaseRevision(isInitialLoad = false)
        }

        /**
         * Decrypts the content of the last commit for the base revision of the gutter markers.
         *
         * The gutter base and rollback state are kept separate. The rollback plaintext remains the last
         * commit, while its encrypted form can follow metadata-only changes such as `sops updatekeys`.
         */
        private fun loadBaseRevision(isInitialLoad: Boolean) {
            val project = editor.project ?: return
            if (isBaseRevisionLoading) {
                return
            }
            isBaseRevisionLoading = true

            project.service<SopsVcsService>().getLastCommitContent(file) { content ->
                if (!isInitialLoad && (content == null || content == baseRevisionEncryptedText)) {
                    isBaseRevisionLoading = false
                    return@getLastCommitContent
                }

                val encryptedBaseRevision = content ?: contentState.originalEncryptedText
                baseRevisionEncryptedText = encryptedBaseRevision
                // Content might not be encrypted, so decryption might fail
                // But since this content might be outdated, we do not want to show an error
                project.service<SopsService>().decrypt(
                    text = encryptedBaseRevision,
                    // SOPS picks the store from the file extension, so decrypting the content of the last
                    // commit as anything else returns it in another format, which the gutter would then
                    // show as a difference. A binary store file, for example, comes back wrapped in YAML.
                    extension = file.extension,
                    workingDirectory = file.parent?.path,
                    onSuccess = { decryptedText ->
                        withContext(Dispatchers.EDT) {
                            baseRevisionDecryptedText = decryptedText
                            contentState.setRollbackBaseline(encryptedBaseRevision, decryptedText)

                            // A metadata-only change may already be on disk when the editor opens, or
                            // may arrive while the VCS revision is being decrypted. Preserve it too.
                            if (isDecryptedTextLoaded) {
                                adoptEncryptedBaselineIfPlaintextMatches(
                                    (previewEditor as TextEditor).editor.document.text,
                                    editor.document.text,
                                )
                            }

                            isBaseRevisionLoading = false
                            updateLineStatusTracker()
                        }
                    },
                    onError = {
                        isBaseRevisionLoading = false
                    }
                )
            }
        }

        private suspend fun adoptEncryptedBaselineIfPlaintextMatches(
            encryptedText: String,
            decryptedText: String,
        ) {
            val rollbackDecryptedText = contentState.originalDecryptedText ?: return
            if (decryptedText.equalsIgnoreIndent(rollbackDecryptedText, file.fileType, editor.project ?: return)) {
                contentState.updateEncryptedBaseline(encryptedText)
            }
        }

        private suspend fun updateLineStatusTracker() {
            val project = editor.project ?: return
            val baseDecryptedText = baseRevisionDecryptedText ?: return
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

        fun hasExternalChangeConflict(): Boolean = externalChangeConflict != null

        fun keepLocalChanges() {
            val conflict = currentExternalChangeConflict() ?: return
            syncedEncryptedText = conflict.encryptedText
            syncedDecryptedText = conflict.decryptedText
            externalChangeConflict = null
            isDecryptedTextLoaded = true
            editor.project?.let { EditorNotifications.getInstance(it).updateAllNotifications() }
            encryptIfChanged(forceEncrypt = true)
        }

        fun loadExternalChanges() {
            val conflict = currentExternalChangeConflict() ?: return
            acceptExternalContent(conflict.encryptedText, conflict.decryptedText)
            isDecryptedTextLoaded = true
        }

        private fun currentExternalChangeConflict(): ExternalChangeConflict? {
            val conflict = externalChangeConflict ?: return null
            if ((previewEditor as TextEditor).editor.document.text == conflict.encryptedText) {
                return conflict
            }

            externalChangeConflict = null
            editor.project?.let { EditorNotifications.getInstance(it).updateAllNotifications() }
            decrypt()
            return null
        }

        fun encryptIfChanged(forceEncrypt: Boolean = false) {
            val project = editor.project ?: return
            val decryptedBaseline = contentState.originalDecryptedText ?: return
            if (!isDecryptedTextLoaded) return

            project.service<SopsService>().editEncrypt(
                file,
                editor.document.text,
                decryptedBaseline,
                contentState.originalEncryptedText,
                syncedEncryptedText,
                syncedDecryptedText,
                forceEncrypt,
            )
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
