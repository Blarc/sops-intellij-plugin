package com.github.blarc.sops.intellij.plugin.settings

import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toMutableProperty

class AppSettingsConfigurable : BoundConfigurable(message("name")) {
    override fun createPanel() = panel {
        row {
            label(message("settings.path"))
                .widthGroup("label")
            textField()
                .align(Align.FILL)
                .bindText(AppSettings.instance::sopsPath)
        }

        row {
            label(message("settings.environment"))
                .widthGroup("label")
            cell(EnvironmentVariablesComponent())
                .align(Align.FILL)
                .applyToComponent {
                    text = ""
                }
                .bind(
                    componentSet = { c, s -> c.envs = s },
                    componentGet = { c -> c.envs },
                    prop = AppSettings.instance::sopsEnvironment.toMutableProperty()
                )
        }
    }
}
