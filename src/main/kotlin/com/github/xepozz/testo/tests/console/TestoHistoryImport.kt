package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider
import com.intellij.execution.testframework.sm.runner.history.ImportedTestConsoleProperties
import com.intellij.execution.testframework.sm.runner.history.actions.AbstractImportTestsAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.OutputStream
import javax.swing.Icon

/**
 * Importing test history through the platform's [AbstractImportTestsAction.doImport] wraps our
 * [TestoConsoleProperties] in [ImportedTestConsoleProperties], which delegates most hooks back to us but NOT
 * `createImportActions` — so the log-level filter button (and any other primary toolbar action we add there) is lost on
 * import. We reconstruct the same import, but build the console on a subclass that re-delegates `createImportActions`,
 * so an imported-history tab carries the exact toolbar a live run does.
 */
internal fun openTestoHistory(project: Project, file: VirtualFile) {
    try {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val profile = TestoImportRunProfile(file, project, executor)
        ExecutionEnvironmentBuilder.create(project, executor, profile)
            .executor(executor)
            .target(profile.target)
            .buildAndExecute()
    } catch (e: Exception) {
        Logger.getInstance("com.github.xepozz.testo.tests.console.TestoHistoryImport")
            .warn("Testo: failed to import test history ${file.path}", e)
    }
}

/**
 * Mirrors `AbstractImportTestsAction.ImportRunProfile` (reused for parsing the saved `<config>` and resolving the
 * target), but its first [getState] returns our [TestoImportedRunnableState] so the console is built on our properties.
 */
// Internal (not private) so the rerun actions' ExecutionEnvironment.testoRunProfile() can recognize an imported Testo
// history tab as a Testo run tab and surface our toolbar's rerun split button on it.
internal class TestoImportRunProfile(
    file: VirtualFile,
    project: Project,
    private val executor: Executor,
) : RunProfile {
    private val inner = AbstractImportTestsAction.ImportRunProfile(file, project, executor)
    private val ioFile = VfsUtilCore.virtualToIoFile(file)
    private val fallbackName = file.nameWithoutExtension
    private var imported = false

    val target get() = inner.target

    /** The Testo run configuration reconstructed from the history `<config>`, if any — used by the rerun actions. */
    val testoConfiguration: RunConfiguration? get() = inner.initialConfiguration

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val config = inner.initialConfiguration
        if (!imported && config is SMRunnerConsolePropertiesProvider) {
            imported = true
            return TestoImportedRunnableState(config as RunConfiguration, ioFile)
        }
        // Re-run from an imported tab (second invocation): run the original configuration's tests, not a re-import.
        return config?.getState(executor, environment) ?: inner.getState(executor, environment)
    }

    override fun getName(): String = inner.initialConfiguration?.name ?: fallbackName
    override fun getIcon(): Icon? = inner.initialConfiguration?.icon
}

/** Mirrors `ImportedTestRunnableState.execute`, but builds the console on [TestoImportedConsoleProperties]. */
private class TestoImportedRunnableState(
    private val initialConfig: RunConfiguration,
    private val file: File,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = NopProcessHandler()
        val delegate = (initialConfig as SMRunnerConsolePropertiesProvider)
            .createTestConsoleProperties(DefaultRunExecutor.getRunExecutorInstance()) as TestoConsoleProperties
        val props = TestoImportedConsoleProperties(
            delegate, file, handler, initialConfig.project, initialConfig, delegate.testFrameworkName, executor,
        )
        Disposer.register(props, delegate)

        val console = SMTestRunnerConnectionUtil.createConsole(props.testFrameworkName, props)
        val component = console.component
        var rerun = if (component is TestFrameworkRunningModel) props.createRerunFailedTestsAction(console) else null
        rerun?.setModelProvider { component as TestFrameworkRunningModel }

        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler).apply { rerun?.let { setRestartActions(it) } }
    }
}

/**
 * Imported-history console properties that keep our primary toolbar actions. Everything else (the XML-parsing converter,
 * locator, rerun, additional actions) is inherited/delegated by [ImportedTestConsoleProperties]; we only re-expose
 * [createImportActions] from the wrapped [delegate] so the visible toolbar matches a live run.
 */
internal class TestoImportedConsoleProperties(
    val delegate: TestoConsoleProperties,
    file: File,
    handler: ProcessHandler,
    project: Project,
    config: RunConfiguration,
    frameworkName: String,
    executor: Executor,
) : ImportedTestConsoleProperties(delegate, file, handler, project, config, frameworkName, executor) {
    override fun createImportActions(): Array<AnAction> = delegate.createImportActions()
}

/** No-op handler standing in for the (private) platform import handler; the run is a replay, not a real process. */
private class NopProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() {}
    override fun detachProcessImpl() = notifyProcessTerminated(0)
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}
