package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoHistoryIndex
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import java.nio.file.Path

/**
 * *Replay* — everything about **this tab's** archived run: export it as a single file, say what retention may do with
 * it, and load someone else's export back.
 *
 * The run it acts on is the replayed archive on a history tab, or the recording of a live run — the same directory
 * either way, so the group means the same thing on both.
 */
class TestoReplayGroup(
    private val project: Project,
    private val props: TestoConsoleProperties,
) : ActionGroup(
    TestoBundle.messagePointer("testo.runs.replay.group"),
    TestoBundle.messagePointer("testo.runs.replay.group.description"),
    { AllIcons.Actions.Play_forward },
), DumbAware {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = arrayOf(
        ExportAction(),
        Separator.create(TestoBundle.message("testo.runs.replay.retention.title")),
        RetentionOption(RunRetention.AUTO, "testo.runs.replay.keep"),
        RetentionOption(RunRetention.DISCARD, "testo.runs.replay.discard"),
        RetentionOption(RunRetention.LOCKED, "testo.runs.replay.lock"),
        Separator.getInstance(),
        ImportAction(),
    )

    /** This tab's run directory: the archive a history tab replays, or the one a live run is being recorded into. */
    private fun runDir(): Path? = props.currentRunDir()

    private inner class ExportAction : AnAction(
        TestoBundle.message("testo.runs.replay.export"),
        null,
        AllIcons.ToolbarDecorator.Export,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        // Only once the run has an archive to export: a live run's directory fills as it goes, and a zip of half of
        // it would replay as a run that stops in the middle.
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = runDir()?.let { TestoRunStore.getInstance(project).readManifest(it) } != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val dir = runDir() ?: return
            val store = TestoRunStore.getInstance(project)
            val manifest = store.readManifest(dir) ?: return
            val descriptor = FileSaverDescriptor(
                TestoBundle.message("testo.runs.replay.export.title"),
                TestoBundle.message("testo.runs.replay.export.description"),
                "zip",
            )
            val target = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(null as java.nio.file.Path?, exportFileName(manifest))
                ?: return
            val file = target.file.toPath()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { zipRunDirectory(dir, file) }
                notify(
                    if (result.isSuccess) TestoBundle.message("testo.runs.replay.export.done", file.toString())
                    else TestoBundle.message("testo.runs.replay.export.failed"),
                    if (result.isSuccess) NotificationType.INFORMATION else NotificationType.WARNING,
                )
            }
        }
    }

    /** Import lives here rather than in the history list: it is the same "replay as a file" idea, read instead of written. */
    private inner class ImportAction : AnAction(
        TestoBundle.message("testo.runs.replay.import"),
        null,
        AllIcons.ToolbarDecorator.Import,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
                .withTitle(TestoBundle.message("testo.runs.replay.import.title"))
                .withExtensionFilter("zip")
            val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
            val zip = chosen.toNioPath()
            ApplicationManager.getApplication().executeOnPooledThread {
                val imported = TestoRunStore.getInstance(project).importRun(zip)
                if (imported == null) {
                    notify(TestoBundle.message("testo.runs.replay.import.failed"), NotificationType.WARNING)
                    return@executeOnPooledThread
                }
                TestoHistoryIndex.invalidate()
                TestoHistoryIndex.refreshLens(project)
                ApplicationManager.getApplication().invokeLater(
                    { TestoRunReplayProfile.replay(project, imported.first, imported.second) },
                    project.disposed,
                )
            }
        }
    }

    /** One of the three retention choices — exclusive, so picking one is the whole gesture. */
    private inner class RetentionOption(
        private val retention: RunRetention,
        labelKey: String,
    ) : ToggleAction(TestoBundle.message(labelKey), null, iconOf(retention)), DumbAware {

        init {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = runDir() != null
        }

        override fun isSelected(e: AnActionEvent): Boolean = current() == retention

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            val dir = runDir() ?: return
            // Both, and in this order: the recording carries the choice into the manifest it is about to write, and
            // the manifest is what an already-archived run is edited through.
            props.recording?.retention = retention
            ApplicationManager.getApplication().executeOnPooledThread {
                TestoRunStore.getInstance(project).setRetention(dir, retention)
            }
        }

        private fun current(): RunRetention =
            props.recording?.retention
                ?: runDir()?.let { TestoRunStore.getInstance(project).retentionOf(it) }
                ?: RunRetention.AUTO
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Testo")
            ?.createNotification(text, type)
            ?.notify(project)
    }

    private companion object {
        fun iconOf(retention: RunRetention) = when (retention) {
            RunRetention.AUTO -> null
            RunRetention.DISCARD -> AllIcons.Actions.GC
            RunRetention.LOCKED -> AllIcons.Nodes.Locked
        }
    }
}
