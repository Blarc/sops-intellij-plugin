package com.github.blarc.sops.intellij.plugin.settings

import com.github.blarc.sops.intellij.plugin.SopsWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
class AppService(private val cs: CoroutineScope) {

    companion object {
        val instance: AppService
            get() = ApplicationManager.getApplication().getService(AppService::class.java)
    }

    fun version(
        sopsPath: String,
        onSuccess: suspend (result: String) -> Unit,
        onError: suspend (message: String) -> Unit = {}
    ) {
        cs.launch {
            SopsWrapper.version(sopsPath, onSuccess, onError)
        }
    }
}
