package com.github.blarc.sops.intellij.plugin.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SopsEditorContentStateTest {
    @Test
    fun `accepted editor changes do not replace rollback content`() {
        val state = stateWithRollback()
        val request = state.beginDecryption("encrypted edited content")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "encrypted edited content",
            localDecryptedText = "secret: edited",
            externalDecryptedText = "secret: edited",
        )

        assertEquals(ExternalChangeDecision.ACCEPT_EXTERNAL, decision)
        assertEquals(
            SopsContent("committed encrypted content", "secret: original"),
            state.rollbackContent,
        )
    }

    @Test
    fun `metadata-only update replaces only encrypted rollback text`() {
        val state = stateWithRollback()
        val rollbackSnapshot = state.rollbackContent
        val request = state.beginDecryption("updated keys and rotated ciphertext")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "updated keys and rotated ciphertext",
            localDecryptedText = "secret: original",
            externalDecryptedText = "secret: original",
            rollbackSnapshot = rollbackSnapshot,
            shouldAdoptRollbackCiphertext = true,
        )

        assertEquals(ExternalChangeDecision.ACCEPT_EXTERNAL, decision)
        assertEquals(
            SopsContent("updated keys and rotated ciphertext", "secret: original"),
            state.rollbackContent,
        )
    }

    @Test
    fun `stale decryption is rejected`() {
        val state = stateWithRollback()
        val staleRequest = state.beginDecryption("first version")
        state.beginDecryption("second version")

        val rollbackSnapshot = state.rollbackContent
        val decision = state.applyDecryptionResult(
            staleRequest,
            currentEncryptedText = "second version",
            localDecryptedText = "local",
            externalDecryptedText = "stale",
            rollbackSnapshot = rollbackSnapshot,
            shouldAdoptRollbackCiphertext = true,
        )

        assertNull(decision)
        assertEquals(
            SopsContent("committed encrypted content", "secret: original"),
            state.rollbackContent,
        )
    }

    @Test
    fun `decryption does not update a newer rollback baseline`() {
        val state = stateWithRollback()
        val oldRollbackSnapshot = state.rollbackContent
        val request = state.beginDecryption("external encrypted content")
        state.setRollbackContent("new committed encrypted content", "secret: new baseline")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "external encrypted content",
            localDecryptedText = "secret: original",
            externalDecryptedText = "secret: original",
            rollbackSnapshot = oldRollbackSnapshot,
            shouldAdoptRollbackCiphertext = true,
        )

        assertEquals(ExternalChangeDecision.ACCEPT_EXTERNAL, decision)
        assertEquals(
            SopsContent("new committed encrypted content", "secret: new baseline"),
            state.rollbackContent,
        )
    }

    @Test
    fun `metadata-only update is adopted when local changes cause a conflict`() {
        val state = stateWithSyncedContent()
        val rollbackSnapshot = state.rollbackContent
        val request = state.beginDecryption("updated keys and rotated ciphertext")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "updated keys and rotated ciphertext",
            localDecryptedText = "secret: local edit",
            externalDecryptedText = "secret: original",
            rollbackSnapshot = rollbackSnapshot,
            shouldAdoptRollbackCiphertext = true,
        )

        assertEquals(ExternalChangeDecision.CONFLICT, decision)
        assertEquals(
            SopsContent("updated keys and rotated ciphertext", "secret: original"),
            state.rollbackContent,
        )
    }

    @Test
    fun `different local and external changes cause a conflict`() {
        val state = stateWithSyncedContent()
        val request = state.beginDecryption("external encrypted content")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "external encrypted content",
            localDecryptedText = "secret: local",
            externalDecryptedText = "secret: external",
        )

        assertEquals(ExternalChangeDecision.CONFLICT, decision)
        assertEquals(
            SopsContent("external encrypted content", "secret: external"),
            state.externalConflict,
        )
    }

    @Test
    fun `same local and external change is accepted`() {
        val state = stateWithSyncedContent()
        val request = state.beginDecryption("external encrypted content")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "external encrypted content",
            localDecryptedText = "secret: same edit",
            externalDecryptedText = "secret: same edit",
        )

        assertEquals(ExternalChangeDecision.ACCEPT_EXTERNAL, decision)
        assertNull(state.externalConflict)
    }

    @Test
    fun `external change is accepted when local content is unchanged`() {
        val state = stateWithSyncedContent()
        val request = state.beginDecryption("external encrypted content")

        val decision = state.applyDecryptionResult(
            request,
            currentEncryptedText = "external encrypted content",
            localDecryptedText = "secret: original",
            externalDecryptedText = "secret: external",
        )

        assertEquals(ExternalChangeDecision.ACCEPT_EXTERNAL, decision)
        assertEquals(
            SopsContent("external encrypted content", "secret: external"),
            state.syncedContent,
        )
    }

    private fun stateWithRollback() = SopsEditorContentState("committed encrypted content").apply {
        setRollbackContent("committed encrypted content", "secret: original")
    }

    private fun stateWithSyncedContent() = stateWithRollback().apply {
        val request = beginDecryption("committed encrypted content")
        applyDecryptionResult(
            request,
            currentEncryptedText = "committed encrypted content",
            localDecryptedText = "",
            externalDecryptedText = "secret: original",
        )
    }
}
