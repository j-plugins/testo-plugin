package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.tests.console.TestoTestStatus
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.LayeredIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JList

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

/** The history entry's icon: what the run was, wearing a lock when the user locked it out of the rotation. */
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
 * "Show history" for one test: replay the newest archived run that actually holds it (not merely the latest run), and
 * select that test's node once the tree is rebuilt. Scans the archive off the EDT, launches on it.
 */
internal fun replayNewestRunWithTest(project: Project, url: String) {
    val key = normalizeRunLocation(url)
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val store = TestoRunStore.getInstance(project)
        val match = store.listRuns().firstOrNull { (dir, _) ->
            store.readLocations(dir).any { it == key || it.startsWith(key) }
        }
        ApplicationManager.getApplication().invokeLater(
            {
                if (match == null) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                        ?.createNotification(
                            TestoBundle.message("testo.runs.history.none"),
                            NotificationType.INFORMATION,
                        )
                        ?.notify(project)
                    return@invokeLater
                }
                TestoRunReplayProfile.replay(project, match.first, match.second, url)
            },
            project.disposed,
        )
    }
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
