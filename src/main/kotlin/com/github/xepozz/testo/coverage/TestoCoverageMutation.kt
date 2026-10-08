package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.infection.TestoInfectionArguments
import com.github.xepozz.testo.infection.TestoInfectionReports
import com.github.xepozz.testo.infection.TestoMutationReadiness
import com.github.xepozz.testo.infection.TestoMutationRecipe
import com.github.xepozz.testo.infection.TestoMutationService
import com.github.xepozz.testo.infection.mutationFilterFor
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.runs.restoreTestoConfiguration
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.Collections
import java.util.WeakHashMap

/**
 * A mutation run narrowed to a file or a directory, over the reports of the Testo run whose coverage is shown: the
 * Coverage view's *Mutate Selected* and the editor's and project view's *Mutate*.
 */
internal object TestoCoverageMutation {
    class Target(val recipe: TestoMutationRecipe, val filter: String, val name: String)

    private class Prepared(val recipe: TestoMutationRecipe, val sources: List<String>, val root: String?)

    // Once a bundle's run can be mutated it stays so; until its run.json says so, every update reads it again.
    private val prepared = Collections.synchronizedMap(WeakHashMap<CoverageSuitesBundle, Prepared>())

    /** The Testo bundle the Coverage view shows, if any. */
    fun shownBundle(project: Project): CoverageSuitesBundle? =
        CoverageDataManager.getInstance(project).activeSuites().firstOrNull { it.coverageEngine is TestoCoverageEngine }

    /** What mutating [file] from [bundle]'s run takes, or null when that run cannot be mutated or covers nothing there. */
    fun target(project: Project, bundle: CoverageSuitesBundle, file: VirtualFile): Target? {
        val ready = prepared[bundle] ?: prepare(project, bundle)?.also { prepared[bundle] = it } ?: return null
        val covered = bundle.coverageData?.classes?.keys.orEmpty()
        val relative = mutationFilterFor(file.path, file.isDirectory, covered, ready.sources) ?: return null
        val filter = relative.takeIf { it.isNotEmpty() }?.let { TestoInfectionArguments.pathFilter(ready.root, it) }.orEmpty()
        return Target(ready.recipe, filter, file.name)
    }

    fun isBusy(project: Project, target: Target): Boolean =
        TestoMutationService.getInstance(project).runFor(target.recipe.runDir)?.isBusy == true

    /** Call on the EDT. */
    fun start(project: Project, target: Target) {
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

    private fun prepare(project: Project, bundle: CoverageSuitesBundle): Prepared? {
        val runDir = bundle.suites.filterIsInstance<TestoCoverageSuite>().firstNotNullOfOrNull { it.runDir } ?: return null
        val manifest = TestoRunStore.getInstance(project).readManifest(runDir) ?: return null
        val ready = TestoInfectionReports.readiness(runDir, manifest) as? TestoMutationReadiness.Ready ?: return null
        val configuration = restoreTestoConfiguration(project, runDir, manifest)
        val saved = RunManager.getInstance(project)
            .findConfigurationByTypeAndName(TestoPhp.getInstance().configurationFactory().type, configuration.name)
            ?.configuration as? TestoConfiguration
        val sources = runCatching { TestoInfectionReports.coveredSourceFiles(ready.coverageXml) }.getOrDefault(emptyList())
        val root = runCatching { TestoInfectionReports.coverageRoot(ready.coverageXml) }.getOrNull()
        return Prepared(TestoMutationRecipe(configuration, runDir, ready, saved ?: configuration), sources, root)
    }
}

/** *Mutate* on a file or directory in the editor and the project view; shown only where the shown coverage covers it. */
class TestoMutateFileAction : DumbAwareAction(TestoIcons.MUTATION_RUN) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isVisible = target != null
        e.presentation.isEnabled = target != null && e.project?.let { TestoCoverageMutation.isBusy(it, target) } == false
        target?.let { e.presentation.text = TestoBundle.message("testo.coverage.view.mutate.named", it.name) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        target(e)?.let { TestoCoverageMutation.start(project, it) }
    }

    private fun target(e: AnActionEvent): TestoCoverageMutation.Target? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val bundle = TestoCoverageMutation.shownBundle(project) ?: return null
        return TestoCoverageMutation.target(project, bundle, file)
    }
}
