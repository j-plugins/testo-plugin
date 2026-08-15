package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.intellij.coverage.CoverageRunnerData
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.remote.RemoteSdkAdditionalData
import com.intellij.util.PathMappingSettings
import com.intellij.util.PathUtil
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.debug.xdebug.options.XdebugConfigurationOptionsManager
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.run.PhpConfigurationOption
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager

/**
 * Runs a Testo configuration under the Coverage executor. Extends the public [GenericProgramRunner] instead of the
 * internal `com.intellij.php.coverage.PhpCoverageRunner`: [doExecute] reproduces that base's Xdebug flow (resolve the
 * IDE-managed report path, build the command, attach the platform to the process so it loads coverage on termination
 * via [TestoCoverageRunner]) on public PHP execution API alone.
 */
open class TestoCoverageProgramRunner : GenericProgramRunner<RunnerSettings>() {
    companion object {
        const val EXECUTOR_ID: String = "Coverage"
        const val RUNNER_ID: String = "TestoCoverageRunner"
    }

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == EXECUTOR_ID && profile is TestoRunConfiguration

    // The Coverage executor needs CoverageRunnerData so the platform threads RunnerSettings through to attachToProcess.
    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    override fun doExecute(state: RunProfileState, env: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val runConfiguration = env.runProfile as? TestoRunConfiguration
            ?: throw ExecutionException("Coverage is not supported for the selected run profile.")
        val interpreter = runConfiguration.interpreter
            ?: throw ExecutionException(PhpCommandSettingsBuilder.getInterpreterNotFoundError())

        // Kept for its IDE-managed base path alone — loading no longer goes through CoverageHelper.
        val coverageConfiguration = CoverageEnabledConfiguration.getOrCreate(runConfiguration)
        val localCoverage = coverageConfiguration.coverageFilePath
        val settings = runConfiguration.testoSettings.getTestoRunnerSettings()
        val flags = coverageFlagLocalPaths(settings, localCoverage)
        val coverageArguments = when {
            // No base path (runner missing) or every report unchecked: a bare --coverage still makes any
            // testo.php-configured writer collect, and the announce path picks the reports up.
            flags.isEmpty() -> listOf("--coverage")
            else -> flags.map { (format, local) ->
                coverageFlagFor(format, toTargetPath(runConfiguration, interpreter, local))
            }
        }

        val command = createTestoCoverageCommand(
            runConfiguration,
            interpreter,
            coverageArguments,
            localCoverage,
            localCoverage?.takeIf { it.isNotEmpty() }?.let { toTargetPath(runConfiguration, interpreter, it) },
        )
        runConfiguration.checkConfiguration()

        val profileState = runConfiguration.getState(env, command, null) ?: return null
        val executionResult = profileState.execute(env.executor, this) ?: return null

        // The platform's CoverageHelper loads exactly one file; a Testo run can produce several reports (flags plus
        // testo.php writers), so termination triggers our own merged apply instead.
        val flagDataFiles = flagLocalDataFiles(flags)
        executionResult.processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                val props = (executionResult.executionConsole as? SMTRunnerConsoleView)?.properties
                    as? TestoConsoleProperties ?: return
                autoApplyCoverage(runConfiguration.project, props, flagDataFiles)
            }
        })
        return RunContentBuilder(executionResult, env).showRunContent(env.contentToReuse)
    }

    /** The enabled formats with the local file/directory each one writes, derived from the IDE-managed base path. */
    fun coverageFlagLocalPaths(settings: TestoRunnerSettings, localCoverage: String?): List<Pair<CoverageFormat, String>> {
        if (localCoverage.isNullOrEmpty()) return emptyList()
        val stem = localCoverage.removeSuffix(".xml")
        return buildList {
            if (settings.coverageClover) add(CoverageFormat.CLOVER to "$stem-clover.xml")
            if (settings.coverageCobertura) add(CoverageFormat.COBERTURA to "$stem-cobertura.xml")
            if (settings.coverageXml) add(CoverageFormat.COVERAGE_XML to "$stem-coverage-xml")
        }
    }

    fun coverageFlagFor(format: CoverageFormat, targetCoverage: String): String = when (format) {
        CoverageFormat.CLOVER -> "--coverage-clover=$targetCoverage"
        CoverageFormat.COBERTURA -> "--coverage-cobertura=$targetCoverage"
        CoverageFormat.COVERAGE_XML -> "--coverage-xml=$targetCoverage"
    }

    fun createTestoCoverageCommand(
        runConfiguration: TestoRunConfiguration,
        interpreter: PhpInterpreter,
        coverageArguments: List<String>,
        localCoverage: String?,
        targetCoverage: String?,
    ): PhpCommandSettings {
        val command = runConfiguration.createCommand(
            interpreter,
            mutableMapOf(),
            coverageArguments.toMutableList(),
            true,
        )

        val coverageEngine = runConfiguration.testoSettings.getTestoRunnerSettings().coverageEngine
        val options = when (coverageEngine) {
            CoverageEngine.XDEBUG -> XdebugConfigurationOptionsManager
                .getConfigurationOptionsProvider(runConfiguration.project, interpreter)
                .enableCoverage()
                .createXdebugConfigurations()

            CoverageEngine.PCOV -> listOf(PhpConfigurationOption("pcov.enabled", 1))
            else -> throw RuntimeConfigurationError("Unsupported Testo coverage engine: $coverageEngine.")
        }
        command.addConfigurationOptions(options)
        setAdditionalMapping(localCoverage, targetCoverage, command)

        return command
    }

    private fun setAdditionalMapping(localCoverage: String?, targetCoverage: String?, command: PhpCommandSettings) {
        if (!localCoverage.isNullOrEmpty() && !targetCoverage.isNullOrEmpty()) {
            command.setAdditionalMapping(
                PathMappingSettings.PathMapping(PathUtil.getParentPath(localCoverage), PathUtil.getParentPath(targetCoverage)),
            )
        }
    }

    // Local interpreter: the report path is the same on both sides. Remote: map it into the execution environment.
    private fun toTargetPath(runConfiguration: TestoRunConfiguration, interpreter: PhpInterpreter, localCoverage: String): String {
        val data = interpreter.phpSdkAdditionalData
        if (data is RemoteSdkAdditionalData) {
            PhpRemoteInterpreterManager.getInstance()?.let { manager ->
                return manager.createPathMappings(runConfiguration.project, data).convertToRemote(localCoverage)
            }
        }
        return localCoverage
    }
}
