package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.LineTotals
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.PhpFile

/**
 * Testo coverage on 100% public platform API — no `com.intellij.php.coverage.*` (internal, closed to third-party
 * plugins) and no deprecated `com.jetbrains.php.phpunit.coverage.*`. The report path is IDE-managed, so we pass it to
 * the CLI and read it back where the IDE expects it (see [TestoCoverageProgramRunner]).
 */
class TestoCoverageEnabledConfiguration(
    configuration: TestoRunConfiguration,
) : CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(TestoCoverageRunner::class.java)) {
    override fun coverageFileNameSeparator(): String = "@"
}

/**
 * Carries the parsed report's format-dependent side-data — the format (which decides the CLI flag and how the runner
 * reads the file) and whether it holds branch data.
 * Deletion is a no-op: the report is regenerated at the same IDE-managed path each run, so there is nothing to clean.
 */
class TestoCoverageSuite : BaseCoverageSuite {
    var format: CoverageFormat = CoverageFormat.CLOVER

    /**
     * Per-file line tallies keyed like the `ClassData` entries, for reports that state how many executable lines a file
     * has without saying which they are (coverage-xml). Counting the `ProjectData` lines would call every such file
     * fully covered, since only executed lines are in there.
     */
    var lineTotals: Map<String, LineTotals> = emptyMap()
        private set

    private var branchCoverage: Boolean = false

    constructor() : super()

    constructor(
        name: String,
        project: Project,
        coverageRunner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timeStamp: Long,
    ) : super(name, project, coverageRunner, fileProvider, timeStamp)

    fun applyParsed(hasBranches: Boolean, lineTotals: Map<String, LineTotals>) {
        this.branchCoverage = hasBranches
        this.lineTotals = lineTotals
    }

    override fun isBranchCoverage(): Boolean = branchCoverage

    override fun getCoverageEngine(): CoverageEngine = TestoCoverageEngine.INSTANCE

    override fun deleteCachedCoverageData() = Unit
}

class TestoCoverageEngine : CoverageEngine() {
    override fun getPresentableText(): String = "Testo"

    override fun isApplicableTo(conf: RunConfigurationBase<*>): Boolean = conf is TestoRunConfiguration

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>): CoverageEnabledConfiguration =
        TestoCoverageEnabledConfiguration(conf as TestoRunConfiguration)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
    ): CoverageSuite = TestoCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
        config: CoverageEnabledConfiguration,
    ): CoverageSuite? =
        if (config is TestoCoverageEnabledConfiguration) TestoCoverageSuite(name, project, runner, fileProvider, timestamp)
        else null

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite = TestoCoverageSuite()

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = TestoCoverageAnnotator.getInstance(project)

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean = psiFile is PhpFile

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean = true

    // Must equal the ClassData keys the runner stored (VirtualFile.getPath()): the editor gutter does an exact
    // getClassData(getQualifiedNames(file)) lookup. canonicalPath resolves symlinks and can diverge from the key.
    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> =
        sourceFile.virtualFile?.path?.let { setOf(it) } ?: emptySet()

    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension =
        TestoCoverageViewExtension(project, TestoCoverageAnnotator.getInstance(project), suiteBundle)

    companion object {
        val INSTANCE = TestoCoverageEngine()
    }
}
