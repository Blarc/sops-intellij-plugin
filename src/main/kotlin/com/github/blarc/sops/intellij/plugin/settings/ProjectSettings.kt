package com.github.blarc.sops.intellij.plugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = ProjectSettings.SERVICE_NAME,
    storages = [Storage("Sops.xml")]
)
@Service(Service.Level.PROJECT)
class ProjectSettings : PersistentStateComponent<ProjectSettings?> {
    companion object {
        const val SERVICE_NAME = "com.github.blarc.sops.intellij.plugin.settings.ProjectSettings"
    }

    var sopsProjectEnvironment: Map<String, String> = emptyMap()

    override fun getState() = this

    override fun loadState(state: ProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

}