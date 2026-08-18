package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoTestStatus
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.LayeredIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.text.DateFormatUtil
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JList

private val LOG = Logger.getInstance("com.github.xepozz.testo.runs.TestoRunHistory")

/** `Tools | Testo | Testo Run History…`: pick an archived run, replay it into a run tab. */
class TestoRunHistoryAction : AnAction(TestoBundle.message("testo.runs.history.action"), null, AllIcons.Vcs.History), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val runs = TestoRunStore.getInstance(project).listRuns()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (runs.isEmpty()) {
                        JBPopupFactory.getInstance()
                            .createMessage(TestoBundle.message("testo.runs.history.empty"))
                            .showCenteredInCurrentWindow(project)
                        return@invokeLater
                    }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(runs)
                        .setTitle(TestoBundle.message("testo.runs.history.title"))
                        .setRenderer(RunHistoryCellRenderer())
                        .setItemChosenCallback { (dir, manifest) ->
                            TestoRunReplayProfile.replay(project, dir, manifest)
                        }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                },
                project.disposed,
            )
        }
    }

    private class RunHistoryCellRenderer : ColoredListCellRenderer<Pair<Path, TestoRunManifest>>() {
        override fun customizeCellRenderer(
            list: JList<out Pair<Path, TestoRunManifest>>,
            value: Pair<Path, TestoRunManifest>,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val manifest = value.second
            icon = runHistoryIcon(manifest)
            append(manifest.configurationName.ifEmpty { value.first.fileName.toString() })
            append(" — ${DateFormatUtil.formatDateTime(manifest.startedAt)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("   ${runResultSummary(manifest)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

/** `Tools | Testo | Run History Retention`: how many archived runs to keep; pruning runs after each archived run. */
class TestoRunRetentionGroup : DefaultActionGroup(TestoBundle.message("testo.runs.retention.group"), true), DumbAware {
    init {
        LIMITS.forEach { add(RetentionOption(it)) }
    }

    private class RetentionOption(private val limit: Int) :
        ToggleAction(TestoBundle.message("testo.runs.retention.option", limit)), DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = TestoRunStore.retentionLimit() == limit

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) TestoRunStore.setRetentionLimit(limit)
        }
    }

    private companion object {
        private val LIMITS = listOf(5, 10, 20, 50)
    }
}

/** What a run was started as — all the history list needs to know to draw it. */
internal enum class TestoRunKind { RUN, DEBUG, COVERAGE }

internal fun runKindOf(executorId: String?): TestoRunKind = when (executorId) {
    TestoCoverageProgramRunner.EXECUTOR_ID -> TestoRunKind.COVERAGE
    DefaultDebugExecutor.EXECUTOR_ID -> TestoRunKind.DEBUG
    // Also a v1 archive, which recorded no executor: a plain run is the honest guess.
    else -> TestoRunKind.RUN
}

// The tool window icons rather than the toolbar ones: they are the plainer shapes, and the lock overlay has room to
// sit on them.
internal fun runKindIcon(kind: TestoRunKind): Icon = when (kind) {
    TestoRunKind.COVERAGE -> AllIcons.Toolwindows.ToolWindowCoverage
    TestoRunKind.DEBUG -> AllIcons.Toolwindows.ToolWindowDebugger
    TestoRunKind.RUN -> AllIcons.Toolwindows.ToolWindowRun
}

internal fun runHistoryIcon(manifest: TestoRunManifest): Icon {
    val base = runKindIcon(runKindOf(manifest.executorId))
    if (manifest.retention != RunRetention.LOCKED) return base
    return LayeredIcon.layeredIcon { arrayOf(base, AllIcons.Nodes.Locked) }
}

/** How the run ended, as the history list spells it: "145 total, 42 failed". */
internal fun runResultSummary(manifest: TestoRunManifest): String {
    val total = manifest.statuses.values.sum()
    if (total == 0) return TestoBundle.message("testo.runs.history.summary.empty")
    val failed = manifest.statuses.entries
        .filter { TestoTestStatus.fromWire(it.key)?.isProblem == true }
        .sumOf { it.value }
    return when {
        failed > 0 -> TestoBundle.message("testo.runs.history.summary.failed", total.toString(), failed.toString())
        else -> TestoBundle.message("testo.runs.history.summary.passed", total.toString())
    }
}

/**
 * "Show history" for one test: a popup of the archived runs that hold it, newest first, the one already shown in a tab
 * in bold. Each row carries the Load-replay / Repeat-run inline buttons (see [RunHistoryRow]). The archive is scanned
 * off the EDT, but the open-tab set is read first — RunContentManager is EDT-only.
 */
internal fun showRunHistoryForTest(project: Project, url: String, editor: Editor, event: MouseEvent?) {
    val key = runLocationKey(url)
    val openDirs = openReplayDirs(project)
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val store = TestoRunStore.getInstance(project)
        // `startsWith("$key::")`, not `startsWith(key)`: `…::testPay` must not answer for `…::testPayment`.
        val entries = store.listRuns()
            .filter { (dir, _) -> store.readLocations(dir).any { val k = runLocationKey(it); k == key || k.startsWith("$key::") } }
            .map { (dir, manifest) -> RunHistoryEntry(dir, manifest, dir.toAbsolutePath().normalize() in openDirs) }
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater
                if (entries.isEmpty()) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                        ?.createNotification(TestoBundle.message("testo.runs.history.none"), NotificationType.INFORMATION)
                        ?.notify(project)
                    return@invokeLater
                }
                val group = DefaultActionGroup().apply { entries.forEach { add(RunHistoryRow(project, it, url)) } }
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    TestoBundle.message("testo.runs.history.forTest.title"),
                    group,
                    DataManager.getInstance().getDataContext(editor.contentComponent),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    false,
                )
                if (event != null) popup.show(RelativePoint(event)) else popup.showInBestPositionFor(editor)
            },
            project.disposed,
        )
    }
}

