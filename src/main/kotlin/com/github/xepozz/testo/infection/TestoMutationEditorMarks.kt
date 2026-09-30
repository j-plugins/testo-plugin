package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import java.awt.Font
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * Marks in the editor for the mutation run of the selected *Mutations* tab, else the latest ([TestoMutationService.current]): a gutter icon
 * on each line a mutant sits on, the worst status first, and an underline over the code of the escaped ones. A file
 * whose code is no longer what Infection mutated gets none: Infection's byte offsets would land on other code.
 */
@Service(Service.Level.PROJECT)
class TestoMutationEditorMarks(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var watched: TestoMutationRun? = null

    @Volatile
    private var watchedPaths: Set<String> = emptySet()

    private val onRunChanged: () -> Unit = { refresh() }

    /** Whether the marks are shown at all; kept for the next session. */
    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(ENABLED_KEY, value, true)
            refresh()
        }

    /** The statuses left out of the marks; a mutant still running is always shown. Kept for the next session. */
    var hiddenStatuses: Set<MutantStatus>
        get() = PropertiesComponent.getInstance().getList(HIDDEN_KEY).orEmpty()
            .mapNotNullTo(HashSet()) { name -> MutantStatus.entries.firstOrNull { it.name == name } }
        set(value) {
            PropertiesComponent.getInstance().setList(HIDDEN_KEY, value.map { it.name }.sorted())
            refresh()
        }

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh()
            }
        }, this)
        // An edit can move the code off the offsets Infection gave, so the marks go as soon as the file changes.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.path in watchedPaths) refresh()
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = watchedPaths
                if (events.any { it.path in paths }) refresh()
            }
        })
    }

    fun refresh() {
        alarm.cancelAllRequests()
        alarm.addRequest(::compute, REFRESH_MS)
    }

    private fun compute() {
        if (project.isDisposed) return
        val run = if (enabled) TestoMutationService.getInstance(project).current() else null
        watch(run)
        val hidden = hiddenStatuses
        val marks = run?.let { marksOf(it, hidden) }.orEmpty()
        ApplicationManager.getApplication().invokeLater({ apply(run, marks) }, project.disposed)
    }

    private fun watch(run: TestoMutationRun?) {
        if (run !== watched) {
            watched?.removeListener(onRunChanged)
            run?.addListener(onRunChanged)
            watched = run
        }
        watchedPaths = run?.files?.mapNotNullTo(HashSet()) { localOf(run, it) }.orEmpty()
    }

    private fun localOf(run: TestoMutationRun, file: MutatedFile): String? =
        run.localPath(file.path)?.let(FileUtil::toSystemIndependentName)

    /** Each mutated file still holding the code Infection read, by host path: where its mutants sit. */
    private fun marksOf(run: TestoMutationRun, hidden: Set<MutantStatus>): Map<String, FileMarks> = run.files.mapNotNull { file ->
        val local = localOf(run, file) ?: return@mapNotNull null
        val bytes = runCatching { Files.readAllBytes(Path.of(local)) }.getOrNull() ?: return@mapNotNull null
        val expected = run.fingerprints[file.path]
        if (expected != null && fingerprintOf(Path.of(local)) != expected) return@mapNotNull null
        if (isUnsaved(local)) return@mapNotNull null
        val lines = TestoByteLines(bytes)
        val placed = file.mutants.filter { it.status !in hidden }.mapNotNull { mutant ->
            val start = mutant.start ?: return@mapNotNull null
            // Infection's end is the mutated node's last byte.
            val end = (mutant.end ?: start) + 1
            PlacedMutant(mutant, lines.position(start), lines.position(end))
        }
        local to FileMarks(placed)
    }.toMap()

    private fun isUnsaved(local: String): Boolean {
        val file = LocalFileSystem.getInstance().findFileByPath(local) ?: return false
        return ApplicationManager.getApplication().runReadAction(Computable { FileDocumentManager.getInstance().isFileModified(file) })
    }

    private fun apply(run: TestoMutationRun?, marks: Map<String, FileMarks>) {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project || editor.isDisposed) continue
            clear(editor)
            if (run == null) continue
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: continue
            marks[file.path]?.let { install(editor, run, it) }
        }
    }

    private fun clear(editor: Editor) {
        editor.getUserData(MARKS_KEY)?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(MARKS_KEY, null)
    }

    private fun install(editor: Editor, run: TestoMutationRun, marks: FileMarks) {
        val document = editor.document
        val markup = editor.markupModel
        val installed = ArrayList<RangeHighlighter>()
        marks.mutants.groupBy { it.start.first }.forEach { (line, onLine) ->
            if (line >= document.lineCount) return@forEach
            markup.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply {
                gutterIconRenderer = MutantsGutter(project, run, line, onLine.map { it.mutant })
                installed += this
            }
        }
        marks.mutants.filter { it.mutant.status == MutantStatus.ESCAPED }.forEach { placed ->
            val start = offsetOf(editor, placed.start) ?: return@forEach
            val end = offsetOf(editor, placed.end)?.coerceAtLeast(start) ?: return@forEach
            if (end == start) return@forEach
            markup.addRangeHighlighter(start, end, HighlighterLayer.WARNING, ESCAPED_TEXT, HighlighterTargetArea.EXACT_RANGE).apply {
                setErrorStripeMarkColor(ESCAPED_COLOR)
                errorStripeTooltip = TestoBundle.message("infection.editor.escaped", placed.mutant.mutator)
                installed += this
            }
        }
        editor.putUserData(MARKS_KEY, installed)
    }

    private fun offsetOf(editor: Editor, position: Pair<Int, Int>): Int? {
        val document = editor.document
        val (line, column) = position
        if (line >= document.lineCount) return null
        return (document.getLineStartOffset(line) + column).coerceAtMost(document.getLineEndOffset(line))
    }

    override fun dispose() {
        watched?.removeListener(onRunChanged)
    }

    private class PlacedMutant(val mutant: Mutant, val start: Pair<Int, Int>, val end: Pair<Int, Int>)

    private class FileMarks(val mutants: List<PlacedMutant>)

    /** The line's mutants: the worst status as the icon, all of them in the tooltip, a menu of them on a click. */
    private class MutantsGutter(
        private val project: Project,
        private val run: TestoMutationRun,
        private val line: Int,
        private val mutants: List<Mutant>,
    ) : GutterIconRenderer() {
        private val worst: Mutant = mutants.minBy { severity(it.status) }

        override fun getIcon(): Icon = worst.status?.icon ?: MutantStatus.UNFINISHED_ICON

        override fun getTooltipText(): String = buildString {
            append("<html>")
            mutants.sortedBy { severity(it.status) }.forEach { mutant ->
                val status = mutant.status?.label ?: TestoBundle.message("infection.status.running")
                append("<b>").append(StringUtil.escapeXmlEntities(status)).append("</b> ")
                append(StringUtil.escapeXmlEntities(mutant.mutator)).append("<br>")
            }
            append("</html>")
        }

        override fun isNavigateAction(): Boolean = true

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val group = DefaultActionGroup()
                mutants.sortedBy { severity(it.status) }.forEach { mutant ->
                    val status = mutant.status?.label ?: TestoBundle.message("infection.status.running")
                    group.add(object : DumbAwareAction("$status · ${mutant.mutator}", null, mutant.status?.icon) {
                        override fun actionPerformed(e: AnActionEvent) = TestoMutationToolWindow.reveal(project, run, mutant)
                    })
                }
                group.addSeparator()
                group.add(object : DumbAwareAction(TestoBundle.message("infection.editor.rerun", mutants.size.toString())) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = !run.isBusy && run.recipe != null
                    }

                    override fun actionPerformed(e: AnActionEvent) {
                        TestoMutationService.getInstance(project).rerun(run, mutants)
                        TestoMutationToolWindow.show(project, run)
                    }
                })
                mutants.singleOrNull()?.takeIf { TestoMutationApply.canApply(run, it) }?.let { mutant ->
                    group.add(object : DumbAwareAction(TestoBundle.message("infection.apply.action"), null, AllIcons.Actions.Edit) {
                        override fun actionPerformed(e: AnActionEvent) {
                            TestoMutationApply.apply(project, run, mutant)
                        }
                    })
                }
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    TestoBundle.message("infection.editor.line", (line + 1).toString()),
                    group,
                    e.dataContext,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true,
                    ActionPlaces.EDITOR_GUTTER_POPUP,
                )
                val mouse = e.inputEvent as? MouseEvent
                if (mouse != null) popup.show(RelativePoint(mouse)) else popup.showInBestPositionFor(e.dataContext)
            }
        }

        override fun equals(other: Any?): Boolean =
            other is MutantsGutter && other.run === run && other.line == line && other.mutants == mutants &&
                other.mutants.map { it.status } == mutants.map { it.status }

        override fun hashCode(): Int = 31 * line + mutants.hashCode()
    }

    companion object {
        private const val REFRESH_MS = 300
        private const val ENABLED_KEY = "testo.mutations.editor.marks"
        private const val HIDDEN_KEY = "testo.mutations.editor.hidden"

        private val MARKS_KEY = Key.create<List<RangeHighlighter>>("testo.mutations.editor.marks")

        private val ESCAPED_COLOR = JBColor(0xE05555, 0xC75450)
        private val ESCAPED_TEXT = TextAttributes(null, null, ESCAPED_COLOR, EffectType.WAVE_UNDERSCORE, Font.PLAIN)

        /** Worst first: what the tests let through, then what they could not judge, then what they caught. */
        private val SEVERITY = listOf(
            MutantStatus.ESCAPED,
            MutantStatus.TIMED_OUT,
            MutantStatus.ERROR,
            MutantStatus.SYNTAX_ERROR,
            MutantStatus.NOT_COVERED,
            null,
            MutantStatus.KILLED,
            MutantStatus.KILLED_BY_SA,
            MutantStatus.SKIPPED,
            MutantStatus.IGNORED,
        )

        private fun severity(status: MutantStatus?): Int = SEVERITY.indexOf(status)

        val STATUSES: List<MutantStatus> = SEVERITY.filterNotNull()

        fun getInstance(project: Project): TestoMutationEditorMarks = project.service()
    }
}
