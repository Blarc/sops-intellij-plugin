package com.github.blarc.sops.intellij.plugin.services

import com.github.blarc.sops.intellij.plugin.ScriptUtil
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.providers.SopsEditorProvider
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.writeText
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class SopsService(
    private val project: Project,
    private val cs: CoroutineScope
) {

    var error: String = ""

    fun sopsDecrypt(file: VirtualFile, onResult: suspend (decryptedText: String) -> Unit) {
        cs.launch {
            withBackgroundProgress(project, message("background.decrypting")) {
                val commandLine = try {
                    buildCommand(file.parent.path)
                } catch (e: IllegalArgumentException) {
                    error = e.message ?: ""
                    return@withBackgroundProgress
                }

                commandLine.addParameter("-d")
                commandLine.addParameter(file.path)

                val output = try {
                    withContext(Dispatchers.IO) {
                        ExecUtil.execAndGetOutput(commandLine)
                    }
                } catch (e: ProcessNotCreatedException) {
                    error = e.localizedMessage
                    return@withBackgroundProgress
                }

                val result = if (output.exitCode != 0) {
                    error = output.stderr
                    "Error: " + output.stderr
                } else {
                    error = ""
                    output.stdout
                }

                onResult(result)
            }
        }
    }

    fun sopsEncrypt(editor: SopsEditorProvider.SopsEditor) {
        cs.launch {
            withBackgroundProgress(project, message("background.encrypting")) {
                val decryptedText = editor.getDecryptedText()
                if (decryptedText.isBlank()) {
                    return@withBackgroundProgress
                }

                // Do not change file (metadata), if the content has not changed
                if (decryptedText == editor.originalDecryptedText) {
                    withContext(Dispatchers.EDT) {
                        runWriteAction {
                            editor.file.writeText(editor.originalEncryptedText)
                        }
                    }
                    error = ""
                    return@withBackgroundProgress
                }

                // Do not change file (metadata), if the content has not changed
                if (decryptedText == editor.previousDecryptedText) {
                    withContext(Dispatchers.EDT) {
                        runWriteAction {
                            editor.file.writeText(editor.previousEncryptedText)
                        }
                    }
                    error = ""
                    return@withBackgroundProgress
                }

                withContext(Dispatchers.IO) {
                    try {
                        edit(editor.file, decryptedText, {
                            error = ""
                            editor.file.refresh(true, false)
                        }, {
                            error = it

                            val encryptedText = readAction {
                                editor.file.readText()
                            }

                            withContext(Dispatchers.EDT) {
                                runWriteAction {
                                    editor.file.writeText(encryptedText)
                                }
                            }

                            editor.previousDecryptedText = decryptedText
                            editor.previousEncryptedText = encryptedText
                        })
                    } catch (e: IllegalArgumentException) {
                        error = e.localizedMessage
                    } catch (e: ProcessNotCreatedException) {
                        error = e.localizedMessage

                    }
                }
            }
        }
    }

    /**
     * edits encrypted file with given content
     *
     * @param project        project
     * @param file           file
     * @param newContent     new content
     * @param successHandler called on success
     * @param failureHandler called on failure
     */
    private fun edit(
        file: VirtualFile,
        newContent: String?,
        successHandler: suspend () -> Unit,
        failureHandler: suspend (String) -> Unit
    ) {
        val scriptFiles = ScriptUtil.createScriptFiles()

        val command = buildCommand(file.parent.path)

        val editorPath: String = scriptFiles.script.toAbsolutePath().toString()
            .replace("\\", "\\\\") // escape twice for windows because of ENV variable parsing
            .replace(" ", "\\ ") // escape whitespaces

        command.withEnvironment("EDITOR", editorPath)
        command.addParameter(file.name)

        run(command, object : ProcessAdapter() {
            private val failed = AtomicBoolean(false)
            private var output = AtomicReference<String>()

            override fun processTerminated(event: ProcessEvent) {
                // clean up the temporary files
                FileUtils.deleteQuietly(scriptFiles.directory.toFile())

                cs.launch {
                    if (event.exitCode == 0 && !failed.get()) {
                        successHandler.invoke()
                    } else {
                        failureHandler.invoke(output.get())
                    }
                }
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.set(event.text)

                if (null != event.text && ScriptUtil.INPUT_START_IDENTIFIER == event.text.trim()) {
                    IOUtils.write(newContent, event.processHandler.processInput, file.charset)
                    event.processHandler.processInput!!.close()
                }

                if (ProcessOutputType.isStderr(outputType)) {
                    event.processHandler.destroyProcess()
                    // destroy process is apparently perfectly fine and exit code is 0
                    failed.set(true)
                }

            }
        })
    }

    private fun run(command: GeneralCommandLine, vararg listener: ProcessListener) {
        val processHandler = OSProcessHandler(command)
        Arrays.stream(listener).forEach { processHandler.addProcessListener(it) }
        processHandler.startNotify()
    }

    private fun buildCommand(cwd: String): GeneralCommandLine {
        if (AppSettings.instance.sopsPath.isBlank()) {
            throw IllegalArgumentException("Sops path must be specified!")
        }

        val command: GeneralCommandLine = GeneralCommandLine(AppSettings.instance.sopsPath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(AppSettings.instance.sopsEnvironment)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory(cwd)

        return command
    }


}
