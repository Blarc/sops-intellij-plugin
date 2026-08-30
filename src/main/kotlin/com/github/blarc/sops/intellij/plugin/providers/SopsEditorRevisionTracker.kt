package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.services.SopsVcsService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ex.SimpleLocalLineStatusTracker
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads the decrypted VCS base revision and applies it to the editor's gutter tracker. */
internal class SopsEditorRevisionTracker(
    private val project: Project,
    private val encryptedFile: VirtualFile,
    private val decryptedDocument: Document,
    private val fallbackEncryptedText: () -> String,
    private val isDecryptedTextLoaded: () -> Boolean,
    private val onRevisionLoaded: suspend (SopsContent) -> Unit,
) {
    private var lineStatusTracker: SimpleLocalLineStatusTracker? = null
    private var isLoading = false
    private var encryptedRevision: String? = null
    private var decryptedRevision: String? = null

    fun load(isInitialLoad: Boolean) {
        if (isLoading) return
        isLoading = true

        project.service<SopsVcsService>().getLastCommitContent(encryptedFile) { content ->
            // Decryption is not needed if the content was already decrypted
            if (!isInitialLoad && (content == null || content == encryptedRevision)) {
                isLoading = false
                return@getLastCommitContent
            }

            val encryptedText = content ?: fallbackEncryptedText()
            encryptedRevision = encryptedText
            decrypt(encryptedText)
        }
    }

    private fun decrypt(encryptedText: String) {
        // Failure is intentionally silent: an outdated or unencrypted VCS revision must not replace
        // an error from decrypting the actual editor file.
        project.service<SopsService>().decrypt(
            text = encryptedText,
            extension = encryptedFile.extension,
            workingDirectory = encryptedFile.parent?.path,
            onSuccess = { decryptedText ->
                withContext(Dispatchers.EDT) {
                    decryptedRevision = decryptedText
                    onRevisionLoaded(SopsContent(encryptedText, decryptedText))
                    isLoading = false
                    updateLineStatusTracker()
                }
            },
            onError = {
                isLoading = false
            }
        )
    }

    suspend fun updateLineStatusTracker() {
        val baseText = decryptedRevision ?: return
        if (!isDecryptedTextLoaded()) return

        withContext(Dispatchers.EDT) {
            val decryptedFile = FileDocumentManager.getInstance().getFile(decryptedDocument) ?: return@withContext
            val tracker = lineStatusTracker
                ?: SimpleLocalLineStatusTracker.createTracker(project, decryptedDocument, decryptedFile)
                    .also { lineStatusTracker = it }
            tracker.setBaseRevision(baseText)
        }
    }

    fun release() {
        lineStatusTracker?.release()
    }
}
