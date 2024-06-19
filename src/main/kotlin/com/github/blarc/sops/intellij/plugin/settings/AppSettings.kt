package com.github.blarc.sops.intellij.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = AppSettings.SERVICE_NAME,
    storages = [Storage("Sops.xml")]
)
@Service(Service.Level.APP)
class AppSettings : PersistentStateComponent<AppSettings> {
    companion object {
        const val SERVICE_NAME = "com.github.blarc.sops.intellij.plugin.settings.AppSettings"
        val instance: AppSettings
            get() = ApplicationManager.getApplication().getService(AppSettings::class.java)
    }

    var sopsPath: String = ""
    var sopsEnvironment: Map<String, String> = emptyMap()

    override fun getState(): AppSettings {
        return this
    }

    override fun loadState(state: AppSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
