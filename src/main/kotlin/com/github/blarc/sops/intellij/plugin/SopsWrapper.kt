package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

object SopsWrapper {

    private const val EDITOR_PROMPT = " Press enter to return to the editor, or Ctrl+C to exit."
    private val SOPS_DIAGNOSTIC = Regex("""msg="([^"]*)"(?:\s+error="([^"]*)")?""")

    suspend fun version(
        sopsPath: String,
        project: Project,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit = {}
    ) {
        run(
            sopsPath,
            "--version",
            project,
            onSuccess = { result -> onSuccess(result.lines().first()) },
            onError = onError
        )
    }

    suspend fun encrypt(
        file: VirtualFile,
        project: Project,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit = {}
    ) {
        run("encrypt", project, file, inPlace, onSuccess, onError)
    }

    suspend fun decrypt(
        file: VirtualFile,
        project: Project,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit = {}
    ) {
        run("decrypt", project, file, inPlace, onSuccess, onError)
    }

    /**
     * SOPS determines the input format from the file extension, so [extension] should be the extension
     * of the file the text comes from, when it is known.
     */
    suspend fun decrypt(
        text: String,
        project: Project,
        extension: String? = null,
        workingDirectory: String? = null,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit = {}
    ) {
        val tmpFilePath = Files.createTempFile("sopsIntellijPlugin", ".${extension ?: "yaml"}")
        Files.writeString(tmpFilePath, text)
        // Delete on JVM exit
        val tmpFile = tmpFilePath.toFile()
        tmpFile.deleteOnExit()

        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmpFile)
        run(
            "decrypt",
            project,
            file!!,
            false,
            onSuccess,
            onError,
            workingDirectory = workingDirectory,
            fileArgument = tmpFilePath.toString()
        )
    }

    suspend fun edit(
        file: VirtualFile,
        project: Project,
        newText: String?,
        onSuccess: suspend () -> Unit = {},
        onError: suspend (SopsError) -> Unit = {}
    ) {

        val sopsPath = AppSettings.instance.sopsPath
        if (sopsPath.isNullOrBlank()) {
            onError(SopsError.ExecutableNotSet)
            return
        }
        val command = buildCommand(sopsPath, project, file.parent.path)

        val scriptFiles = ScriptUtil.createScriptFiles()
        val editorPath: String = scriptFiles.script.toAbsolutePath().toString()
            .replace("\\", "\\\\") // escape twice for windows because of ENV variable parsing
            .replace(" ", "\\ ") // escape whitespaces

        command.withEnvironment("EDITOR", editorPath)
        command.addParameter(file.name)

        var exitCode = 0
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val inputSent = AtomicBoolean(false)

        val processHandler = try {
            OSProcessHandler(command)
        } catch (e: ExecutionException) {
            FileUtils.deleteQuietly(scriptFiles.directory.toFile())
            onError(SopsError.ProcessNotCreated(e.localizedMessage.orEmpty()))
            return
        }
        processHandler.addProcessListener(object : ProcessAdapter() {

            override fun processTerminated(event: ProcessEvent) {
                // clean up the temporary files
                FileUtils.deleteQuietly(scriptFiles.directory.toFile())
                // keep the exit code from onTextAvailable
                if (event.exitCode != 0) {
                    exitCode = event.exitCode
                }
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(output) {
                    output.append(event.text)
                }

                if (ProcessOutputType.isStderr(outputType)) {
                    synchronized(errorOutput) {
                        errorOutput.append(event.text)
                    }
                }

                if (
                    ScriptUtil.INPUT_START_IDENTIFIER == event.text.trim() &&
                    inputSent.compareAndSet(false, true) &&
                    !event.processHandler.isProcessTerminated
                ) {
                    // SOPS can report an error and destroy the process while this output event is
                    // still queued, which closes the editor script's stdin.
                    try {
                        event.processHandler.processInput?.let { processInput ->
                            IOUtils.write(newText, processInput, file.charset)
                            processInput.close()
                        }
                    } catch (_: IOException) {
                        // The process failed, so its closed stdin is expected. The exit code and
                        // captured output below report the actual SOPS failure.
                    }
                }

                if (ProcessOutputType.isStderr(outputType)) {
                    event.processHandler.destroyProcess()
                    // destroying the process is apparently perfectly fine and exit code is 0
                    if (event.exitCode == 0) {
                        exitCode = 1
                    }
                }
            }
        })
        processHandler.startNotify()

        withContext(Dispatchers.IO) {
            processHandler.waitFor()
        }

        if (exitCode == 0) {
            onSuccess.invoke()
        } else if (exitCode == 200) {
            onError.invoke(SopsError.FileNotChanged)
        } else {
            val processOutput = synchronized(output) { output.toString() }
            val processErrorOutput = synchronized(errorOutput) { errorOutput.toString() }
            onError.invoke(SopsError.CommandFailed(formatEditError(processErrorOutput.ifBlank { processOutput })))
        }
    }

    suspend fun run(
        sopsCommand: String,
        project: Project,
        file: VirtualFile? = null,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit,
        workingDirectory: String? = null,
        fileArgument: String? = null
    ) {
        val sopsPath = AppSettings.instance.sopsPath
        if (sopsPath.isNullOrBlank()) {
            onError(SopsError.ExecutableNotSet)
            return
        }
        run(
            sopsPath,
            sopsCommand,
            project,
            file,
            inPlace,
            onSuccess,
            onError,
            workingDirectory,
            fileArgument
        )
    }

    suspend fun run(
        sopsPath: String,
        sopsCommand: String,
        project: Project,
        file: VirtualFile? = null,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit,
        workingDirectory: String? = null,
        fileArgument: String? = null
    ) {
        if (sopsPath.isBlank()) {
            onError(SopsError.ExecutableNotSet)
            return
        }
        if (sopsCommand.isBlank()) {
            onError(SopsError.CommandFailed("SOPS command is not configured."))
            return
        }

        val command = buildCommand(
            sopsPath,
            project,
            workingDirectory ?: file?.parent?.path
        )
        command.addParameter(sopsCommand)
        if (inPlace) {
            command.addParameter("--in-place")
        }
        if (file != null) {
            command.addParameter(fileArgument ?: file.name)
        }

        execute(command, onSuccess, onError)
    }

    internal suspend fun execute(
        command: GeneralCommandLine,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (SopsError) -> Unit
    ) {
        val output = try {
            withContext(Dispatchers.IO) {
                ExecUtil.execAndGetOutput(command)
            }
        } catch (e: ExecutionException) {
            onError(SopsError.ProcessNotCreated(e.localizedMessage.orEmpty()))
            return
        }

        if (output.exitCode != 0) {
            onError(SopsError.CommandFailed(output.stderr))
        } else {
            onSuccess(output.stdout)
        }
    }

    internal fun formatEditError(output: String): String = output
        .lineSequence()
        .map { it.replace(ScriptUtil.INPUT_START_IDENTIFIER, "").trim().removePrefix("[CMD]").trim() }
        .filter { it.isNotBlank() }
        .map { formatSopsDiagnostic(it) ?: it }
        .distinct()
        .joinToString("\n")

    private fun formatSopsDiagnostic(line: String): String? {
        val match = SOPS_DIAGNOSTIC.find(line) ?: return null
        val message = unescapeLogValue(match.groupValues[1])
            .removeSuffix(EDITOR_PROMPT)
            .removeSuffix(".")
        val detail = match.groupValues.getOrNull(2)
            ?.let(::unescapeLogValue)
            ?.takeIf { it.isNotBlank() }

        return if (detail == null) message else "$message: $detail"
    }

    private fun unescapeLogValue(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private fun buildCommand(sopsPath: String, project: Project, cwd: String? = null): GeneralCommandLine {
        val command: GeneralCommandLine = GeneralCommandLine(sopsPath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(SopsConfigResolver.configuredSopsEnvironment(project))
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(cwd)

        return command
    }

}