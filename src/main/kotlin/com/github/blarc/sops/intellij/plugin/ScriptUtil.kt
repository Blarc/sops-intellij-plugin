package com.github.blarc.sops.intellij.plugin

import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*

object ScriptUtil {

    const val INPUT_START_IDENTIFIER: String = "8aa203fd-cc7d-4e00-9c2f-af32b872e4c3"

    private val PWSH_SCRIPT: String = """
		param (${'$'}file)
		Write-Output "%s"
		${'$'}stdin = [System.Console]::In
		${'$'}content = ${'$'}stdin.ReadToEnd()
		${'$'}content | Out-File "${'$'}file"
		
		""".trimIndent().format(INPUT_START_IDENTIFIER)

    /*
    #!/usr/bin/env sh
    set -eu
    printf "8aa203fd-cc7d-4e00-9c2f-af32b872e4c3"
    cat - > "$1"
     */

    private val SHELL_SCRIPT: String = """
		#!/usr/bin/env sh
		set -eu
		printf "%s"
		cat - > "${'$'}1"
		
		""".trimIndent().format(INPUT_START_IDENTIFIER)

    fun createScriptFiles(): ScriptFiles {
        // create temp directory
        val tempDirectory = Files.createTempDirectory("simple-sops-edit")

        // make sure temp directory is cleaned on application exit
        FileUtils.forceDeleteOnExit(tempDirectory.toFile())

        if (SystemUtils.IS_OS_WINDOWS) {
            val cmdFile = Files.createTempFile(tempDirectory, null, ".cmd")
            val pwshFile = Files.createTempFile(tempDirectory, null, ".ps1")

            makeExecutable(cmdFile, pwshFile)

            Files.writeString(pwshFile, PWSH_SCRIPT, StandardCharsets.UTF_8, StandardOpenOption.APPEND)

            val cmdFileContent = "@powershell.exe -NoProfile -File \"" + pwshFile.toAbsolutePath() + "\" %1"
            Files.writeString(cmdFile, cmdFileContent, StandardCharsets.UTF_8, StandardOpenOption.APPEND)

            return ScriptFiles(tempDirectory, cmdFile)
        } else {
            val shellFile = Files.createTempFile(tempDirectory, null, ".sh")

            makeExecutable(shellFile)

            Files.writeString(shellFile, SHELL_SCRIPT, StandardCharsets.UTF_8, StandardOpenOption.APPEND)


            return ScriptFiles(tempDirectory, shellFile)

        }
    }

    private fun makeExecutable(vararg files: Path) {
        check(Arrays.stream(files).map { obj: Path -> obj.toFile() }.allMatch { f: File -> f.setExecutable(true) }) { "Could not make scripts executable" }
    }

    data class ScriptFiles(val directory: Path, val script: Path)
}
