package com.github.blarc.sops.intellij.plugin

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SopsUtil {

    val SOPS_KEYWORDS: List<String> = listOf(
        "sops",
        "lastmodified",
        "version"
    )

    private val SOPS_DOCUMENT_KEY = Key.create<Pair<Long, Boolean>>("sops.isSopsDocument")

    fun isSopsFileBasedOnContent(file: VirtualFile): Boolean {
        try {
            val content: String = ReadAction.compute<String, RuntimeException> { LoadTextUtil.loadText(file).toString() }
            return isSopsContent(content)
        } catch (e: Exception) {
            thisLogger().warn("could not get content of file ${file.name} $e")
        }
        return false
    }

    fun isSopsContent(content: String): Boolean {
        return SOPS_KEYWORDS.all { content.contains(it) }
    }

    /**
     * Actions are updated often, so the result is cached until the document is modified,
     * to avoid scanning the whole content on every update.
     */
    fun isSopsDocument(document: Document): Boolean {
        val cached = document.getUserData(SOPS_DOCUMENT_KEY)
        if (cached != null && cached.first == document.modificationStamp) {
            return cached.second
        }

        val isSopsDocument = isSopsContent(runReadAction { document.text })
        document.putUserData(SOPS_DOCUMENT_KEY, document.modificationStamp to isSopsDocument)
        return isSopsDocument
    }
}
