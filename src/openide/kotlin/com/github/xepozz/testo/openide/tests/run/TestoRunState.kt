package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.openide.tests.actions.TestoRerunFailedTestsAction
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

/**
 * A run of a Testo configuration: the process, and the console with Testo's results tree attached to it. Testo's own
 * runners prepare the process off the EDT and call [show] on it; any other runner gets both from [execute].
 */
internal class TestoRunState(
    private val configuration: TestoRunConfiguration,
    private val environment: ExecutionEnvironment,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult = show(configuration.prepare())

    /** The console for a [prepared] run, on the EDT. The platform starts the process once the content is shown. */
    fun show(prepared: TestoRunConfiguration.PreparedRun): ExecutionResult {
        val properties = configuration.createTestConsoleProperties(environment.executor) as TestoConsoleProperties
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
            properties.testFrameworkName,
            prepared.handler,
            properties,
        ) as SMTRunnerConsoleView
        // The platform hands the results model to the tree's own toolbar only; a restart action reads it from here.
        val rerunFailed = TestoRerunFailedTestsAction(console, properties).apply { setModelProvider { console.resultsViewer } }
        return DefaultExecutionResult(console, prepared.handler).apply {
            setRestartActions(rerunFailed)
        }
    }
}
