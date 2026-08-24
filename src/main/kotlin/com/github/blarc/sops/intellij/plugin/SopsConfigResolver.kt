package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.github.blarc.sops.intellij.plugin.settings.ProjectSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Finds the SOPS configuration file selected for a SOPS working directory. */
object SopsConfigResolver {
    private const val CONFIG_FILE_NAME = ".sops.yaml"
    private const val MAX_LOOKUP_DEPTH = 100

    /**
     * Finds the config file that SOPS would use for [file].
     *
     * The working directory matches the one used by [SopsWrapper] for file operations. The
     * inherited environment is combined with the plugin's project and application overrides so
     * an explicit SOPS_CONFIG is handled consistently with the SOPS process.
     */
    fun findConfigFile(file: VirtualFile, project: Project): VirtualFile? {
        val workingDirectory = file.parent ?: return null
        val configPath = findConfigPath(
            Paths.get(workingDirectory.path),
            EnvironmentUtil.getEnvironmentMap() + configuredSopsEnvironment(project)
        ) ?: return null

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(configPath.toFile())
    }

    internal fun findConfigPath(
        startDirectory: Path,
        environment: Map<String, String> = emptyMap()
    ): Path? {
        val normalizedStart = startDirectory.toAbsolutePath().normalize()
        val configuredPath = environment["SOPS_CONFIG"]?.takeIf { it.isNotBlank() }

        if (configuredPath != null) {
            return resolveConfiguredPath(normalizedStart, configuredPath)
        }

        var directory = normalizedStart
        repeat(MAX_LOOKUP_DEPTH) {
            val configPath = directory.resolve(CONFIG_FILE_NAME)
            if (Files.isRegularFile(configPath)) {
                return configPath
            }

            val parent = directory.parent ?: return null
            if (parent == directory) {
                return null
            }
            directory = parent
        }

        return null
    }

    internal fun configuredSopsEnvironment(project: Project): Map<String, String> =
        project.service<ProjectSettings>().sopsProjectEnvironment + AppSettings.instance.sopsEnvironment

    private fun resolveConfiguredPath(startDirectory: Path, configuredPath: String): Path? {
        return try {
            val path = Paths.get(configuredPath)
            val resolved = if (path.isAbsolute) path else startDirectory.resolve(path)
            resolved.normalize().takeIf { Files.isRegularFile(it) }
        } catch (_: InvalidPathException) {
            null
        }
    }
}
