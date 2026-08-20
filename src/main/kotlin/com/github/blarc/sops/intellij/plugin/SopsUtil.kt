package com.github.blarc.sops.intellij.plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

object SopsUtil {

    val SOPS_KEYWORDS: List<String> = listOf(
        "sops",
        "lastmodified",
        "version"
    )

    private val SOPS_DOCUMENT_KEY = Key.create<Pair<Long, Boolean>>("sops.isSopsDocument")
    private val SOPS_FILE_KEY = Key.create<Pair<Long, Boolean>>("sops.isSopsFile")

    /**
     * Checks whether [file] contains SOPS metadata, caching the result until the file changes.
     *
     * File icon providers and file editor providers can both ask this question frequently. Keeping
     * the cache here makes sure they make the same decision without repeatedly reading the file.
     */
    fun isSopsFile(file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false

        val cached = file.getUserData(SOPS_FILE_KEY)
        if (cached != null && cached.first == file.modificationStamp) {
            return cached.second
        }

        return isSopsFileBasedOnContent(file).also { isSopsFile ->
            file.putUserData(SOPS_FILE_KEY, file.modificationStamp to isSopsFile)
        }
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

    private fun isSopsFileBasedOnContent(file: VirtualFile): Boolean {
        try {
            val content: String = ReadAction.compute<String, RuntimeException> { LoadTextUtil.loadText(file).toString() }
            return isSopsContent(content)
        } catch (e: Exception) {
            thisLogger().warn("could not get content of file ${file.name} $e")
        }
        return false
    }

    private fun isSopsContent(content: String): Boolean {
        return SOPS_KEYWORDS.all { content.contains(it) }
    }
}
