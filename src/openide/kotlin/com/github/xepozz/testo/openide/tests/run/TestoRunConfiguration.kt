package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoConfigurationNames
import com.github.xepozz.testo.launch.TestoReportLocation
import com.github.xepozz.testo.launch.TestoRunContexts
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
import ru.openide.openphp.settings.PhpSettings
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality

/**
 * A Testo run configuration on OpenIDE. It keeps what to run as a [TestoRunSelection] and runs under the interpreter
 * profile it names, or the project's active one when it names none.
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
                testoTypes = testoTypes.toList().ifEmpty { TestoRunContexts.typesOf(testoType.orEmpty()) },
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
            testoTypes = value.testoTypes.toMutableList()
            testoType = ""
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
        get() = archivedEnvironment()?.displayName.orEmpty()

    override val interpreterKind: String
        get() = when (archivedEnvironment()?.locality) {
            PhpLaunchLocality.SameHost -> "local"
            is PhpLaunchLocality.Container -> "container"
            is PhpLaunchLocality.WslDistribution -> "wsl"
            null -> ""
        }

    private fun archivedEnvironment(): PhpLaunchEnvironment? =
        lastEnvironment ?: runCatching { launchEnvironment() }.getOrNull()

    /**
     * The launch environment of the interpreter this configuration names, else of the project's active profile. A
     * profile removed since is refused rather than replaced: the run would go somewhere the user did not choose.
     */
    internal fun launchEnvironment(): PhpLaunchEnvironment {
        val id = options.interpreterId?.takeIf { it.isNotBlank() } ?: return environmentProvider(project)
        return profileEnvironmentProvider(project, id) ?: throw ExecutionException(missingInterpreterMessage())
    }

    private fun missingInterpreterMessage(): String {
        val name = options.interpreterName?.takeIf { it.isNotBlank() } ?: options.interpreterId
        return "PHP interpreter '$name' no longer exists. Choose another one in the configuration, or the project default."
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
        val environment = try {
            launchEnvironment()
        } catch (e: ExecutionException) {
            throw RuntimeConfigurationError(e.message)
        }
        val launch = launch(environment)
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
     * the process started, its handler not yet. Off the EDT. [addition] is what a runner adds on top — the coverage
     * flags and the reports they write — decided with the launch at hand.
     */
    internal fun prepare(
        environment: PhpLaunchEnvironment = launchEnvironment(),
        phpArguments: List<String> = emptyList(),
        environmentVariables: Map<String, String> = emptyMap(),
        addition: (TestoLaunch) -> RunAddition = { RunAddition() },
        processHandler: (GeneralCommandLine) -> ProcessHandler = ::defaultProcessHandler,
    ): PreparedRun {
        val launch = ReadAction.compute<TestoLaunch, ExecutionException> { launch(environment) }
        val check = launch.checkBinary()
        if (check.refuses) throw ExecutionException(check.message)
        val logs = launch.logReports()
        val logReports = logs.reports
        val added = addition(launch)
        logs.unreachable?.let { TestoUnreachableReportsNotice.getInstance(project).tell(environment.displayName, name, it) }
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
        openIdeConsoleProperties(this, executor, archivedEnvironment() ?: environmentProvider(project)).also {
            it.workingDirectory = lastWorkingDirectory
            it.reportTargets = lastReportTargets
            it.toolEnvironment = lastToolEnvironment
        }

    companion object {
        /** The project default: the active interpreter profile. Tests put their own in. */
        @Volatile
        internal var environmentProvider: (Project) -> PhpLaunchEnvironment = { PhpLaunchEnvironment.of(it) }

        /** The launch environment of the interpreter profile with an id; null once it is removed. Tests put their own in. */
        @Volatile
        internal var profileEnvironmentProvider: (Project, String) -> PhpLaunchEnvironment? = { project, id ->
            PhpLaunchEnvironment.of(project, id)
        }

        /** The interpreter profiles a configuration can name, as id and name. Tests put their own in. */
        @Volatile
        internal var profilesProvider: (Project) -> List<Pair<String, String>> = { project ->
            PhpSettings.getInstance(project).allProfiles.map { it.id to it.name }
        }

        /** The name of the project's active profile, for the form's default entry; null when none is active. */
        @Volatile
        internal var activeProfileNameProvider: (Project) -> String? = { PhpSettings.getInstance(it).activeProfile?.name }

        internal fun defaultProcessHandler(command: GeneralCommandLine): ProcessHandler =
            KillableColoredProcessHandler(command).also { ProcessTerminatedListener.attach(it) }
    }
}
