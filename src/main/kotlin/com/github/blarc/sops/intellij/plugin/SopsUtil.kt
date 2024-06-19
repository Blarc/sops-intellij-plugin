package com.github.blarc.sops.intellij.plugin

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.VirtualFile

object SopsUtil {

    val SOPS_KEYWORDS: List<String> = listOf(
        "sops",
        "lastmodified",
        "version"
    )

    fun isSopsFileBasedOnContent(file: VirtualFile): Boolean {
        try {
            val content: String = ReadAction.compute<String, RuntimeException> { LoadTextUtil.loadText(file).toString() }
            return SOPS_KEYWORDS.stream().allMatch { s: String -> content.contains(s) }
        } catch (e: Exception) {
            thisLogger().warn("could not get content of file ${file.name} $e")
        }
        return false
    }
}
