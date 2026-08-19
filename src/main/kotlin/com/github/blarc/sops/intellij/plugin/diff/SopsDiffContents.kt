package com.github.blarc.sops.intellij.plugin.diff

import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsDocument
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import java.util.Collections

/**
 * The decrypted contents of the documents that are being compared.
 *
 * Decrypting runs SOPS, so the result is remembered. A comparison window asks for it more than once:
 * when the window is created, when it is reloaded after the decryption has finished and whenever the
 * same contents are compared again. The results are remembered by the encrypted content they come
 * from, because a window that is reloaded compares the same content in new documents.
 */
object SopsDiffContents {

    private const val MAX_REMEMBERED_CONTENTS = 16

    /**
     * The decrypted content of an encrypted content. Only the last few are kept, so that the contents
     * of windows that have been closed do not stay in memory.
     */
    private val decryptedContents: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_REMEMBERED_CONTENTS, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > MAX_REMEMBERED_CONTENTS
        }
    )

    /**
     * The contents of [request] that are encrypted with SOPS. Only documents can be looked at, so a
     * content of another kind, a directory for example, is never considered encrypted.
     */
    fun encryptedContents(request: DiffRequest): List<DocumentContent> {
        if (request !is ContentDiffRequest) return emptyList()
        return request.contents.filterIsInstance<DocumentContent>().filter { isSopsDocument(it.document) }
    }

    /** Every content of [request] that is encrypted with SOPS has been decrypted. */
    fun isDecrypted(request: DiffRequest) = encryptedContents(request).all { isDecrypted(it.encryptedText()) }

    fun isDecrypted(encryptedText: String) = decryptedContents.containsKey(encryptedText)

    fun remember(encryptedText: String, decryptedText: String) {
        decryptedContents[encryptedText] = decryptedText
    }

    /**
     * The [request] with every content that is encrypted with SOPS replaced by its decrypted content.
     * Contents that are not encrypted are shown as they are, so an encrypted file can also be
     * compared with a plain one.
     */
    fun decryptedRequest(project: Project, request: ContentDiffRequest): SimpleDiffRequest {
        val contentFactory = DiffContentFactory.getInstance()
        val contents = request.contents.map { content ->
            val decryptedText = (content as? DocumentContent)?.let { decryptedContents[it.encryptedText()] }
                ?: return@map content
            contentFactory.create(project, decryptedText, content.contentType)
        }

        val decryptedRequest = SimpleDiffRequest(
            request.title,
            contents,
            request.contentTitles.map { it.orEmpty() }
        )

        // The decrypted contents are not backed by the files that are being compared, so changes to
        // them would be lost. They are shown read only, instead of pretending that they can be edited.
        decryptedRequest.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        return decryptedRequest
    }

    private fun DocumentContent.encryptedText(): String = runReadAction { document.text }
}
