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
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.nio.file.Files
import java.nio.file.Path

/**
 * The *Mutations* tool window, one tab per mutated Testo run. Made available on first use, so a project that never
 * runs Infection never gets the stripe button.
 */
internal object TestoMutationToolWindow {
    const val ID = "Mutations"

    private val RUN_KEY = Key.create<TestoMutationRun>("testo.mutation.run")

    /**
     * Opens the window on [run]'s tab without taking the focus. A rerun of the same Testo run takes over the tab its last
     * run had, unless that tab is pinned: a pinned tab keeps its run and cannot be closed until unpinned.
     */
    fun add(project: Project, run: TestoMutationRun, title: String? = null): Content {
        val window = window(project)
        val manager = window.contentManager
        val panel = TestoMutationPanel(project, run)
        val content = ContentFactory.getInstance().createContent(panel, title ?: title(manager.contents, run), false).apply {
            putUserData(RUN_KEY, run)
            isCloseable = true
            isPinnable = true
            addPropertyChangeListener { event ->
                if (event.propertyName == Content.PROP_PINNED) isCloseable = !isPinned
            }
            // The files stay: they belong to the Testo run's archive. A run still going stops with its tab.
            setDisposer {
                Disposer.dispose(panel)
                if (run.isBusy) run.stop()
            }
            preferredFocusableComponent = panel.preferredFocus
        }
        // Never one still running: closing its tab would stop it.
        val previous = manager.contents.firstOrNull { content ->
            val shown = content.getUserData(RUN_KEY)
            !content.isPinned && shown?.sourceRunDir == run.sourceRunDir && !shown.isBusy
        }
        if (previous != null) {
            val index = manager.getIndexOfContent(previous)
            manager.addContent(content, index)
            manager.removeContent(previous, true)
        } else {
            manager.addContent(content)
        }
        manager.setSelectedContent(content)
        window.show()
        TestoMutationEditorMarks.getInstance(project).refresh()
        return content
    }

    // Pinned tabs of the same Testo run stay beside the new one, which is numbered to tell them apart.
    private fun title(contents: Array<Content>, run: TestoMutationRun): String {
        val pinned = contents.count { it.isPinned && it.getUserData(RUN_KEY)?.sourceRunDir == run.sourceRunDir }
        return if (pinned == 0) run.title else "${run.title} (${pinned + 1})"
    }

    fun show(project: Project, run: TestoMutationRun, title: String? = null) {
        val window = window(project)
        val content = contentOf(window, run.workDir) ?: add(project, run, title)
        window.contentManager.setSelectedContent(content, true)
        window.activate(null)
    }

    /** Brings up the tab of the mutation run archived in [workDir], if one is open. */
    fun select(project: Project, workDir: Path): Boolean {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return false
        val content = contentOf(window, workDir) ?: return false
        window.contentManager.setSelectedContent(content, true)
        window.activate(null)
        return true
    }

    private fun contentOf(window: ToolWindow, workDir: Path): Content? =
        window.contentManager.contents.firstOrNull { it.getUserData(RUN_KEY)?.workDir == workDir }

    fun reveal(project: Project, run: TestoMutationRun, mutant: Mutant) {
        show(project, run)
        val window = window(project)
        (contentOf(window, run.workDir)?.component as? TestoMutationPanel)?.select(mutant)
    }

    /** Call on the EDT. A tab being closed is no longer the selected one, though the manager may still answer it. */
    fun selectedRun(window: ToolWindow, event: ContentManagerEvent): TestoMutationRun? =
        window.contentManager.selectedContent
            ?.takeUnless { event.operation == ContentManagerEvent.ContentOperation.remove && it === event.content }
            ?.getUserData(RUN_KEY)

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
// Without it the compiler bridges the interface's defaults into the class, and on 252 three of them are internal API.
@JvmDefaultWithoutCompatibility
class TestoMutationToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = false

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setToHideOnEmptyContent(true)
        // The editor marks follow the selected tab.
        toolWindow.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                TestoMutationService.getInstance(toolWindow.project).selected = TestoMutationToolWindow.selectedRun(toolWindow, event)
                TestoMutationEditorMarks.getInstance(toolWindow.project).refresh()
            }
        })
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = Unit
}
