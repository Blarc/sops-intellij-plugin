package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.equalsIgnoreIndent
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presents a decrypted editor backed by an encrypted SOPS file.
 *
 * [contentState] owns the rollback, disk-synchronized, and conflict versions. This class only
 * coordinates that state with IntelliJ documents, SOPS operations, and the VCS gutter tracker.
 */
class SopsEditor private constructor(
    decryptedEditor: TextEditor,
    encryptedEditor: TextEditor,
) : TextEditorWithPreview(decryptedEditor, encryptedEditor) {

    private val encryptedTextEditor: TextEditor
        get() = previewEditor as TextEditor
    private val encryptedDocument: Document
        get() = encryptedTextEditor.editor.document

    private val contentState = SopsEditorContentState(encryptedDocument.text)
    private var isDecryptedTextLoaded = false
    // Set to true when writing to file and back to false once done in showDecryptedText
    private var isWritingDecryptedText = false
    // Keeps track of the base revision and provides line status tracker based on it
    // Base revision may not be the same as rollback content, since rollback content can change
    private val revisionTracker = SopsEditorRevisionTracker(
        project = requireNotNull(editor.project),
        encryptedFile = encryptedTextEditor.file,
        decryptedDocument = editor.document,
        fallbackEncryptedText = contentState::encryptedRollbackText,
        isDecryptedTextLoaded = { isDecryptedTextLoaded },
        onRevisionLoaded = { revision ->
            val rollbackSnapshot = contentState.setRollbackContent(
                revision.encryptedText,
                revision.decryptedText,
            )
            val syncedSnapshot = contentState.syncedContent
            if (isDecryptedTextLoaded && syncedSnapshot != null) {
                val shouldAdoptRollbackCiphertext = shouldAdoptRollbackCiphertext(
                    syncedSnapshot.encryptedText,
                    syncedSnapshot.decryptedText,
                    rollbackSnapshot,
                )
                if (shouldAdoptRollbackCiphertext && contentState.syncedContent == syncedSnapshot) {
                    contentState.adoptRollbackCiphertext(rollbackSnapshot, syncedSnapshot.encryptedText)
                }
            }
        },
    )

    init {
        registerEditorsForDisposal()
        addChangeListener()
        addExternalChangeListener()
        revisionTracker.load(isInitialLoad = true)
        decrypt()
    }

    private fun registerEditorsForDisposal() {
        (editor as? Disposable)?.let { Disposer.register(this, it) }
        (previewEditor as? Disposable)?.let { Disposer.register(this, it) }
    }

    private fun addChangeListener() {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (!isWritingDecryptedText && AppSettings.instance.sopsEncryptOnChange) {
                    encryptIfChanged()
                }
            }
        })
    }

    private fun addExternalChangeListener() {
        editor.project?.messageBus?.connect(this)?.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun fileContentReloaded(reloadedFile: VirtualFile, document: Document) {
                    if (reloadedFile == file && document === encryptedDocument) {
                        decrypt()
                    }
                }
            }
        )
    }

    /** Decrypts the current encrypted document and either loads it or records a conflict. */
    fun decrypt() {
        val project = editor.project ?: return
        val newEncryptedText = encryptedDocument.text
        val request = contentState.beginDecryption(newEncryptedText)

        // Clear existing conflict if encrypted text changed
        val existingConflict = contentState.externalConflict
        if (existingConflict?.encryptedText != newEncryptedText) {
            contentState.clearConflict()
            updateNotifications(project)
        }

        // Do not allow stale plaintext to be saved while this exact encrypted version is loading.
        isDecryptedTextLoaded = false

        project.service<SopsService>().decrypt(file, false, { newDecryptedText ->
            withContext(Dispatchers.EDT) {
                if (!contentState.isCurrent(request, encryptedDocument.text)) {
                    return@withContext
                }

                val rollbackSnapshot = contentState.rollbackContent
                val shouldAdoptRollbackCiphertext = shouldAdoptRollbackCiphertext(
                    newEncryptedText,
                    newDecryptedText,
                    rollbackSnapshot,
                )

                when (contentState.applyDecryptionResult(
                    request = request,
                    currentEncryptedText = encryptedDocument.text,
                    localDecryptedText = editor.document.text,
                    externalDecryptedText = newDecryptedText,
                    rollbackSnapshot = rollbackSnapshot,
                    shouldAdoptRollbackCiphertext = shouldAdoptRollbackCiphertext,
                )) {
                    null -> return@withContext
                    ExternalChangeDecision.CONFLICT -> {
                        updateNotifications(project)
                        return@withContext
                    }
                    ExternalChangeDecision.ACCEPT_EXTERNAL -> {
                        showDecryptedText(newDecryptedText)
                        isDecryptedTextLoaded = true
                        revisionTracker.updateLineStatusTracker()
                    }
                }
            }
        })
    }

    private fun showDecryptedText(decryptedText: String) {
        if (editor.document.text != decryptedText) {
            isWritingDecryptedText = true
            try {
                WriteAction.run<Throwable> {
                    editor.document.setText(decryptedText)
                }
            } finally {
                isWritingDecryptedText = false
            }
        }

        editor.project?.let(::updateNotifications)
    }

    fun hasExternalChangeConflict(): Boolean = contentState.externalConflict != null

    /** Applies the local editor text to the latest external encrypted version. */
    fun keepLocalChanges() {
        currentConflictOrReload() ?: return
        contentState.acceptConflict()
        isDecryptedTextLoaded = true
        editor.project?.let(::updateNotifications)
        encryptIfChanged(forceEncrypt = true)
    }

    /** Replaces the local editor text with the conflicting external version. */
    fun loadExternalChanges() {
        currentConflictOrReload() ?: return
        val externalContent = contentState.acceptConflict() ?: return
        showDecryptedText(externalContent.decryptedText)
        isDecryptedTextLoaded = true
    }

    private fun currentConflictOrReload(): SopsContent? {
        contentState.currentConflict(encryptedDocument.text)?.let { return it }
        if (contentState.externalConflict == null) return null

        contentState.clearConflict()
        editor.project?.let(::updateNotifications)
        decrypt()
        return null
    }

    /** Encrypts the editor text unless it is already represented by rollback or synchronized content. */
    fun encryptIfChanged(forceEncrypt: Boolean = false) {
        val project = editor.project ?: return
        val rollbackContent = contentState.rollbackContent ?: return
        val syncedContent = contentState.syncedContent ?: return
        if (!isDecryptedTextLoaded) return

        project.service<SopsService>().editEncrypt(
            file = file,
            newDecryptedText = editor.document.text,
            originalDecryptedText = rollbackContent.decryptedText,
            originalEncryptedText = rollbackContent.encryptedText,
            syncedEncryptedText = syncedContent.encryptedText,
            syncedDecryptedText = syncedContent.decryptedText,
            forceEncrypt = forceEncrypt,
        )
    }

    override fun selectNotify() {
        super.selectNotify()
        // Commits and branch changes can leave the gutter and rollback baseline outdated.
        revisionTracker.load(isInitialLoad = false)
    }

    private suspend fun shouldAdoptRollbackCiphertext(
        newEncryptedText: String,
        newDecryptedText: String,
        rollbackSnapshot: SopsContent?,
    ): Boolean {
        if (rollbackSnapshot == null || newEncryptedText == rollbackSnapshot.encryptedText) return false

        val project = editor.project ?: return false
        return newDecryptedText.equalsIgnoreIndent(rollbackSnapshot.decryptedText, file.fileType, project)
    }

    private fun updateNotifications(project: Project) {
        EditorNotifications.getInstance(project).updateAllNotifications()
    }

    override fun getFile(): VirtualFile = encryptedTextEditor.file

    override fun dispose() {
        revisionTracker.release()
        EditorFactory.getInstance().releaseEditor(editor)
        EditorFactory.getInstance().releaseEditor(encryptedTextEditor.editor)
    }

    companion object {
        fun create(decryptedFile: VirtualFile, encryptedFile: VirtualFile, project: Project): SopsEditor {
            val editorFactory = EditorFactory.getInstance()
            val textEditorProvider = TextEditorProvider.getInstance()

            val encryptedViewer = editorFactory.createEditor(
                FileDocumentManager.getInstance().getDocument(encryptedFile)!!,
                project,
                encryptedFile.fileType,
                false,
            ).also { it.settings.isRightMarginShown = false }

            val decryptedViewer = editorFactory.createEditor(
                FileDocumentManager.getInstance().getDocument(decryptedFile)!!,
                project,
                encryptedFile.fileType,
                false,
            )

            return SopsEditor(
                textEditorProvider.getTextEditor(decryptedViewer),
                textEditorProvider.getTextEditor(encryptedViewer),
            )
        }
    }
}
