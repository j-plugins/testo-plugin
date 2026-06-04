package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.util.SmartList
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.config.interpreters.PhpSdkFileTransfer
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRerunFailedTestsAction(
    componentContainer: ComponentContainer,
    properties: SMTRunnerConsoleProperties?
) : AbstractRerunFailedTestsAction(componentContainer) {
    init {
        this.init(properties)
    }

    // Testo has no "rerun failed" switch, so each failed leaf is turned into an explicit `--filter` selector.
    private fun collectFailedFilters(project: Project): List<String> =
        getFailedTests(project)
            .asSequence()
            .filter { it.isLeaf }
            .mapNotNull { it.locationUrl }
            .mapNotNull(::locationUrlToFilter)
            .distinct()
            .toList()

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val profile = this.myConsoleProperties.configuration
        if (profile !is TestoRunConfiguration) {
            LOG.warn(
                TestoBundle.message(
                    "php.testo.run.configuration.rerun.incorrect.configuration",
                    profile.javaClass,
                )
            )
            return null
        }

        val filters = collectFailedFilters(profile.project)
        if (filters.isEmpty()) return null

        // Bake the selectors into a throwaway clone so every runner (incl. coverage, which needs a real
        // TestoRunConfiguration) rebuilds the same failed-subset command. Scope is cleared so it can't narrow them.
        val clone = profile.clone() as TestoRunConfiguration
        clone.testoSettings.getTestoRunnerSettings().apply {
            rerunFilters = filters
            scope = PhpTestRunnerSettings.Scope.ConfigurationFile
        }

        return object : MyRunProfile(clone) {
            @Throws(ExecutionException::class)
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
                val runConfiguration = this.peer as TestoRunConfiguration
                val project = runConfiguration.project
                val interpreter: PhpInterpreter = runConfiguration.interpreter ?: return null
                val baseFile = runConfiguration.getBaseFile(null, interpreter)
                val frameworkConfiguration = PhpTestFrameworkSettingsManager.getInstance(project)
                    .getOrCreateByInterpreter(TestoFrameworkType.INSTANCE, interpreter, baseFile, true)
                    ?: return null

                val command = runConfiguration.createCommand(
                    interpreter,
                    mutableMapOf(),
                    SmartList<String?>(),
                    frameworkConfiguration,
                    executor.id == DefaultDebugExecutor.EXECUTOR_ID,
                )
                PhpSdkFileTransfer
                    .getSdkFileTransfer(interpreter.phpSdkAdditionalData)
                    .updateCommand(command)
                return runConfiguration.getState(environment, command, null)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(TestoRerunFailedTestsAction::class.java)

        // `php_qn://<file>::\<Fqn>::<method>[ with data set #N]` -> `\Fqn::method` (a dataset reruns its whole method).
        internal fun locationUrlToFilter(locationUrl: String): String? =
            locationUrl
                .substringBefore(" with data set")
                .substringAfter("://")
                .substringAfter("::", "")
                .ifEmpty { null }
    }
}
