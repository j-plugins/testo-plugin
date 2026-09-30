package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import java.awt.datatransfer.StringSelection
import java.nio.file.Files

/** Base of the *Mutations* tool window's actions: they act on the tab they are invoked from. */
abstract class TestoMutationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun panel(e: AnActionEvent): TestoMutationPanel? = e.getData(TestoMutationPanel.PANEL)

    final override fun update(e: AnActionEvent) {
        val panel = panel(e)
        e.presentation.isEnabled = panel != null && isEnabled(panel)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.let(::perform)
    }

    protected open fun isEnabled(panel: TestoMutationPanel): Boolean = true

    protected abstract fun perform(panel: TestoMutationPanel)
}

class TestoMutationRerunAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.Actions.Restart
    }

    override fun isEnabled(panel: TestoMutationPanel) = !panel.run.isBusy && panel.run.recipe != null

    override fun perform(panel: TestoMutationPanel) = TestoMutationService.getInstance(panel.project).restart(panel.run)
}

/** Each selected mutant alone, updated in place in this tab. */
class TestoMutationRerunSelectedAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.Actions.Rerun
    }

    override fun isEnabled(panel: TestoMutationPanel) =
        !panel.run.isBusy && panel.run.recipe != null && panel.selectedMutants().any { it.finished }

    override fun perform(panel: TestoMutationPanel) =
        TestoMutationService.getInstance(panel.project).rerun(panel.run, panel.selectedMutants())
}

/** Every escaped mutant alone, updated in place in this tab. */
class TestoMutationRerunEscapedAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.RunConfigurations.RerunFailedTests
    }

    private fun escaped(panel: TestoMutationPanel) = panel.run.mutants.filter { it.status == MutantStatus.ESCAPED }

    override fun isEnabled(panel: TestoMutationPanel) =
        !panel.run.isBusy && panel.run.recipe != null && escaped(panel).isNotEmpty()

    override fun perform(panel: TestoMutationPanel) =
        TestoMutationService.getInstance(panel.project).rerun(panel.run, escaped(panel))
}

class TestoMutationStopAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.Actions.Suspend
    }

    override fun isEnabled(panel: TestoMutationPanel) = panel.run.isBusy

    override fun perform(panel: TestoMutationPanel) = panel.run.stop()
}

class TestoMutationOpenReportAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.General.Web
    }

    override fun isEnabled(panel: TestoMutationPanel) = !panel.run.isRunning && Files.isRegularFile(panel.run.htmlReport)

    override fun perform(panel: TestoMutationPanel) = TestoMutationToolWindow.openReport(panel.project, panel.run)
}

class TestoMutationCopyIdAction : TestoMutationAction() {
    override fun isEnabled(panel: TestoMutationPanel) = panel.selectedMutants().isNotEmpty()

    override fun perform(panel: TestoMutationPanel) {
        val ids = panel.selectedMutants().joinToString("\n") { it.hash }
        CopyPasteManager.getInstance().setContents(StringSelection(ids))
    }
}

class TestoMutationEscapedOnlyAction : DumbAwareToggleAction() {
    init {
        templatePresentation.icon = TestoIcons.Status.FAILED
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = e.getData(TestoMutationPanel.PANEL)?.escapedOnly == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.getData(TestoMutationPanel.PANEL)?.escapedOnly = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.getData(TestoMutationPanel.PANEL) != null
    }
}

/** The tab's own pin, the same one its context menu has: a pinned tab stays when the next run of its Testo run starts. */
class TestoMutationPinAction : DumbAwareToggleAction() {
    init {
        templatePresentation.icon = AllIcons.General.Pin_tab
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun content(e: AnActionEvent) =
        e.getData(TestoMutationPanel.PANEL)?.let { TestoMutationToolWindow.contentOf(it.project, it) }

    override fun isSelected(e: AnActionEvent): Boolean = content(e)?.isPinned == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        content(e)?.isPinned = state
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = content(e) != null
    }
}
