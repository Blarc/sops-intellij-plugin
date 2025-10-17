package com.github.blarc.sops.intellij.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.SystemInfo
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

    var sopsPath: String? = null
    var sopsEnvironment: Map<String, String> = mapOf(
        Pair("SOPS_AGE_KEY_FILE", "~/.config/sops/age/keys.txt")
    )

    override fun getState(): AppSettings {
        return this
    }

    override fun loadState(state: AppSettings) {
        XmlSerializerUtil.copyBean(state, this)
        // If no path is configured, try to detect it
        if (sopsPath.isNullOrBlank()) {
            setDefaultSopsPath()
        }
    }

    private fun setDefaultSopsPath() {
        when {
            SystemInfo.isWindows -> {
                sopsPath = "C:\\Program Files\\sops\\sops.exe"
            }
            SystemInfo.isMac -> {
                sopsPath = "/usr/local/bin/sops"
            }
            SystemInfo.isLinux -> {
                sopsPath = "/usr/local/bin/sops"
            }
        }
    }
}
