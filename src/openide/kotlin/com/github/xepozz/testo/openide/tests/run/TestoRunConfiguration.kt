package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoConfigurationNames
import com.github.xepozz.testo.launch.TestoReportLocation
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.openIdeConsoleProperties
import com.github.xepozz.testo.openide.OpenIdeToolEnvironment
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.PathUtil
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality

/**
 * A Testo run configuration on OpenIDE. It keeps what to run as a [TestoRunSelection] and runs under the active
 * interpreter profile of the project, which it does not store: switching the profile switches every run.
 */
class TestoRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String? = null,
) : LocatableConfigurationBase<TestoRunConfigurationOptions>(project, factory, name), TestoConfiguration {

    public override fun getOptions(): TestoRunConfigurationOptions = super.getOptions() as TestoRunConfigurationOptions

    /** The failed tests a "Rerun Failed Tests" clone selects; never saved. */
    var rerunFilters: List<String> = emptyList()

    override var selection: TestoRunSelection
        get() = with(options) {
            TestoRunSelection(
                scope = scope,
                selectedType = selectedType,
                directoryPath = directoryPath,
                filePath = filePath,
                methodName = methodName,
                useAlternativeConfigurationFile = useAlternativeConfigurationFile,
                configurationFilePath = configurationFilePath,
                testRunnerOptions = testRunnerOptions,
                command = command ?: TestoRunConfigurationOptions.DEFAULT_COMMAND,
                testoType = testoType.orEmpty(),
                suites = suites.toList(),
                groups = groups.toList(),
                excludeGroups = excludeGroups.toList(),
                rerunFilters = rerunFilters,
                dataProviderIndex = dataProviderIndex,
                dataSetIndex = dataSetIndex,
                coverageDriver = coverageDriver,
                coverageClover = coverageClover,
                coverageCobertura = coverageCobertura,
                coverageXml = coverageXml,
                coverageLevel = coverageLevel ?: TestoRunSelection.COVERAGE_LEVEL_AUTO,
                coverageOptions = coverageOptions.orEmpty(),
                parallel = parallel,
                parallelTestingEnabled = parallelTestingEnabled,
                logHtml = logHtml,
                logJunit = logJunit,
                infectionScope = infectionScope ?: TestoRunSelection.INFECTION_SCOPE_COVERED,
                infectionGitDiffBase = infectionGitDiffBase.orEmpty(),
                infectionThreads = infectionThreads.orEmpty(),
                infectionOnlyCoveringTestCases = infectionOnlyCoveringTestCases,
                infectionWithUncovered = infectionWithUncovered,
                infectionTimeoutsAsEscaped = infectionTimeoutsAsEscaped,
                infectionMutators = infectionMutators.orEmpty(),
                infectionStaticAnalysisTool = infectionStaticAnalysisTool.orEmpty(),
                infectionOptions = infectionOptions.orEmpty(),
            )
        }
        set(value) = with(options) {
            scope = value.scope
            selectedType = value.selectedType
            directoryPath = value.directoryPath
            filePath = value.filePath
            methodName = value.methodName
            useAlternativeConfigurationFile = value.useAlternativeConfigurationFile
            configurationFilePath = value.configurationFilePath
            testRunnerOptions = value.testRunnerOptions
            command = value.command
            testoType = value.testoType
            suites = value.suites.toMutableList()
            groups = value.groups.toMutableList()
            excludeGroups = value.excludeGroups.toMutableList()
            rerunFilters = value.rerunFilters
            dataProviderIndex = value.dataProviderIndex
            dataSetIndex = value.dataSetIndex
            coverageDriver = value.coverageDriver
            coverageClover = value.coverageClover
            coverageCobertura = value.coverageCobertura
            coverageXml = value.coverageXml
            coverageLevel = value.coverageLevel
            coverageOptions = value.coverageOptions
            parallel = value.parallel
            parallelTestingEnabled = value.parallelTestingEnabled
            logHtml = value.logHtml
            logJunit = value.logJunit
            infectionScope = value.infectionScope
            infectionGitDiffBase = value.infectionGitDiffBase
            infectionThreads = value.infectionThreads
            infectionOnlyCoveringTestCases = value.infectionOnlyCoveringTestCases
            infectionWithUncovered = value.infectionWithUncovered
            infectionTimeoutsAsEscaped = value.infectionTimeoutsAsEscaped
            infectionMutators = value.infectionMutators
            infectionStaticAnalysisTool = value.infectionStaticAnalysisTool
            infectionOptions = value.infectionOptions
        }

    override val interpreterName: String
        get() = PhpLaunchEnvironment.of(project).displayName

    override val interpreterKind: String
        get() = when (PhpLaunchEnvironment.of(project).locality) {
            PhpLaunchLocality.SameHost -> "local"
            is PhpLaunchLocality.Container -> "container"
            is PhpLaunchLocality.WslDistribution -> "wsl"
        }

    /** Named after what it runs, the way PhpStorm names a test configuration, unless Testo has a better name. */
    override fun suggestedName(): String {
        val selection = selection
        TestoConfigurationNames.suggestedName(selection)?.let { return it }
        return when (selection.scope) {
            TestoScope.TYPE -> selection.selectedType.orEmpty()
            TestoScope.DIRECTORY -> PathUtil.getFileName(selection.directoryPath.orEmpty())
            TestoScope.FILE -> PathUtil.getFileName(selection.filePath.orEmpty())
            TestoScope.METHOD -> "${PathUtil.getFileName(selection.filePath.orEmpty())}::${selection.methodName.orEmpty()}"
            TestoScope.CONFIGURATION_FILE ->
                if (selection.useAlternativeConfigurationFile) PathUtil.getFileName(selection.configurationFilePath.orEmpty())
                else ""
        }
    }

    override fun getActionName(): String? {
        val selection = selection
        TestoConfigurationNames.qualifiedMethodTail(selection)?.let { return it }
        if (selection.scope == TestoScope.METHOD && !selection.methodName.isNullOrEmpty()) return selection.methodName
        return super.getActionName()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = TestoRunConfigurationEditor(project)

    /**
     * Refuses what cannot run anywhere and warns about the binary where the IDE can tell it is missing. Reads the
     * project's composer files, as the platform's check does elsewhere, under the read action it is called in.
     */
    override fun checkConfiguration() {
        val launch = launch(environmentProvider(project))
        launch.checkBinary().let { check ->
            val message = check.message ?: return@let
            if (check is BinaryCheck.NotFound) throw RuntimeConfigurationError(message)
            throw RuntimeConfigurationWarning(message)
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        TestoRunState(this, environment)

    internal fun launch(environment: PhpLaunchEnvironment) =
        TestoLaunch(project, selection, options, name, environment)

    /**
     * Everything a run needs before its console: the environment is taken, the binary checked, the command built and
     * the process created, not started. Off the EDT. [addition] is what a runner adds on top — the coverage flags and
     * the reports they write — decided with the launch at hand.
     */
    internal fun prepare(
        environment: PhpLaunchEnvironment = environmentProvider(project),
        phpArguments: List<String> = emptyList(),
        environmentVariables: Map<String, String> = emptyMap(),
        addition: (TestoLaunch) -> RunAddition = { RunAddition() },
        processHandler: (GeneralCommandLine) -> ProcessHandler = ::defaultProcessHandler,
    ): PreparedRun {
        val launch = ReadAction.compute<TestoLaunch, ExecutionException> { launch(environment) }
        val check = launch.checkBinary()
        if (check.refuses) throw ExecutionException(check.message)
        val logReports = launch.reportTargets()
        val added = addition(launch)
        val command = launch.commandLine(
            logReports,
            phpArguments,
            added.testoArguments,
            environmentVariables,
            added.reports.map { it.exposed },
        )
        lastEnvironment = environment
        lastWorkingDirectory = launch.workingDirectory
        lastReportTargets = logReports + added.reports
        lastToolEnvironment = OpenIdeToolEnvironment(this, environment)
        return PreparedRun(launch, processHandler(command), command)
    }

    /** What a runner adds to a run: Testo arguments and the reports they write. */
    internal class RunAddition(
        val testoArguments: List<String> = emptyList(),
        val reports: List<TestoLaunch.Report> = emptyList(),
    )

    internal class PreparedRun(val launch: TestoLaunch, val handler: ProcessHandler, val command: GeneralCommandLine)

    /** The environment the latest run was started in; the console reads that run's paths back through it. */
    @Volatile
    internal var lastEnvironment: PhpLaunchEnvironment? = null

    @Volatile
    private var lastToolEnvironment: com.github.xepozz.testo.php.TestoToolEnvironment? = null

    @Volatile
    private var lastWorkingDirectory: String? = null

    @Volatile
    private var lastReportTargets: List<TestoReportLocation> = emptyList()

    override fun createTestConsoleProperties(executor: Executor): SMTRunnerConsoleProperties =
        openIdeConsoleProperties(this, executor, lastEnvironment ?: environmentProvider(project)).also {
            it.workingDirectory = lastWorkingDirectory
            it.reportTargets = lastReportTargets
            it.toolEnvironment = lastToolEnvironment
        }

    companion object {
        /** Where a run takes its launch environment from: the active interpreter profile. Tests put their own in. */
        @Volatile
        internal var environmentProvider: (Project) -> PhpLaunchEnvironment = { PhpLaunchEnvironment.of(it) }

        internal fun defaultProcessHandler(command: GeneralCommandLine): ProcessHandler =
            KillableColoredProcessHandler(command).also { ProcessTerminatedListener.attach(it) }
    }
}
