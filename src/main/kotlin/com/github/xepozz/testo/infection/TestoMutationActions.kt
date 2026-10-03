package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TestoCoveringTestsLauncher
import com.github.xepozz.testo.coverage.perTest.navigateToTest
import com.github.xepozz.testo.tests.console.TestoReportIcons
import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import javax.swing.Icon
import kotlin.reflect.KMutableProperty0

/** Base of the *Mutations* tool window's actions: they act on the tab they are invoked from. */
abstract class TestoMutationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun panel(e: AnActionEvent): TestoMutationPanel? = e.getData(TestoMutationPanel.PANEL)

    final override fun update(e: AnActionEvent) {
        val panel = panel(e)
        e.presentation.isEnabled = panel != null && isEnabled(panel)
        panel?.let { updatePresentation(e, it) }
    }

    protected open fun updatePresentation(e: AnActionEvent, panel: TestoMutationPanel) = Unit

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

/** The selected mutants, and those under a selected file or group, updated in place in this tab. */
class TestoMutationRerunSelectedAction : TestoMutationAction() {
    init {
        templatePresentation.icon = AllIcons.Actions.Rerun
    }

    override fun isEnabled(panel: TestoMutationPanel) =
        !panel.run.isBusy && panel.run.recipe != null && panel.selectedForRerun().any { it.finished }

    override fun perform(panel: TestoMutationPanel) =
        TestoMutationService.getInstance(panel.project).rerun(panel.run, panel.selectedForRerun())
}

/** Writes the selected mutant into its file, or puts the original back where it is written: whichever the file reads. */
class TestoMutationApplyAction : TestoMutationAction() {
    override fun isEnabled(panel: TestoMutationPanel): Boolean {
        val mutant = panel.selectedMutants().singleOrNull() ?: return false
        return TestoMutationApply.isApplied(panel.run, mutant) || TestoMutationApply.canApply(panel.run, mutant)
    }

    override fun updatePresentation(e: AnActionEvent, panel: TestoMutationPanel) {
        val applied = panel.selectedMutants().singleOrNull()?.let { TestoMutationApply.isApplied(panel.run, it) } == true
        e.presentation.text = TestoBundle.message(if (applied) "infection.revert.action" else "infection.apply.action")
        e.presentation.icon = if (applied) AllIcons.Actions.Rollback else AllIcons.Actions.Edit
    }

    override fun perform(panel: TestoMutationPanel) {
        val mutant = panel.selectedMutants().singleOrNull() ?: return
        if (TestoMutationApply.isApplied(panel.run, mutant)) TestoMutationApply.revert(panel.project, panel.run, mutant)
        else TestoMutationApply.apply(panel.project, panel.run, mutant)
    }
}

class TestoMutationIgnoreAction : TestoMutationAction() {
    override fun isEnabled(panel: TestoMutationPanel): Boolean {
        val mutant = panel.selectedMutants().singleOrNull() ?: return false
        return TestoMutationIgnore.canIgnore(panel.project, panel.run, mutant)
    }

    override fun perform(panel: TestoMutationPanel) {
        panel.selectedMutants().singleOrNull()?.let { TestoMutationIgnore.ignore(panel.project, panel.run, it) }
    }
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

    override fun isEnabled(panel: TestoMutationPanel) = panel.run.isRunning || panel.run.rerunning || panel.run.stopper != null

    override fun perform(panel: TestoMutationPanel) = panel.run.stop()
}