private class RunHistoryEntry(val dir: Path, val manifest: TestoRunManifest, val current: Boolean)

/** The archive dirs a run tab is currently showing (a live run's own, or a replay's), so the list can bold them. */
private fun openReplayDirs(project: Project): Set<Path> =
    RunContentManager.getInstance(project).allDescriptors.mapNotNull { descriptor ->
        val console = descriptor.executionConsole as? SMTRunnerConsoleView
        (console?.properties as? TestoConsoleProperties)?.currentRunDir()?.toAbsolutePath()?.normalize()
    }.toSet()

/** One archived run: Load-replay / Repeat-run inline buttons at the row's right edge; a bare click on the body runs nothing. */
private class RunHistoryRow(project: Project, entry: RunHistoryEntry, url: String) : AnAction(), DumbAware {
    init {
        val manifest = entry.manifest
        val name = manifest.configurationName.ifEmpty { entry.dir.fileName.toString() }
        val text = "$name — ${DateFormatUtil.formatDateTime(manifest.startedAt)}   ${runResultSummary(manifest)}"
        // Menu items render HTML; the run this tab already shows goes bold, as the history list does.
        templatePresentation.text = if (entry.current) "<html><b>${StringUtil.escapeXmlEntities(text)}</b></html>" else text
        templatePresentation.icon = runHistoryIcon(manifest)
        templatePresentation.putClientProperty(
            ActionUtil.INLINE_ACTIONS,
            listOf(InlineReplay(project, entry, url), InlineRepeat(project, entry)),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = Unit
}

private class InlineReplay(private val project: Project, private val entry: RunHistoryEntry, private val url: String) :
    AnAction(
        TestoBundle.message("testo.runs.history.forTest.loadReplay"),
        TestoBundle.message("testo.runs.history.forTest.loadReplay"),
        AllIcons.Actions.Rollback,
    ), DumbAware {
    init { templatePresentation.putClientProperty(ActionUtil.ALWAYS_VISIBLE_INLINE_ACTION, true) }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = TestoRunReplayProfile.replay(project, entry.dir, entry.manifest, url)
}

private class InlineRepeat(private val project: Project, private val entry: RunHistoryEntry) :
    AnAction(
        TestoBundle.message("testo.runs.history.forTest.repeat"),
        TestoBundle.message("testo.runs.history.forTest.repeat"),
        AllIcons.Actions.Restart,
    ), DumbAware {
    init { templatePresentation.putClientProperty(ActionUtil.ALWAYS_VISIBLE_INLINE_ACTION, true) }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = repeatArchivedRun(project, entry.dir, entry.manifest)
}

/** Re-executes an archived run: its own configuration, restored from the manifest, launched with its original executor. */
private fun repeatArchivedRun(project: Project, runDir: Path, manifest: TestoRunManifest) {
    val executor = ExecutorRegistry.getInstance().getExecutorById(manifest.executorId)
        ?: DefaultRunExecutor.getRunExecutorInstance()
    val name = manifest.configurationName.ifEmpty { runDir.fileName.toString() }
    val settings = RunManager.getInstance(project).createConfiguration(name, TestoRunConfigurationType.INSTANCE)
    val configuration = settings.configuration as? TestoRunConfiguration ?: return
    manifest.configuration.takeIf { it.isNotBlank() }?.let { xml ->
        runCatching { configuration.readExternal(JDOMUtil.load(xml)) }
            .onFailure { LOG.warn("Failed to restore the run configuration of $runDir", it) }
    }
    val environment = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build() ?: return
    ExecutionManager.getInstance(project).restartRunProfile(environment)
}

/** The lens's fallback when it cannot name a test: replay the newest archived run. */
internal fun replayNewestRun(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val newest = TestoRunStore.getInstance(project).listRuns().firstOrNull() ?: return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater(
            { TestoRunReplayProfile.replay(project, newest.first, newest.second) },
            project.disposed,
        )
    }
}
