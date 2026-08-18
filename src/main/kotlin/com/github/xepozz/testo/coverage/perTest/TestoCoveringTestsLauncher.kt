package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.project.Project
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

/**
 * Runs a set of tests read off the per-test coverage — the covering tests of a line, a declaration, a file or a whole
 * directory — as one Testo run.
 *
 * The set is passed as explicit `--filter` selectors, the same shape *Rerun Failed Tests* uses, on a throwaway
 * configuration that never reaches `RunManager`'s list. Its scope is reset to `ConfigurationFile` so nothing narrows
 * the filters away.
 */
internal object TestoCoveringTestsLauncher {

    /** Coverage by default: the point of running the covering tests is to see the coverage they produce now. */
    fun run(
        project: Project,
        tests: Collection<TestId>,
        name: String,
        executorId: String = TestoCoverageProgramRunner.EXECUTOR_ID,
    ) {
        val mapper = TestoTestIdentityMapper.getInstance()
        val filters = tests.map { mapper.toFilterSelector(it) }.distinct().sorted()
        if (filters.isEmpty()) return
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return

        val settings = RunManager.getInstance(project).createConfiguration(name, TestoRunConfigurationType.INSTANCE)
        val configuration = settings.configuration as? TestoRunConfiguration ?: return
        configuration.testoSettings.getTestoRunnerSettings().apply {
            rerunFilters = filters
            scope = PhpTestRunnerSettings.Scope.ConfigurationFile
        }
        val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build() ?: return
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    /** The name such a run appears under — the tab title, and what the run archive files it as. */
    fun runName(subject: String, count: Int): String =
        TestoBundle.message("testo.coverage.covering.run.name", subject, count)
}
