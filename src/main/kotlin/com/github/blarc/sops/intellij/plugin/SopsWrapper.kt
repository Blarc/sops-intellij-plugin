package com.github.blarc.sops.intellij.plugin

import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object SopsWrapper {

    suspend fun encrypt(
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (String) -> Unit = {}
    ) {
        run("encrypt", file, inPlace, onSuccess, onError)
    }

    suspend fun decrypt(
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (String) -> Unit = {}
    ) {
        run("decrypt", file, inPlace, onSuccess, onError)
    }

    suspend fun decrypt(
        text: String,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (String) -> Unit = {}
    ) {
        val tmpFilePath = Files.createTempFile("sopsIntellijPlugin", ".yaml")
        Files.writeString(tmpFilePath, text)
        // Delete on JVM exit
        val tmpFile = tmpFilePath.toFile()
        tmpFile.deleteOnExit()

        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmpFile)
        run("decrypt", file!!, false, onSuccess, onError)
    }

    suspend fun edit(
        file: VirtualFile,
        newText: String?,
        onSuccess: suspend () -> Unit,
        onError: suspend (String, Int) -> Unit = { _, _ -> }
    ) {

        val sopsPath = AppSettings.instance.sopsPath
        if (sopsPath == null) {
            onError("Sops path not configured", 1)
            return
        }
        val command = buildCommand(sopsPath, file.parent.path)

        val scriptFiles = ScriptUtil.createScriptFiles()
        val editorPath: String = scriptFiles.script.toAbsolutePath().toString()
            .replace("\\", "\\\\") // escape twice for windows because of ENV variable parsing
            .replace(" ", "\\ ") // escape whitespaces

        command.withEnvironment("EDITOR", editorPath)
        command.addParameter(file.name)

        var exitCode = 0
        var output = ""

        val processHandler = OSProcessHandler(command)
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
                output += event.text

                if (null != event.text && ScriptUtil.INPUT_START_IDENTIFIER == event.text.trim()) {
                    IOUtils.write(newText, event.processHandler.processInput, file.charset)
                    event.processHandler.processInput!!.close()
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
        } else {
            onError.invoke(output, exitCode)
        }
    }

    suspend fun run(
        sopsCommand: String,
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        val sopsPath = AppSettings.instance.sopsPath
        if (sopsPath == null) {
            onError("Sops path not configured")
            return
        }

        val command = buildCommand(sopsPath, file.parent.path)
        command.addParameter(sopsCommand)
        if (inPlace) {
            command.addParameter("--in-place")
        }
        command.addParameter(file.name)

        val output = try {
            withContext(Dispatchers.IO) {
                ExecUtil.execAndGetOutput(command)
            }
        } catch (e: ProcessNotCreatedException) {
            onError(e.localizedMessage)
            return
        }

        if (output.exitCode != 0) {
            onError(output.stderr)
        } else {
            onSuccess(output.stdout)
        }
    }

    private fun buildCommand(sopsPath: String, cwd: String? = null): GeneralCommandLine {
        val command: GeneralCommandLine = GeneralCommandLine(sopsPath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(AppSettings.instance.sopsEnvironment)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(cwd)

        return command
    }

}