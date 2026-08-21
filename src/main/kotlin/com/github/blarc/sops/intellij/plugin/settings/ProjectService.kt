package com.github.blarc.sops.intellij.plugin.settings

import com.github.blarc.sops.intellij.plugin.SopsError
import com.github.blarc.sops.intellij.plugin.SopsWrapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class ProjectService(private val project: Project, private val cs: CoroutineScope) {

    fun version(
        sopsPath: String,
        onSuccess: suspend (result: String) -> Unit,
        onError: suspend (error: SopsError) -> Unit = {}
    ) {
        cs.launch {
            SopsWrapper.version(sopsPath, project, onSuccess, onError)
        }
    }
}
