package com.github.blarc.sops.intellij.plugin.providers

/** Tracks the encrypted and decrypted content to restore when an editor change is reverted. */
internal class SopsEditorContentState(initialEncryptedText: String) {
    class DecryptionRequest internal constructor(
        internal val number: Int,
        internal val encryptedText: String,
    )

    var originalEncryptedText = initialEncryptedText
        private set
    var originalDecryptedText: String? = null
        private set

    private var requestNumber = 0

    fun beginDecryption(encryptedText: String): DecryptionRequest {
        return DecryptionRequest(++requestNumber, encryptedText)
    }

    /** Whether the result still belongs to the latest encrypted file version. */
    fun completeDecryption(
        request: DecryptionRequest,
        currentEncryptedText: String,
    ): Boolean {
        return request.number == requestNumber && request.encryptedText == currentEncryptedText
    }

    fun setRollbackBaseline(encryptedText: String, decryptedText: String) {
        originalEncryptedText = encryptedText
        originalDecryptedText = decryptedText
    }

    fun updateEncryptedBaseline(encryptedText: String) {
        originalEncryptedText = encryptedText
    }
}

internal enum class ExternalChangeDecision {
    ACCEPT_EXTERNAL,
    CONFLICT,
}

internal fun decideExternalChange(
    hasSyncedContent: Boolean,
    localMatchesSynced: Boolean,
    externalMatchesLocal: Boolean,
): ExternalChangeDecision {
    return if (hasSyncedContent && !localMatchesSynced && !externalMatchesLocal) {
        ExternalChangeDecision.CONFLICT
    } else {
        ExternalChangeDecision.ACCEPT_EXTERNAL
    }
}
