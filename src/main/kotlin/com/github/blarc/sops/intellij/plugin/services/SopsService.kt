package com.github.blarc.sops.intellij.plugin.services

import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.SopsWrapper
import com.github.blarc.sops.intellij.plugin.diff.SopsDiffContents
import com.github.blarc.sops.intellij.plugin.equalsIgnoreIndent
import com.github.blarc.sops.intellij.plugin.getLastCommitContent
import com.github.blarc.sops.intellij.plugin.notifications.Notification
import com.github.blarc.sops.intellij.plugin.notifications.sendNotification
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.diff.requests.ContentDiffRequest
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
                    file, project, inPlace,
                    { decryptedText ->
                        errors[file.path] = ""
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        AppSettings.instance.recordHit()
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
        extension: String? = null,
        workingDirectory: String? = null,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.decrypting")) {
                AppSettings.instance.recordHit()
                SopsWrapper.decrypt(text, project, extension, workingDirectory, onSuccess = {
                    AppSettings.instance.recordHit()
                    onSuccess(it)
                }, onError = onError)
            }
        }
    }

    /**
     * Decrypts the contents of [request] that are encrypted with SOPS, remembering them in
     * [SopsDiffContents], and then runs [onFinished] with whether all of them could be decrypted.
     */
    fun decryptDiffContents(request: ContentDiffRequest, onFinished: suspend (success: Boolean) -> Unit = {}) {
        cs.launch {
            withBackgroundProgress(project, message("background.decrypting")) {
                for (content in SopsDiffContents.encryptedContents(request)) {
                    val encryptedText = readAction { content.document.text }
                    if (SopsDiffContents.isDecrypted(encryptedText)) {
                        continue
                    }

                    var decryptedText: String? = null
                    var errorMessage: String? = null
                    SopsWrapper.decrypt(
                        text = encryptedText,
                        project = project,
                        // SOPS picks the store from the file extension, so decrypting a content as
                        // anything else returns it in another format. A binary store file, for
                        // example, comes back wrapped in YAML.
                        extension = content.highlightFile?.extension,
                        workingDirectory = content.highlightFile?.parent?.path,
                        onSuccess = { decryptedText = it },
                        onError = { errorMessage = it }
                    )
                    val newDecryptedText = decryptedText
                    if (newDecryptedText == null) {
                        sendNotification(
                            Notification(message = message("notification.diff.decrypt-failed", errorMessage.orEmpty())),
                            project
                        )
                        onFinished(false)
                        return@withBackgroundProgress
                    }

                    SopsDiffContents.remember(encryptedText, newDecryptedText)
                    AppSettings.instance.recordHit()
                }

                onFinished(true)
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
                SopsWrapper.decrypt(
                    text = originalEncryptedText.orEmpty(),
                    project = project,
                    extension = file.extension,
                    workingDirectory = file.parent?.path,
                    onSuccess = { originalDecryptedText = it }
                )

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
                    file, project, inPlace,
                    {
                        errors[file.path] = ""
                        EditorNotifications.getInstance(project).updateAllNotifications()
                        AppSettings.instance.recordHit()
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
                    AppSettings.instance.recordHit()
                    return@withBackgroundProgress
                }

                withContext(Dispatchers.IO) {
                    SopsWrapper.edit(
                        file, project,newDecryptedText,
                        {
                            errors[file.path] = ""
                            EditorNotifications.getInstance(project).updateAllNotifications()
                            AppSettings.instance.recordHit()
                            file.refresh(true, false)
                        },
                        { message, exitCode ->

                            // ignore "File has not changed" error
                            // https://github.com/getsops/sops/blob/main/cmd/sops/codes/codes.go#L29
                            if (exitCode != 200) {
                                errors[file.path] = message
                            }

                            EditorNotifications.getInstance(project).updateAllNotifications()
                            file.refresh(true, false)
                        }
                    )
                }
            }
        }
    }
}
