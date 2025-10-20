package com.github.blarc.sops.intellij.plugin.services

import com.github.blarc.sops.intellij.plugin.getLastCommitContent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class SopsVcsService(
    private val project: Project, private val cs: CoroutineScope
) {
    fun getLastCommitContent(
        virtualFile: VirtualFile,
        onResult: suspend (content: String?) -> Unit
    ) {
        ProjectLevelVcsManager.getInstance(project).runAfterInitialization {
            cs.launch {
                onResult(virtualFile.getLastCommitContent(project))
            }
        }
    }
}
