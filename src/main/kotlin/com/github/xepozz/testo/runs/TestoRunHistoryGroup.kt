package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.console.TestoHistoryIndex
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.util.text.DateFormatUtil
import java.nio.file.Path

/**
 * The "Test History" button above the test tree, listing this project's archived Testo runs.
 *
 * It replaces the platform's own `ImportTestsGroup`, which our `createImportActions()` used to inherit from
 * `SMTRunnerConsoleProperties`: that one lists the platform's history XMLs and opens them through the import
 * machinery — a foreign console with none of the Testo UI. Ours replays the archive instead
 * ([TestoRunReplayProfile]), so the reopened run looks and behaves like the run it was.
 *
 * Children are built on a background thread ([ActionUpdateThread.BGT]), which is what lets them read the archive.
 */
class TestoRunHistoryGroup(private val project: Project) : ActionGroup(
    TestoBundle.messagePointer("testo.runs.history.group"),
    TestoBundle.messagePointer("testo.runs.history.group.description"),
    { AllIcons.Vcs.History },
), DumbAware {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null || project.isDisposed) return EMPTY_ARRAY
        val runs = TestoRunStore.getInstance(project).listRuns()
        if (runs.isEmpty()) return arrayOf(NoRuns())
        return buildList<AnAction> {
            runs.forEach { (dir, manifest) -> add(ReplayRun(project, dir, manifest)) }
            add(Separator.getInstance())
            add(ClearHistory(project))
        }.toTypedArray()
    }

    private class ReplayRun(
        private val project: Project,
        private val dir: Path,
        private val manifest: TestoRunManifest,
    ) : AnAction(
        label(dir, manifest),
        null,
        runKindIcon(runKindOf(manifest.executorId)),
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = TestoRunReplayProfile.replay(project, dir, manifest)

        private companion object {
            fun label(dir: Path, manifest: TestoRunManifest): String {
                val name = manifest.configurationName.ifEmpty { dir.fileName.toString() }
                val at = DateFormatUtil.formatDateTime(manifest.startedAt)
                return "$name — $at   ${runResultSummary(manifest)}"
            }
        }
    }

    /** Deletes every archived run of this project — output, reports and all. Asks first: the files are the history. */
    private class ClearHistory(private val project: Project) : AnAction(
        TestoBundle.message("testo.runs.history.clear"),
        null,
        AllIcons.Actions.GC,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val confirmed = MessageDialogBuilder
                .yesNo(TestoBundle.message("testo.runs.history.clear"), TestoBundle.message("testo.runs.history.clear.confirm"))
                .icon(Messages.getWarningIcon())
                .ask(project)
            if (!confirmed) return
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                TestoRunStore.getInstance(project).clear()
                // Every lens was answered off the archive that just went away.
                TestoHistoryIndex.invalidate()
                TestoHistoryIndex.refreshLens(project)
            }
        }
    }

    private class NoRuns : AnAction(TestoBundle.message("testo.runs.history.empty")), DumbAware {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
