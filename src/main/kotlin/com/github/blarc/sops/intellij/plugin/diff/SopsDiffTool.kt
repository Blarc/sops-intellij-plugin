package com.github.blarc.sops.intellij.plugin.diff

import com.github.blarc.sops.intellij.plugin.Icons
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffContextEx
import com.intellij.diff.DiffTool
import com.intellij.diff.DiffToolType
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.SuppressiveDiffTool
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.ErrorDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/** Whether a comparison window shows the decrypted contents instead of the encrypted ones. */
private val SHOW_DECRYPTED = Key.create<Boolean>("sops.diff.showDecrypted")

/**
 * A window that has not been switched shows what the setting asks for, so that the same window can be
 * switched back and forth without changing what every other window shows.
 */
private fun DiffContext.isShowingDecrypted(): Boolean =
    getUserData(SHOW_DECRYPTED) ?: AppSettings.instance.sopsDecryptDiff

/**
 * Shows the decrypted contents of the files that are being compared with [delegate], so that SOPS
 * files can be compared without decrypting them first. It takes the place of [delegate] whenever the
 * compared contents are encrypted with SOPS, and shows them with [delegate] itself, which keeps
 * everything that viewer can do.
 *
 * Decrypting runs SOPS, which cannot be waited for while the window is being created. The window shows
 * that it is decrypting and is reloaded once the decrypted contents are known, which is when they are
 * handed over to [delegate]. The action that switches between the encrypted and the decrypted contents
 * is added to the toolbar of the window, and switches them in the same window.
 */
abstract class SopsDiffTool(private val delegate: FrameDiffTool) : FrameDiffTool, SuppressiveDiffTool {

    /** The window offers this viewer under the name of the viewer it takes the place of. */
    override fun getName(): String = delegate.name

    /** The window shows the icon of the viewer it takes the place of as well. */
    override fun getToolType(): DiffToolType = delegate.toolType

    /** Only one of the two is offered by the window, because they show the same contents. */
    override fun getSuppressedTools(): List<Class<out DiffTool>> = listOf(delegate.javaClass)

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        // The window has to be reloadable, because the contents are decrypted in the background and
        // because the action switches between the decrypted and the encrypted contents.
        if (context !is DiffContextEx || context.project == null) return false
        if (request !is ContentDiffRequest) return false
        if (SopsDiffContents.encryptedContents(request).isEmpty()) return false
        return delegate.canShow(context, request)
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer {
        val project = context.project
        if (project == null || context !is DiffContextEx || request !is ContentDiffRequest) {
            return ErrorDiffTool.INSTANCE.createComponent(context, request)
        }

        if (!context.isShowingDecrypted()) {
            return SopsDiffViewer(delegate.createComponent(context, request), context)
        }

        if (!SopsDiffContents.isDecrypted(request)) {
            return SopsDecryptingDiffViewer(project, context, request)
        }

        val decryptedRequest = SopsDiffContents.decryptedRequest(project, request)
        return SopsDiffViewer(delegate.createComponent(context, decryptedRequest), context)
    }
}

/** Shows the decrypted contents next to each other. */
class SopsSideBySideDiffTool : SopsDiffTool(SimpleDiffTool.INSTANCE)

/** Shows the decrypted contents in a single editor. */
class SopsUnifiedDiffTool : SopsDiffTool(UnifiedDiffTool.INSTANCE)

/**
 * Shows that the contents are being decrypted and reloads the comparison window once they are, which
 * is when the decrypted contents are shown.
 */
private class SopsDecryptingDiffViewer(
    private val project: Project,
    private val context: DiffContextEx,
    private val request: ContentDiffRequest,
) : FrameDiffTool.DiffViewer {

    private val panel = DiffUtil.createMessagePanel(message("diff.decrypting"))
    private var isDisposed = false

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun init(): FrameDiffTool.ToolbarComponents {
        context.showProgressBar(true)
        project.service<SopsService>().decryptDiffContents(request) { isDecrypted ->
            withContext(Dispatchers.EDT) {
                if (isDisposed) return@withContext
                context.showProgressBar(false)
                // Contents that could not be decrypted are shown as they are, so that the toolbar
                // shows what the window shows and decrypting is not tried over and over again.
                if (!isDecrypted) {
                    context.putUserData(SHOW_DECRYPTED, false)
                }
                // The decrypted contents are remembered, so the reloaded window shows them without
                // decrypting them again.
                context.reloadDiffRequest()
            }
        }

        val components = FrameDiffTool.ToolbarComponents()
        components.toolbarActions = listOf(SopsShowDecryptedAction(context))
        return components
    }

    override fun dispose() {
        isDisposed = true
    }
}

/**
 * The viewer that shows the contents, with the action that switches between the decrypted and the
 * encrypted contents added to the toolbar of the comparison window.
 */
private class SopsDiffViewer(
    private val delegate: FrameDiffTool.DiffViewer,
    private val context: DiffContextEx,
) : FrameDiffTool.DiffViewer by delegate {

    override fun init(): FrameDiffTool.ToolbarComponents {
        val components = delegate.init()
        components.toolbarActions = components.toolbarActions.orEmpty() + SopsShowDecryptedAction(context)
        return components
    }
}

/**
 * Switches the comparison window between the encrypted and the decrypted contents. It is pressed while
 * the decrypted contents are shown, so that a window that shows the contents as they are on disk looks
 * like any other comparison window.
 */
private class SopsShowDecryptedAction(private val context: DiffContextEx) : ToggleAction(
    message("diff.action.show-decrypted"),
    null,
    Icons.KEY_ICON.getThemeBasedIcon()
), DumbAware {

    override fun isSelected(e: AnActionEvent) = context.isShowingDecrypted()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        context.putUserData(SHOW_DECRYPTED, state)
        context.reloadDiffRequest()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
