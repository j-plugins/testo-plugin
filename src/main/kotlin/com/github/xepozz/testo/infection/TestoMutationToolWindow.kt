package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.ui.TestoReportViewer
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.nio.file.Files

/**
 * The *Mutations* tool window, one tab per mutated Testo run. Made available on first use, so a project that never
 * runs Infection never gets the stripe button.
 */
internal object TestoMutationToolWindow {
    const val ID = "Mutations"

    private val RUN_KEY = Key.create<TestoMutationRun>("testo.mutation.run")

    /**
     * Shows [run] without focusing. A rerun of the same Testo run takes over the tab its last run had, unless that tab
     * is pinned: a pinned tab keeps its run and cannot be closed until unpinned.
     */
    fun add(project: Project, run: TestoMutationRun): Content {
        val window = window(project)
        val manager = window.contentManager
        val panel = TestoMutationPanel(project, run)
        val content = ContentFactory.getInstance().createContent(panel, title(manager.contents, run), false).apply {
            putUserData(RUN_KEY, run)
            isCloseable = true
            isPinnable = true
            addPropertyChangeListener { event ->
                if (event.propertyName == Content.PROP_PINNED) isCloseable = !isPinned
            }
            // The files stay: they belong to the Testo run's archive. A run still going stops with its tab.
            setDisposer {
                Disposer.dispose(panel)
                if (run.isRunning) run.stop()
            }
            preferredFocusableComponent = panel.preferredFocus
        }
        val previous = manager.contents.firstOrNull { !it.isPinned && it.getUserData(RUN_KEY)?.sourceRunDir == run.sourceRunDir }
        if (previous != null) {
            val index = manager.getIndexOfContent(previous)
            manager.addContent(content, index)
            manager.removeContent(previous, true)
        } else {
            manager.addContent(content)
        }
        manager.setSelectedContent(content)
        return content
    }

    // Pinned tabs of the same Testo run stay beside the new one, which is numbered to tell them apart.
    private fun title(contents: Array<Content>, run: TestoMutationRun): String {
        val pinned = contents.count { it.isPinned && it.getUserData(RUN_KEY)?.sourceRunDir == run.sourceRunDir }
        return if (pinned == 0) run.title else "${run.title} (${pinned + 1})"
    }

    fun show(project: Project, run: TestoMutationRun) {
        val window = window(project)
        val content = window.contentManager.contents.firstOrNull { it.getUserData(RUN_KEY) === run } ?: add(project, run)
        window.contentManager.setSelectedContent(content, true)
        window.activate(null)
    }

    fun contentOf(project: Project, panel: TestoMutationPanel): Content? =
        ToolWindowManager.getInstance(project).getToolWindow(ID)?.contentManager?.contents?.firstOrNull { it.component === panel }

    fun openReport(project: Project, run: TestoMutationRun) {
        val report = run.htmlReport.takeIf(Files::isRegularFile) ?: return
        if (!TestoReportViewer.open(project, report, TestoBundle.message("infection.report.label"))) {
            BrowserUtil.browse(report.toUri())
        }
    }

    private fun window(project: Project): ToolWindow {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID)
            ?: error("The $ID tool window is not registered")
        window.isAvailable = true
        return window
    }
}

/** Contents are added per run by [TestoMutationToolWindow]; the window stays off the stripe until the first one. */
class TestoMutationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = false

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setToHideOnEmptyContent(true)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = Unit
}
