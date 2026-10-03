package com.github.xepozz.testo.openide.tests.actions

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer

/**
 * Reruns the failed tests of a run. Testo has no switch for that, so every failed leaf becomes an explicit `--filter`
 * selector, baked into a clone of the configuration: every runner — debug and coverage too — then builds the same
 * failed-only command.
 */
class TestoRerunFailedTestsAction(
    componentContainer: ComponentContainer,
    properties: SMTRunnerConsoleProperties,
) : AbstractRerunFailedTestsAction(componentContainer) {
    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? TestoRunConfiguration ?: return null
        val clone = rerunConfiguration(configuration, failedFilters(configuration.project)) ?: return null
        return object : MyRunProfile(clone) {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
                clone.getState(executor, environment)
        }
    }

    private fun failedFilters(project: Project): List<String> =
        getFailedTests(project)
            .asSequence()
            .filter { it.isLeaf }
            .mapNotNull { it.locationUrl }
            .mapNotNull(TestoRunTarget::filterOf)
            .distinct()
            .toList()

    companion object {
        /** A clone of [configuration] running exactly [filters]; its scope is cleared so it narrows nothing more. */
        internal fun rerunConfiguration(configuration: TestoRunConfiguration, filters: List<String>): TestoRunConfiguration? {
            if (filters.isEmpty()) return null
            val clone = configuration.clone() as TestoRunConfiguration
            clone.selection = clone.selection.apply {
                rerunFilters = filters
                scope = TestoScope.CONFIGURATION_FILE
            }
            return clone
        }
    }
}
