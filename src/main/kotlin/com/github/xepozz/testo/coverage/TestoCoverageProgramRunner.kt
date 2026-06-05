package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.debug.xdebug.options.XdebugConfigurationOptionsManager
import com.jetbrains.php.phpunit.coverage.PhpCoverageRunner
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.run.PhpConfigurationOption
import com.jetbrains.php.run.PhpRunConfigurationHolder

open class TestoCoverageProgramRunner : PhpCoverageRunner() {
    companion object {
        const val EXECUTOR_ID: String = "Coverage"
        const val RUNNER_ID: String = "TestoCoverageRunner"
    }

    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == EXECUTOR_ID && profile is TestoRunConfiguration

    // Pass the IDE-managed report path to the CLI (mirrors PhpUnit's createCoverageArguments) so Testo writes the Clover
    // XML exactly where the IDE reads it back. `targetCoverage` is the remote-mapped path derived from
    // CoverageEnabledConfiguration.getCoverageFilePath() by PhpCoverageRunner. Testo exposes `--coverage-clover=<file>`
    // (Symfony Console, VALUE_REQUIRED). Fall back to the bare `--coverage` when no path is provided.
    override fun createCoverageArguments(targetCoverage: String?) =
        if (targetCoverage.isNullOrEmpty()) mutableListOf("--coverage")
        else mutableListOf("--coverage-clover=$targetCoverage")

    override fun getRunnerId(): String = RUNNER_ID

    override fun createState(
        env: ExecutionEnvironment,
        interpreter: PhpInterpreter,
        runConfigurationHolder: PhpRunConfigurationHolder<*>,
        coverageArguments: MutableList<String>,
        localCoverage: String,
        targetCoverage: String
    ): RunProfileState? {
        val runConfiguration = runConfigurationHolder.runConfiguration as TestoRunConfiguration

        val command = createTestoCoverageCommand(
            runConfiguration,
            interpreter,
            coverageArguments,
            localCoverage,
            targetCoverage,
        )

        runConfiguration.checkConfiguration()
        return runConfiguration.getState(env, command, null)
    }

    fun createTestoCoverageCommand(
        runConfiguration: TestoRunConfiguration,
        interpreter: PhpInterpreter,
        coverageArguments: List<String>,
        localCoverage: String,
        targetCoverage: String
    ): PhpCommandSettings {
        val command = runConfiguration.createCommand(
            interpreter,
            mutableMapOf<String, String>(),
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
            else -> throw IllegalArgumentException("Unsupported coverage engine $coverageEngine.")
        }
        command.addConfigurationOptions(options)
        setAdditionalMapping(localCoverage, targetCoverage, command)

        return command
    }
}
