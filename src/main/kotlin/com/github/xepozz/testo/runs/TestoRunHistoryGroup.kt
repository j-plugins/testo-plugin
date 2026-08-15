package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.console.TestoHistoryIndex
import com.intellij.CommonBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
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
class TestoRunHistoryGroup(
    private val project: Project,
    /** The archive this tab is showing, so the list can say which entry the user is already looking at. */
    private val currentRunDir: () -> Path? = { null },
) : ActionGroup(
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
        val current = runCatching { currentRunDir()?.toAbsolutePath()?.normalize() }.getOrNull()
        return buildList<AnAction> {
            runs.forEach { (dir, manifest) ->
                add(ReplayRun(project, dir, manifest, dir.toAbsolutePath().normalize() == current))
            }
            add(Separator.getInstance())
            add(ClearHistory(project, currentRunDir))
        }.toTypedArray()
    }

    private class ReplayRun(
        private val project: Project,
        private val dir: Path,
        private val manifest: TestoRunManifest,
        current: Boolean,
    ) : AnAction(
        label(dir, manifest, current),
        null,
        runHistoryIcon(manifest),
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = TestoRunReplayProfile.replay(project, dir, manifest)

        private companion object {
            fun label(dir: Path, manifest: TestoRunManifest, current: Boolean): String {
                val name = manifest.configurationName.ifEmpty { dir.fileName.toString() }
                val at = DateFormatUtil.formatDateTime(manifest.startedAt)
                val text = "$name — $at   ${runResultSummary(manifest)}"
                // The run this tab is already showing, in bold — menu items render HTML, and there is no other way
                // to weight one of them.
                return if (current) "<html><b>${StringUtil.escapeXmlEntities(text)}</b></html>" else text
            }
        }
    }

    /**
     * Deletes archived runs — output, reports and all. Asks first, and separately about the locked ones: locking a run
     * is the one way to say "not this one", so a blanket delete must not be the only offer.
     */
    private class ClearHistory(
        private val project: Project,
        private val currentRunDir: () -> Path?,
    ) : AnAction(
        TestoBundle.message("testo.runs.history.clear"),
        null,
        AllIcons.Actions.GC,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val choice = Messages.showDialog(
                project,
                TestoBundle.message("testo.runs.history.clear.confirm"),
                TestoBundle.message("testo.runs.history.clear"),
                arrayOf(
                    TestoBundle.message("testo.runs.history.clear.unlocked"),
                    TestoBundle.message("testo.runs.history.clear.all"),
                    CommonBundle.getCancelButtonText(),
                ),
                0,
                Messages.getWarningIcon(),
            )
            if (choice != KEEP_LOCKED && choice != DELETE_ALL) return
            val current = runCatching { currentRunDir() }.getOrNull()
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                TestoRunStore.getInstance(project).clearHistory(keepLocked = choice == KEEP_LOCKED, spare = current)
                // Every lens was answered off the archive that just went away.
                TestoHistoryIndex.invalidate()
                TestoHistoryIndex.refreshLens(project)
            }
        }

        private companion object {
            private const val KEEP_LOCKED = 0
            private const val DELETE_ALL = 1
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
