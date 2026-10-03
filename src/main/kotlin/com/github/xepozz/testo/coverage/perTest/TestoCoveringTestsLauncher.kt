package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.project.Project

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
        executorId: String = TestoConfiguration.COVERAGE_EXECUTOR_ID,
    ) {
        val mapper = TestoTestIdentityMapper.getInstance()
        val filters = tests.map { mapper.toFilterSelector(it) }.distinct().sorted()
        if (filters.isEmpty()) return
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return

        val factory = TestoPhp.getInstance().configurationFactory()
        val settings = RunManager.getInstance(project).createConfiguration(name, factory)
        val configuration = settings.configuration as? TestoConfiguration ?: return
        configuration.selection = configuration.selection.apply {
            rerunFilters = filters
            scope = TestoScope.CONFIGURATION_FILE
        }
        val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build() ?: return
        ExecutionManager.getInstance(project).restartRunProfile(environment)
    }

    /** The name such a run appears under — the tab title, and what the run archive files it as. */
    fun runName(subject: String, count: Int): String =
        TestoBundle.message("testo.coverage.covering.run.name", subject, count)
}