/** Opens the report in a WebView tab, like a click on it does; its dropdown opens it in the browser or copies its path. */
class TestoMutationOpenReportAction : SplitButtonAction(
    DefaultActionGroup(
        TestoMutationReportAction(TestoBundle.message("testo.report.open.webview"), AllIcons.Actions.Preview) { panel ->
            TestoMutationToolWindow.openReport(panel.project, panel.run)
        },
        TestoMutationReportAction(TestoBundle.message("testo.report.open.browser"), AllIcons.Nodes.PpWeb) { panel ->
            BrowserUtil.browse(panel.run.htmlReport.toUri())
        },
        TestoMutationReportAction(TestoBundle.message("testo.report.copy.path"), AllIcons.Actions.Copy) { panel ->
            CopyPasteManager.getInstance().setContents(StringSelection(panel.run.htmlReport.toString()))
        },
    )
) {
    private val main = TestoMutationReportAction(null, TestoReportIcons.READY) { panel ->
        TestoMutationToolWindow.openReport(panel.project, panel.run)
    }

    init {
        templatePresentation.icon = TestoReportIcons.READY
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun useDynamicSplitButton(): Boolean = false

    override fun getMainAction(e: AnActionEvent): AnAction = main

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.getData(TestoMutationPanel.PANEL)?.let(::hasReport) == true
    }
}

private fun hasReport(panel: TestoMutationPanel) = !panel.run.isRunning && Files.isRegularFile(panel.run.htmlReport)

private class TestoMutationReportAction(
    text: String?,
    icon: Icon,
    private val open: (TestoMutationPanel) -> Unit,
) : TestoMutationAction() {
    init {
        text?.let { templatePresentation.text = it }
        templatePresentation.icon = icon
    }

    override fun isEnabled(panel: TestoMutationPanel) = hasReport(panel)

    override fun perform(panel: TestoMutationPanel) = open(panel)
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

/** Group By: under their file, their mutator or their status. */
class TestoMutationGroupByGroup : ActionGroup(), DumbAware {
    init {
        templatePresentation.icon = AllIcons.Actions.GroupBy
        templatePresentation.isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(TestoMutationPanel.PANEL) != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        TestoMutationGrouping.entries.map { grouping -> GroupingToggle(grouping) }.toTypedArray()

    private class GroupingToggle(private val grouping: TestoMutationGrouping) : DumbAwareToggleAction(grouping.label) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = e.getData(TestoMutationPanel.PANEL)?.grouping == grouping

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) e.getData(TestoMutationPanel.PANEL)?.grouping = grouping
        }
    }
}

/** Which mutators' mutants the tree shows: one toggle per mutator the run has, the popup staying open between them. */
class TestoMutationMutatorFilterGroup : ActionGroup(), DumbAware {
    init {
        templatePresentation.icon = AllIcons.General.Filter
        templatePresentation.isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(TestoMutationPanel.PANEL)?.run?.mutants?.isNotEmpty() == true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val panel = e?.getData(TestoMutationPanel.PANEL) ?: return emptyArray()
        val mutators = panel.run.mutants.map { it.mutator }.distinct().sorted()
        return buildList {
            add(ShowAll())
            add(Separator.getInstance())
            mutators.forEach { add(MutatorToggle(it)) }
        }.toTypedArray()
    }

    private class MutatorToggle(private val mutator: String) : DumbAwareToggleAction(mutator) {
        init {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            e.getData(TestoMutationPanel.PANEL)?.hiddenMutators?.contains(mutator) == false

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val panel = e.getData(TestoMutationPanel.PANEL) ?: return
            panel.hiddenMutators = if (state) panel.hiddenMutators - mutator else panel.hiddenMutators + mutator
        }
    }

    private class ShowAll : DumbAwareAction(TestoBundle.message("infection.filter.all")) {
        init {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.getData(TestoMutationPanel.PANEL)?.hiddenMutators?.isNotEmpty() == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            e.getData(TestoMutationPanel.PANEL)?.hiddenMutators = emptySet()
        }
    }
}

/**
 * The selected mutant's tests: the ones that killed it and the ones that ran over its code, each opening the test,
 * and a run of the covering ones — the tests an escaped mutant says to strengthen.
 */
