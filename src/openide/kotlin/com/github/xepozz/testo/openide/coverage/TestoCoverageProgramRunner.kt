package com.github.xepozz.testo.openide.coverage

import com.github.xepozz.testo.coverage.autoApplyCoverage
import com.github.xepozz.testo.coverage.flagLocalDataFiles
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoCoverageArguments
import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.openide.tests.run.TestoLaunch
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunState
import com.github.xepozz.testo.openide.tests.run.inBackgroundThenOnEdt
import com.github.xepozz.testo.openide.tests.run.testoConfigurationOf
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.coverage.CoverageRunnerData
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.concurrency.Promise
import java.io.File
import java.nio.file.Path

/**
 * Runs a Testo configuration under the Coverage executor, for Testo's own coverage engine. The reports go where the
 * IDE manages them, made visible to the launch environment; once the process ends, every report is loaded and merged
 * by the core.
 */
open class TestoCoverageProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "TestoCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == TestoConfiguration.COVERAGE_EXECUTOR_ID && testoConfigurationOf(profile) != null

    // The Coverage executor threads the runner settings through CoverageRunnerData.
    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = testoConfigurationOf(environment.runProfile)?.let(::configurationForRun)
            ?: throw ExecutionException("Not a Testo run configuration: ${environment.runProfile.name}")
        val localCoverage = CoverageEnabledConfiguration.getOrCreate(configuration).coverageFilePath
        return inBackgroundThenOnEdt(
            prepare = { prepare(configuration, localCoverage) },
            show = { covered -> show(configuration, environment, covered) },
            abandon = { covered -> covered.run.handler.destroyProcess() },
        )
    }

    internal class Covered(
        val run: TestoRunConfiguration.PreparedRun,
        val flags: List<Pair<CoverageFormat, String>>,
        /** Set when the module probe could not tell whether the driver's extension is loaded. */
        val unverifiedExtension: String?,
    )

    protected open fun configurationForRun(configuration: TestoRunConfiguration): TestoRunConfiguration = configuration

    protected open fun started(env: ExecutionEnvironment, properties: TestoConsoleProperties) = Unit

    private fun show(configuration: TestoRunConfiguration, environment: ExecutionEnvironment, covered: Covered): RunContentDescriptor? {
        val project = configuration.project
        val result = TestoRunState(configuration, environment).show(covered.run)
        val console = result.executionConsole as SMTRunnerConsoleView
        val properties = console.properties as TestoConsoleProperties
        started(environment, properties)
        val flagFiles = flagLocalDataFiles(covered.flags)
        // The flags' own reports win the one-per-format choice over a report some testo.php writer put elsewhere.
        properties.coverageFlagPaths = flagFiles
        covered.unverifiedExtension?.let {
            console.print("$it\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        result.processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    properties.copyReportsToLocal()
                    autoApplyCoverage(project, properties, flagFiles)
                }
            }
        })
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    companion object {
        /**
         * The coverage run of [configuration]: the driver switched on, the report flags pointed at [localCoverage]'s
         * siblings as the environment sees them. Refuses before start what cannot collect anything: PHPDBG, and a driver
         * whose extension the environment is known to lack. Off the EDT: the module probe runs a process.
         */
        internal fun prepare(
            configuration: TestoRunConfiguration,
            localCoverage: String?,
            processHandler: (GeneralCommandLine) -> ProcessHandler = TestoRunConfiguration::defaultProcessHandler,
        ): Covered {
            val selection = configuration.selection
            val driver = selection.coverageDriver
            if (driver == TestoCoverageDriver.PHPDBG) {
                throw ExecutionException("Cannot collect coverage with PHPDBG on OpenIDE: runs start php itself. Choose Xdebug or PCOV.")
            }
            val flags = TestoCoverageArguments.flagLocalPaths(selection, localCoverage)
            var unverified: String? = null
            val addition = { launch: TestoLaunch ->
                unverified = checkExtension(driver, launch)
                val reports = flags.map { (_, local) -> launch.report(Path.of(local)) }
                TestoRunConfiguration.RunAddition(
                    TestoCoverageArguments.reportArguments(flags, reports.map { it.path }) +
                        TestoCoverageArguments.extraArguments(selection),
                    reports,
                )
            }
            val run = configuration.prepare(
                phpArguments = phpArguments(driver),
                environmentVariables = variables(driver),
                addition = addition,
                processHandler = processHandler,
            )
            return Covered(run, flags, unverified)
        }

        private fun phpArguments(driver: TestoCoverageDriver): List<String> =
            if (driver == TestoCoverageDriver.PCOV) listOf("-dpcov.enabled=1") else emptyList()

        private fun variables(driver: TestoCoverageDriver): Map<String, String> =
            if (driver == TestoCoverageDriver.XDEBUG) mapOf("XDEBUG_MODE" to "coverage") else emptyMap()

        /**
         * Whether the driver's extension is loaded where the process runs: refuses when the module list says it is
         * not, answers a warning when there is no module list — the probe failed, which says nothing either way.
         */
        private fun checkExtension(driver: TestoCoverageDriver, launch: TestoLaunch): String? {
            val (extension, name) = when (driver) {
                TestoCoverageDriver.PCOV -> "pcov" to "PCOV"
                else -> "xdebug" to "Xdebug"
            }
            val modules = launch.environment.modules(File(launch.workingDirectory))
            if (modules.isEmpty()) {
                return "Testo: could not tell whether $name is loaded in ${launch.environment.displayName}; " +
                    "without it the run writes no coverage."
            }
            if (extension !in modules) {
                throw ExecutionException(
                    "Cannot collect coverage: $name is not loaded in the PHP of ${launch.environment.displayName}. " +
                        "Enable the $extension extension there, or choose the other engine in the configuration.",
                )
            }
            return null
        }
    }
}
