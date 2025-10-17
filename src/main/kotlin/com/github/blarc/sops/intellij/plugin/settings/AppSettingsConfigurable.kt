package com.github.blarc.sops.intellij.plugin.settings

import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.notBlank
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.externalSystem.service.ui.project.path.WorkingDirectoryField
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toMutableProperty
import com.intellij.ui.dsl.builder.toNonNullableProperty
import com.intellij.ui.dsl.builder.toNullableProperty
import java.awt.Color

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
