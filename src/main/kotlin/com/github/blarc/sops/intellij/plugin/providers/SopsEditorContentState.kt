package com.github.blarc.sops.intellij.plugin.providers

internal data class SopsContent(
    val encryptedText: String,
    val decryptedText: String,
)

internal enum class ExternalChangeDecision {
    ACCEPT_EXTERNAL,
    CONFLICT,
}

/**
 * Tracks the three versions involved in a SOPS editor:
 *
 * - [rollbackContent] is the committed content restored when an edit is reverted.
 * - [syncedContent] is the latest content loaded from disk.
 * - [externalConflict] is an external version waiting for the user to resolve it.
 */
internal class SopsEditorContentState(private val initialEncryptedText: String) {
    class DecryptionRequest internal constructor(
        internal val number: Int,
        internal val encryptedText: String,
    )

    var rollbackContent: SopsContent? = null
        private set
    var syncedContent: SopsContent? = null
        private set
    var externalConflict: SopsContent? = null
        private set

    private var latestDecryptionRequest = 0

    fun beginDecryption(encryptedText: String): DecryptionRequest {
        latestDecryptionRequest += 1
        return DecryptionRequest(latestDecryptionRequest, encryptedText)
    }

    /** Checks whether this is the latest request and the encrypted text has not changed since decryption began. */
    fun isCurrent(request: DecryptionRequest, currentEncryptedText: String): Boolean {
        return request.number == latestDecryptionRequest && request.encryptedText == currentEncryptedText
    }

    /** Records a current decryption result and decides whether it can replace the editor text. */
    fun completeDecryption(
        request: DecryptionRequest,
        currentEncryptedText: String,
        localDecryptedText: String,
        externalDecryptedText: String,
    ): ExternalChangeDecision? {
        if (!isCurrent(request, currentEncryptedText)) {
            return null
        }

        val externalContent = SopsContent(request.encryptedText, externalDecryptedText)
        val previousSyncedContent = syncedContent
        val hasConflict = previousSyncedContent != null &&
            localDecryptedText != previousSyncedContent.decryptedText &&
            localDecryptedText != externalDecryptedText

        return if (hasConflict) {
            externalConflict = externalContent
            ExternalChangeDecision.CONFLICT
        } else {
            syncedContent = externalContent
            externalConflict = null
            ExternalChangeDecision.ACCEPT_EXTERNAL
        }
    }

    fun setRollbackContent(encryptedText: String, decryptedText: String) {
        rollbackContent = SopsContent(encryptedText, decryptedText)
    }

    fun updateRollbackEncryptedText(encryptedText: String) {
        rollbackContent = rollbackContent?.copy(encryptedText = encryptedText)
    }

    fun encryptedRollbackText(): String = rollbackContent?.encryptedText ?: initialEncryptedText

    fun currentConflict(currentEncryptedText: String): SopsContent? {
        return externalConflict?.takeIf { it.encryptedText == currentEncryptedText }
    }

    fun acceptConflict(): SopsContent? {
        val conflict = externalConflict ?: return null
        syncedContent = conflict
        externalConflict = null
        return conflict
    }

    fun clearConflict() {
        externalConflict = null
    }
}