class TestoMutationTestsGroup : ActionGroup(), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
    }

    // Children read the run's coverage report, which the background thread may do.
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = mutant(e) != null
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val panel = e?.getData(TestoMutationPanel.PANEL) ?: return emptyArray()
        val mutant = mutant(e) ?: return emptyArray()
        val tests = TestoMutationService.getInstance(panel.project).testsOf(panel.run, mutant)
        return buildList {
            if (tests.killing.isNotEmpty()) {
                add(Separator.create(TestoBundle.message("infection.tests.killedBy")))
                tests.killing.forEach { add(GoToTest(it)) }
            }
            if (tests.covering.isNotEmpty()) {
                add(Separator.create(TestoBundle.message("infection.tests.coveredBy")))
                tests.covering.forEach { add(GoToTest(it)) }
                add(Separator.getInstance())
                add(RunCovering(tests.covering, mutant))
            }
            if (isEmpty()) add(Nothing())
        }.toTypedArray()
    }

    private fun mutant(e: AnActionEvent): Mutant? = e.getData(TestoMutationPanel.SELECTED_MUTANTS)?.singleOrNull()

    private class GoToTest(private val test: TestId) : DumbAwareAction("${test.fqcn}::${test.method}", null, AllIcons.Nodes.Method) {
        override fun actionPerformed(e: AnActionEvent) {
            e.project?.let { navigateToTest(it, test) }
        }
    }

    private class RunCovering(private val tests: List<TestId>, private val mutant: Mutant) :
        DumbAwareAction(TestoBundle.message("infection.tests.runCovering", tests.size), null, AllIcons.Actions.RunAll) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            TestoCoveringTestsLauncher.run(
                project,
                tests.toSet(),
                TestoCoveringTestsLauncher.runName("${mutant.mutator} · ${mutant.file.name}", tests.size),
            )
        }
    }

    private class Nothing : DumbAwareAction(TestoBundle.message("infection.tests.none")) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}

/** The toolbar's right-edge menu: which parts under the tree the tabs show. */
class TestoMutationViewOptionsGroup : DefaultActionGroup(), RightAlignedToolbarAction, DumbAware {
    init {
        templatePresentation.text = TestoBundle.message("infection.view.options")
        templatePresentation.icon = AllIcons.General.Menu
        templatePresentation.isPopupGroup = true
        add(DetailsToggle(TestoBundle.message("infection.view.diff"), TestoMutationDetails::showDiff))
        add(DetailsToggle(TestoBundle.message("infection.view.output"), TestoMutationDetails::showOutput))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private class DetailsToggle(text: String, private val shown: KMutableProperty0<Boolean>) : DumbAwareToggleAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = shown.get()

        override fun setSelected(e: AnActionEvent, state: Boolean) = shown.set(state)
    }
}

/**
 * The mutants in the editor: gutter marks and escaped-code underlines, for the run of the selected tab. The button
 * switches them on and off; its dropdown picks the statuses they show.
 */
class TestoMutationEditorMarksAction : SplitButtonAction(
    DefaultActionGroup(TestoMutationEditorMarks.STATUSES.map(::EditorMarksStatusToggle))
) {
    private val main = EditorMarksToggle()

    init {
        templatePresentation.icon = TestoIcons.MUTATION
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun useDynamicSplitButton(): Boolean = false

    override fun getMainAction(e: AnActionEvent): AnAction = main
}

private class EditorMarksToggle : DumbAwareToggleAction(
    TestoBundle.message("action.Testo.Mutations.EditorMarks.text"),
    TestoBundle.message("action.Testo.Mutations.EditorMarks.description"),
    TestoIcons.MUTATION,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { TestoMutationEditorMarks.getInstance(it).enabled } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.let { TestoMutationEditorMarks.getInstance(it).enabled = state }
    }
}

private class EditorMarksStatusToggle(private val status: MutantStatus) : DumbAwareToggleAction() {
    init {
        templatePresentation.setText { status.label }
        templatePresentation.icon = status.icon
        templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { status !in TestoMutationEditorMarks.getInstance(it).hiddenStatuses } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val marks = e.project?.let(TestoMutationEditorMarks::getInstance) ?: return
        marks.hiddenStatuses = if (state) marks.hiddenStatuses - status else marks.hiddenStatuses + status
    }
}
