package com.github.blarc.sops.intellij.plugin

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun String?.equalsIgnoreIndent(other: String?, fileType: FileType, project: Project): Boolean {
    val thisFormatted = reindentContent(this.orEmpty(), fileType, project)
    val otherFormatted = reindentContent(other.orEmpty(), fileType, project)
    return thisFormatted == otherFormatted
}

private suspend fun reindentContent(content: String, fileType: FileType, project: Project): String {
    return readAction {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            "tmp",
            fileType,
            content
        )
        CodeStyleManager.getInstance(project).reformatText(
            psiFile,
            0,
            psiFile.textLength
        )
        psiFile.text
    }
}

suspend fun VirtualFile.getLastCommitContent(project: Project): String? {
    val filePath = VcsUtil.getFilePath(this)
    return withContext(Dispatchers.IO) {
        ProjectLevelVcsManager.getInstance(project)
            .getVcsFor(filePath)
            ?.vcsHistoryProvider
            ?.createSessionFor(filePath)
            ?.revisionList
            ?.firstOrNull()
            ?.contentAsString()
    }
}

private fun VcsFileRevision.contentAsString(): String? {
    return try {
        val contentBytes = this.loadContent() ?: return null
        String(contentBytes)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}