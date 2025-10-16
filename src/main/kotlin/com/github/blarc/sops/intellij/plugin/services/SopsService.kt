package com.github.blarc.sops.intellij.plugin.services

import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.SopsWrapper
import com.github.blarc.sops.intellij.plugin.equalsIgnoreIndent
import com.github.blarc.sops.intellij.plugin.getLastCommitContent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.writeText
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class SopsService(
    private val project: Project,
    private val cs: CoroutineScope
) {

    var errors: MutableMap<String, String> = mutableMapOf()

    fun decrypt(
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.decrypting")) {
                SopsWrapper.decrypt(
                    file, inPlace,
                    { decryptedText ->
                        errors[file.path] = ""
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        onSuccess(decryptedText)
                    },
                    { message ->
                        errors[file.path] = message
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        onError(message)
                    }
                )
            }
        }
    }

    fun decrypt(
        text: String,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.decrypting")) {
                SopsWrapper.decrypt(text, onSuccess, onError)
            }
        }
    }

    fun encrypt(
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.encrypting")) {
                val newDecryptedText = readAction {
                    file.readText()
                }

                val originalEncryptedText = file.getLastCommitContent(project)
                var originalDecryptedText = ""
                SopsWrapper.decrypt(originalEncryptedText.orEmpty(), onSuccess = {
                    originalDecryptedText = it
                })

                // Do not change the file (metadata) if the content has not changed
                if (newDecryptedText.equalsIgnoreIndent(originalDecryptedText, file.fileType, project)) {
                    withContext(Dispatchers.EDT) {
                        runWriteAction {
                            file.writeText(originalEncryptedText.orEmpty())
                        }
                    }
                    errors[file.path] = ""
                    EditorNotifications.getInstance(project).updateAllNotifications()
                    onSuccess(originalDecryptedText)
                    return@withBackgroundProgress
                }

                SopsWrapper.encrypt(
                    file, inPlace,
                    {
                        errors[file.path] = ""
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        onSuccess(it)
                    },
                    { message ->
                        errors[file.path] = message
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        onError(message)
                    }
                )
            }
        }
    }

    fun editEncrypt(
        file: VirtualFile,
        newDecryptedText: String,
        originalDecryptedText: String?,
        originalEncryptedText: String
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.encrypting")) {
                if (newDecryptedText.isBlank()) {
                    return@withBackgroundProgress
                }

                // Do not change the file (metadata) if the content has not changed
                if (newDecryptedText.equalsIgnoreIndent(originalDecryptedText, file.fileType, project)) {
                    withContext(Dispatchers.EDT) {
                        runWriteAction {
                            file.writeText(originalEncryptedText)
                        }
                    }
                    errors[file.path] = ""
                    EditorNotifications.getInstance(project).updateAllNotifications()
                    return@withBackgroundProgress
                }

                withContext(Dispatchers.IO) {
                    SopsWrapper.edit(
                        file, newDecryptedText,
                        {
                            errors[file.path] = ""
                            file.refresh(true, false)
                        },
                        { message, exitCode ->

                            // ignore "File has not changed" error
                            // https://github.com/getsops/sops/blob/main/cmd/sops/codes/codes.go#L29
                            if (exitCode != 200) {
                                errors[file.path] = message
                            }

                            file.refresh(true, false)
                        }
                    )
                }
            }
        }
    }
}
