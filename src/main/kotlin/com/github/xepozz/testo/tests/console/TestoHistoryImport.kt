package com.github.xepozz.testo.tests.console

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider
import com.intellij.execution.testframework.sm.runner.history.actions.AbstractImportTestsAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Open a saved test-history file as a run tab. We launch it through a [TestoImportRunProfile] so the platform's standard
 * import builds the console (no dependency on the internal `ImportedTestConsoleProperties`); that console still wraps our
 * [com.github.xepozz.testo.tests.TestoConsoleProperties], so [TestoChannelHistory.installForImport] rebuilds the channel
 * tabs from the metainfo the run stored. The trade-off: the primary-row log-level filter (added via `createImportActions`,
 * which the platform import does not delegate) is absent on imported tabs.
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
 * target): the first [getState] lets the platform import build the console, later ones rerun the reconstructed
 * configuration.
 */
// Internal (not private) so the rerun actions' ExecutionEnvironment.testoRunProfile() can recognize an imported Testo
// history tab as a Testo run tab and surface our toolbar's rerun split button on it.
internal class TestoImportRunProfile(
    file: VirtualFile,
    project: Project,
    private val executor: Executor,
    // The locationUrl of the test the "Show history" lens was clicked on. The augmenter reads it off env.runProfile to
    // select that node once the imported tree is built — keeping history-import detection off the internal
    // ImportedTestConsoleProperties.
    val targetUrl: String? = null,
) : RunProfile {
    private val inner = AbstractImportTestsAction.ImportRunProfile(file, project, executor)
    private val fallbackName = file.nameWithoutExtension
    private var imported = false

    val target get() = inner.target

    /** The Testo run configuration reconstructed from the history `<config>`, if any — used by the rerun actions. */
    val testoConfiguration: RunConfiguration? get() = inner.initialConfiguration

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val config = inner.initialConfiguration
        // First launch: let the platform build the imported-history console. Its standard ImportedTestConsoleProperties
        // wraps our TestoConsoleProperties, so the channel UI still rebuilds from the metainfo the run stored. A rerun
        // from that tab (second invocation) runs the reconstructed configuration's tests instead of re-importing.
        if (!imported && config is SMRunnerConsolePropertiesProvider) {
            imported = true
            return inner.getState(executor, environment)
        }
        return config?.getState(executor, environment) ?: inner.getState(executor, environment)
    }

    override fun getName(): String = inner.initialConfiguration?.name ?: fallbackName
    override fun getIcon(): Icon? = inner.initialConfiguration?.icon
}
