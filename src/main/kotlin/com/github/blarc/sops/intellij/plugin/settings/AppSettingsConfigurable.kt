package com.github.blarc.sops.intellij.plugin.settings

import com.github.blarc.sops.intellij.plugin.SopsBundle
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.notBlank
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*

class AppSettingsConfigurable : BoundConfigurable(message("name")) {

    val sopsPathTextField = TextFieldWithBrowseButton()
    val sopsPathVerifyLabel = JBLabel()

    override fun createPanel() = panel {
        row {
            label(message("settings.path"))
                .widthGroup("label")
            cell(sopsPathTextField)
                .align(Align.FILL)
                .bindText(AppSettings.instance::sopsPath.toNonNullableProperty(""))
                .validationOnApply { notBlank(it.text) }
                .resizableColumn()
                .applyToComponent {
                    addBrowseFolderListener(
                        message("settings.path"),
                        null,
                        null,
                        FileChooserDescriptorFactory.createSingleFileDescriptor()
                    )
                }

            button(message("settings.verify")) {
                AppService.instance.version(
                    sopsPathTextField.text,
                    { result ->
                        sopsPathVerifyLabel.text = "✓ $result"
                        sopsPathVerifyLabel.foreground = JBColor.GREEN
                    },
                    { error ->
                        sopsPathVerifyLabel.text = "✗ $error"
                        sopsPathVerifyLabel.foreground = JBColor.RED
                    })
            }
                .align(AlignX.RIGHT)
                .align(AlignY.TOP)
        }
        row {
            label("")
                .widthGroup("label")
            cell(sopsPathVerifyLabel)
        }

        row {
            label(message("settings.environment"))
                .widthGroup("label")
            cell(EnvironmentVariablesTextFieldWithBrowseButton())
                .align(AlignX.FILL)
                .bind(
                    componentSet = { c, s -> c.envs = s },
                    componentGet = { c -> c.envs },
                    prop = AppSettings.instance::sopsEnvironment.toMutableProperty()
                )
        }

        row {
            panel {
                row {
                    label(message("settings.encryptOnChange"))
                        .widthGroup("label")
                    checkBox("")
                        .bindSelected(AppSettings.instance::sopsEncryptOnChange)
                }
            }.align(AlignY.TOP)
        }.resizableRow()

        row {
            browserLink(message("settings.report-bug"), SopsBundle.URL_BUG_REPORT.toString())
            browserLink(message("settings.github-star"), SopsBundle.URL_GITHUB.toString())
            browserLink(message("settings.github-sponsors"), SopsBundle.URL_GITHUB_SPONSORS.toString())
            browserLink(message("settings.kofi"), SopsBundle.URL_GITHUB_SPONSORS.toString())
        }
    }
}
