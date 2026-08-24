package com.github.blarc.sops.intellij.plugin

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SopsConfigResolverTest {

    @Test
    fun `nearest config wins`() = withTempDirectory { root ->
        val nested = root.resolve("service/config").createDirectories()
        val parentConfig = root.resolve(".sops.yaml").also { it.writeText("parent") }
        val nestedConfig = nested.resolve(".sops.yaml").also { it.writeText("nested") }

        assertEquals(nestedConfig, SopsConfigResolver.findConfigPath(nested))
        assertEquals(parentConfig, SopsConfigResolver.findConfigPath(root.resolve("service")))
    }

    @Test
    fun `explicit absolute SOPS_CONFIG wins`() = withTempDirectory { root ->
        val discovered = root.resolve(".sops.yaml").also { it.writeText("discovered") }
        val explicit = root.resolve("custom.yaml").also { it.writeText("explicit") }

        assertEquals(
            explicit,
            SopsConfigResolver.findConfigPath(root, mapOf("SOPS_CONFIG" to explicit.toString()))
        )
        assertEquals(discovered, SopsConfigResolver.findConfigPath(root))
    }

    @Test
    fun `explicit relative SOPS_CONFIG is resolved from the working directory`() = withTempDirectory { root ->
        val config = root.resolve("config/sops.yaml").also {
            it.parent.createDirectories()
            it.writeText("explicit")
        }

        assertEquals(
            config,
            SopsConfigResolver.findConfigPath(root, mapOf("SOPS_CONFIG" to "config/sops.yaml"))
        )
    }

    @Test
    fun `missing explicit SOPS_CONFIG does not fall back to discovered config`() = withTempDirectory { root ->
        root.resolve(".sops.yaml").also { it.writeText("discovered") }

        assertNull(
            SopsConfigResolver.findConfigPath(
                root,
                mapOf("SOPS_CONFIG" to root.resolve("missing.yaml").toString())
            )
        )
    }

    @Test
    fun `sops yml is not automatically discovered`() = withTempDirectory { root ->
        root.resolve(".sops.yml").also { it.writeText("alternate") }

        assertNull(SopsConfigResolver.findConfigPath(root))
    }

    private fun withTempDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("sops-config-resolver-test")
        try {
            test(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
