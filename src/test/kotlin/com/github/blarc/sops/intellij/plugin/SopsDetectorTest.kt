package com.github.blarc.sops.intellij.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SopsDetectorTest {

    private val detector = SopsDetector()

    @Test
    fun `recognizes SOPS YAML metadata`() {
        assertTrue(detector.isSopsContent(fixture("encrypted.yaml")))
    }

    @Test
    fun `recognizes SOPS JSON metadata`() {
        assertTrue(detector.isSopsContent(fixture("encrypted.json")))
    }

    @Test
    fun `recognizes SOPS ENV metadata`() {
        assertTrue(detector.isSopsContent(fixture("encrypted.env")))
    }

    @Test
    fun `recognizes SOPS INI metadata`() {
        assertTrue(detector.isSopsContent(fixture("encrypted.ini")))
    }

    @Test
    fun `rejects metadata with an unencrypted mac`() {
        assertFalse(detector.isSopsContent(fixture("unencrypted-mac.yaml")))
    }

    @Test
    fun `rejects files that only contain SOPS-related words`() {
        assertFalse(detector.isSopsContent(fixture("keyword-only.txt")))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/sops/$name")) {
            "Missing test fixture: $name"
        }.readText()
}
