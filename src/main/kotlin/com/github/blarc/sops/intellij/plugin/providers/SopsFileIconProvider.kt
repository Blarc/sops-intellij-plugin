package com.github.blarc.sops.intellij.plugin.providers

import com.github.blarc.sops.intellij.plugin.Icons
import com.github.blarc.sops.intellij.plugin.SopsUtil.isSopsFile
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class SopsFileIconProvider : FileIconProvider, DumbAware {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        return if (isSopsFile(file)) Icons.LOCKED_ICON.getThemeBasedIcon() else null
    }
}
