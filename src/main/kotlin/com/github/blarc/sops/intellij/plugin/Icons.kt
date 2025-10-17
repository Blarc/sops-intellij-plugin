package com.github.blarc.sops.intellij.plugin

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import javax.swing.Icon

object Icons {

    data class PluginIcon(val bright: String, val dark: String?) {

        fun getThemeBasedIcon(): Icon {
            return if (JBColor.isBright() || dark == null) {
                IconLoader.getIcon(bright, javaClass)
            } else {
                IconLoader.getIcon(dark, javaClass)
            }
        }
    }

    val LOCKED_ICON = PluginIcon("/icons/lockedBright.svg", "/icons/lockedDark.svg")
    val KEY_ICON = PluginIcon("/icons/keyBright.svg", "/icons/keyDark.svg")
}
