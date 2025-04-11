package com.github.blarc.sops.intellij.plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class SopsVcsService(
    private val project: Project, private val cs: CoroutineScope
) {
    fun getLastCommitContent(
        virtualFile: VirtualFile,
        onResult: suspend (content: String?) -> Unit
    ) {
        val filePath = VcsUtil.getFilePath(virtualFile)
        cs.launch {
            val content = withContext(Dispatchers.IO) {
                ProjectLevelVcsManager.getInstance(project)
                    .getVcsFor(filePath)
                    ?.vcsHistoryProvider
                    ?.createSessionFor(filePath)
                    ?.revisionList
                    ?.firstOrNull()
                    ?.contentAsString()
            }
            onResult(content)
        }
    }

    // Extension function to read content as string
    private fun VcsFileRevision.contentAsString(): String? {
        return try {
            val contentBytes = this.loadContent() ?: return null
            String(contentBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
