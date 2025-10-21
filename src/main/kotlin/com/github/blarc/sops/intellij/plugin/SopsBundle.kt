package com.github.blarc.sops.intellij.plugin

import com.intellij.DynamicBundle
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.net.URI

@NonNls
private const val BUNDLE = "messages.SopsBundle"

object SopsBundle : DynamicBundle(BUNDLE) {

    val URL_BUG_REPORT = URI("https://github.com/Blarc/sops-intellij-plugin/issues")
    val URL_GITHUB = URI("https://github.com/Blarc/sops-intellij-plugin")
    val URL_GITHUB_SPONSORS = URI("https://github.com/sponsors/Blarc")
    val URL_KOFI = URI("https://ko-fi.com/blarc")

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)

    @Suppress("unused")
    @JvmStatic
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)

    fun openRepository() {
        BrowserLauncher.instance.open("https://github.com/Blarc/sops-intellij-plugin");
    }

    fun openPluginSettings(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project,
            message("name")
        )
    }

    fun plugin() = PluginManagerCore.getPlugin(PluginId.getId("com.github.blarc.sops-intellij-plugin"))


}
