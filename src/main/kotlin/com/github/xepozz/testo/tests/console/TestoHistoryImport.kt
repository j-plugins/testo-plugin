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
internal fun openTestoHistory(project: Project, file: VirtualFile, targetUrl: String? = null) {
    try {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val profile = TestoImportRunProfile(file, project, executor, targetUrl)
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
 * "Show history" for one test: open the most recent saved run that actually contains [url] (so clicking a test doesn't
 * land on an unrelated latest run), and once imported, select that test's node. Falls back to the newest run overall.
 * Scans files off the EDT (the largest history XML is sizeable), then imports on the EDT.
 */
internal fun openTestoHistoryForTest(project: Project, url: String) {
    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
        // Scan the directory directly (not TestHistoryConfiguration.files): a freshly-saved run lands on disk before
        // it's registered there.
        val files = (com.intellij.execution.TestStateStorage.getTestHistoryRoot(project)
            .listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        // Only the run that actually contains this test — do NOT fall back to an unrelated latest run (a saved run that
        // included the test may have been pruned out of the 10-file history; the lens still shows because the last
        // status survives in TestStateStorage).
        val target = files.firstOrNull { f -> runCatching { f.readText().contains(url) }.getOrDefault(false) }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (target == null) {
                com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                    ?.createNotification(
                        "No saved run history contains this test yet — run it to record one.",
                        com.intellij.notification.NotificationType.INFORMATION,
                    )
                    ?.notify(project)
                return@invokeLater
            }
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
                ?: return@invokeLater
            openTestoHistory(project, vf, url)
        }
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
    private val targetUrl: String? = null,
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
            return TestoImportedRunnableState(config as RunConfiguration, ioFile, targetUrl)
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
    private val targetUrl: String?,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = NopProcessHandler()
        val delegate = (initialConfig as SMRunnerConsolePropertiesProvider)
            .createTestConsoleProperties(DefaultRunExecutor.getRunExecutorInstance()) as TestoConsoleProperties
        val props = TestoImportedConsoleProperties(
            delegate, file, handler, initialConfig.project, initialConfig, delegate.testFrameworkName, executor, targetUrl,
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
    // The locationUrl of the test the "Show history" lens was clicked on, so installForImport can select its node.
    val targetUrl: String? = null,
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
