package com.github.blarc.sops.intellij.plugin.services

import ai.grazie.text.TextRange
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.SopsUtil.reindentContent
import com.github.blarc.sops.intellij.plugin.SopsWrapper
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.writeText
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
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
            SopsWrapper.decrypt(
                file, project, inPlace,
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

    fun decrypt(
        text: String,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            SopsWrapper.decrypt(text, project, false, onSuccess, onError)
        }
    }

    fun encrypt(
        file: VirtualFile,
        inPlace: Boolean = false,
        onSuccess: suspend (decryptedText: String) -> Unit,
        onError: suspend (message: String?) -> Unit = {}
    ) {
        cs.launch {
            SopsWrapper.encrypt(
                file, project, inPlace,
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

    fun editEncrypt(
        file: VirtualFile,
        decryptedText: String,
        originalDecryptedText: String?,
        originalEncryptedText: String
    ) {
        cs.launch {
            withBackgroundProgress(project, message("background.encrypting")) {
                if (decryptedText.isBlank()) {
                    return@withBackgroundProgress
                }

                val decryptedTextFormatted = reindentContent(decryptedText, file.fileType, project)
                val originalDecryptedTextFormatted = reindentContent(originalDecryptedText.orEmpty(), file.fileType, project)

                // Do not change the file (metadata) if the content has not changed
                if (decryptedTextFormatted == originalDecryptedTextFormatted) {
                    withContext(Dispatchers.EDT) {
                        runWriteAction {
                            file.writeText(originalEncryptedText)
                        }
                    }
                    errors[file.path] = ""
                    return@withBackgroundProgress
                }

                withContext(Dispatchers.IO) {
                    SopsWrapper.edit(
                        file, decryptedText, project,
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
