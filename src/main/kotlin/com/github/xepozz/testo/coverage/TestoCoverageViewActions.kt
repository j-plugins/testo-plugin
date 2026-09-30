package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.infection.TestoInfectionReports
import com.github.xepozz.testo.infection.TestoMutationReadiness
import com.github.xepozz.testo.infection.TestoMutationRecipe
import com.github.xepozz.testo.infection.TestoMutationService
import com.github.xepozz.testo.infection.mutationFilterFor
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.runs.restoreTestoConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.RunManager
import com.github.xepozz.testo.coverage.editor.TestoCoverageEditorHighlighter
import com.github.xepozz.testo.coverage.editor.TestoCoveringTestsGutter
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.TestoCoveringTestsLauncher
import com.github.xepozz.testo.coverage.perTest.testsUnder
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree

/**
 * The Testo additions to the Coverage view toolbar, plugged in through the one public seam the view offers —
 * `CoverageViewExtension.createExtraToolbarActions()` (`@ApiStatus.Experimental`, present on 252 and 262).
 *
 * Expand/Collapse are shared with the test tree's toolbar — see
 * [com.github.xepozz.testo.tests.console.TestoTreeExpandAction].
 */

/** Switches the Testo editor gutter stripes on and off without touching the suite or the view. */
internal class TestoCoverageHighlightToggleAction(private val project: Project) : ToggleAction(
    TestoBundle.message("testo.coverage.view.toggle.highlight"),
    null,
    AllIcons.Actions.Show,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        TestoCoverageEditorHighlighter.getInstance(project).highlightingEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) =
        TestoCoverageEditorHighlighter.getInstance(project).setHighlightingEnabled(state)
}

/** Switches the *Run covering tests* gutter icons on methods, functions and classes on and off. */
internal class TestoCoveringTestsGutterToggleAction(private val project: Project) : ToggleAction(
    TestoBundle.message("testo.coverage.view.toggle.gutters"),
    null,
    AllIcons.Toolwindows.ToolWindowRunWithCoverage,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean = TestoCoveringTestsGutter.getInstance(project).enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        TestoCoveringTestsGutter.getInstance(project).enabled = state
    }
}

/**
 * *Select Opened File* — the working half of the platform's *Always select opened element*, which does nothing in a
 * file-based coverage view (see [TestoCoverageSelectOpenedFile]).
 *
 * The update pass is also where the view's tree is handed over: the toolbar's target component is the coverage table,
 * and the service that follows the editor has no other way to reach it.
 */
internal class TestoSelectOpenedFileAction(private val project: Project) : ToggleAction(
    TestoBundle.message("testo.coverage.view.select.opened"),
    null,
    AllIcons.General.AutoscrollFromSource,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? JComponent ?: return
        UIUtil.findComponentOfType(component, JTree::class.java)
            ?.let { TestoCoverageSelectOpenedFile.getInstance(project).rememberTree(it) }
    }

    override fun isSelected(e: AnActionEvent): Boolean = TestoCoverageSelectOpenedFile.getInstance(project).enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        TestoCoverageSelectOpenedFile.getInstance(project).enabled = state
    }
}

/**
 * Runs the tests covering the selected row — a file's own, a directory's whole subtree.
 *
 * The selection is read as `CommonDataKeys.NAVIGATABLE`, which is what the view publishes for the selected node; the
 * tree's context menu holds `EditSource` and nothing else, and is built inline with no id to extend.
 */
internal class TestoRunCoveringTestsAction(private val project: Project) : AnAction(
    TestoBundle.message("testo.coverage.view.run.covering"),
    null,
    // Not the tool window icon the gutter toggle beside it wears — two buttons on one toolbar must not look alike.
    AllIcons.General.RunWithCoverage,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val tests = selectedTests(e)
        e.presentation.isEnabled = tests.isNotEmpty()
        e.presentation.text = when {
            tests.isEmpty() -> TestoBundle.message("testo.coverage.view.run.covering")
            else -> TestoBundle.message("testo.coverage.view.run.covering.count", tests.size)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val item = selectedItem(e) ?: return
        val tests = selectedTests(e)
        if (tests.isEmpty()) return
        TestoCoveringTestsLauncher.run(project, tests, TestoCoveringTestsLauncher.runName(item.name, tests.size))
    }

    private fun selectedTests(e: AnActionEvent): Set<TestId> {
        val item = selectedItem(e) ?: return emptySet()
        val file = item.virtualFile ?: return emptySet()
        return TestoCoverageByTestIndex.getInstance(project).data().testsUnder(file.path, file.isDirectory)
    }

    private fun selectedItem(e: AnActionEvent): PsiFileSystemItem? =
        ((e.getData(CommonDataKeys.NAVIGATABLE) as? AbstractTreeNode<*>)?.value) as? PsiFileSystemItem
}

