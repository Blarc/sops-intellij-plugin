package com.github.blarc.sops.intellij.plugin.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SopsEditorContentStateTest {
    @Test
    fun `editing and reverting keeps the original rollback baseline`() {
        val state = SopsEditorContentState("committed encrypted content")
        state.setRollbackBaseline("committed encrypted content", "secret: original")

        val editedRequest = state.beginDecryption("encrypted edited content")

        assertTrue(state.completeDecryption(editedRequest, "encrypted edited content"))
        assertEquals("committed encrypted content", state.originalEncryptedText)
        assertEquals("secret: original", state.originalDecryptedText)
    }

    @Test
    fun `metadata-only external content can replace the encrypted rollback baseline`() {
        val state = SopsEditorContentState("committed encrypted content")
        state.setRollbackBaseline("committed encrypted content", "secret: original")

        val reloadRequest = state.beginDecryption("updated keys and rotated ciphertext")

        assertTrue(state.completeDecryption(reloadRequest, "updated keys and rotated ciphertext"))
        state.updateEncryptedBaseline("updated keys and rotated ciphertext")
        assertEquals("updated keys and rotated ciphertext", state.originalEncryptedText)
        assertEquals("secret: original", state.originalDecryptedText)
    }

    @Test
    fun `stale decryption cannot replace a newer external version`() {
        val state = SopsEditorContentState("first version")
        val firstRequest = state.beginDecryption("first version")
        val secondRequest = state.beginDecryption("second version")

        assertFalse(state.completeDecryption(firstRequest, "second version"))
        assertTrue(state.completeDecryption(secondRequest, "second version"))
    }

    @Test
    fun `local and external changes with different content cause a conflict`() {
        assertEquals(
            ExternalChangeDecision.CONFLICT,
            decideExternalChange(
                hasSyncedContent = true,
                localMatchesSynced = false,
                externalMatchesLocal = false,
            )
        )
    }

    @Test
    fun `the same change made locally and externally does not cause a conflict`() {
        assertEquals(
            ExternalChangeDecision.ACCEPT_EXTERNAL,
            decideExternalChange(
                hasSyncedContent = true,
                localMatchesSynced = false,
                externalMatchesLocal = true,
            )
        )
    }

    @Test
    fun `external changes load automatically when there are no local changes`() {
        assertEquals(
            ExternalChangeDecision.ACCEPT_EXTERNAL,
            decideExternalChange(
                hasSyncedContent = true,
                localMatchesSynced = true,
                externalMatchesLocal = false,
            )
        )
    }
}