/**
 * Mutates the selected row alone — a file, or a directory's whole subtree — over the reports of the Testo run the view
 * shows. Read off the selection like [TestoRunCoveringTestsAction], for the same reason.
 */
internal class TestoMutateSelectionAction(
    private val project: Project,
    private val bundle: CoverageSuitesBundle,
) : AnAction(TestoBundle.message("testo.coverage.view.mutate"), null, TestoIcons.MUTATION_RUN), DumbAware {
    // Kept once the run can be mutated; until its run.json says so, every update reads it again.
    @Volatile
    private var recipe: TestoMutationRecipe? = null

    @Volatile
    private var sources: List<String> = emptyList()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        val busy = target != null && TestoMutationService.getInstance(project).runFor(target.recipe.runDir)?.isBusy == true
        e.presentation.isEnabled = target != null && !busy
        e.presentation.text = target?.let { TestoBundle.message("testo.coverage.view.mutate.named", it.name) }
            ?: TestoBundle.message("testo.coverage.view.mutate")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = target(e) ?: return
        val recipe = target.recipe
        val scoped = target.filter.isNotEmpty()
        TestoMutationService.getInstance(project).start(
            TestoMutationRecipe(
                recipe.configuration,
                recipe.runDir,
                recipe.ready,
                recipe.optionsFrom,
                filter = target.filter.takeIf { scoped },
                scopeName = target.name.takeIf { scoped },
            )
        )
    }

    private class Target(val recipe: TestoMutationRecipe, val filter: String, val name: String)

    private fun target(e: AnActionEvent): Target? {
        val recipe = recipe() ?: return null
        val item = ((e.getData(CommonDataKeys.NAVIGATABLE) as? AbstractTreeNode<*>)?.value) as? PsiFileSystemItem ?: return null
        val file = item.virtualFile ?: return null
        val covered = bundle.coverageData?.classes?.keys.orEmpty()
        val filter = mutationFilterFor(file.path, file.isDirectory, covered, sources) ?: return null
        return Target(recipe, filter, item.name)
    }

    private fun recipe(): TestoMutationRecipe? {
        recipe?.let { return it }
        val runDir = bundle.suites.filterIsInstance<TestoCoverageSuite>().firstNotNullOfOrNull { it.runDir } ?: return null
        val manifest = TestoRunStore.getInstance(project).readManifest(runDir) ?: return null
        val ready = TestoInfectionReports.readiness(runDir, manifest) as? TestoMutationReadiness.Ready ?: return null
        val configuration = restoreTestoConfiguration(project, runDir, manifest)
        val saved = RunManager.getInstance(project)
            .findConfigurationByTypeAndName(TestoRunConfigurationType.INSTANCE, configuration.name)
            ?.configuration as? TestoRunConfiguration
        sources = runCatching { TestoInfectionReports.coveredSourceFiles(ready.coverageXml) }.getOrDefault(emptyList())
        return TestoMutationRecipe(configuration, runDir, ready, saved ?: configuration).also { recipe = it }
    }
}

/** Non-clickable chips naming the report formats merged into the shown bundle — one per distinct format. */
internal class TestoCoverageFormatBadgesAction(
    private val bundle: CoverageSuitesBundle,
) : AnAction(), CustomComponentAction, RightAlignedToolbarAction, DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        panel.isOpaque = false
        bundle.suites.filterIsInstance<TestoCoverageSuite>()
            .map { it.format.id }
            .distinct()
            .forEach { panel.add(badge(it)) }
        return panel
    }

    private fun badge(text: String): JComponent = JBLabel(text).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.compound(
            RoundedLineBorder(JBColor.border(), JBUI.scale(10)),
            JBUI.Borders.empty(1, 6),
        )
    }
}
