package com.github.xepozz.testo.coverage.editor

import com.github.xepozz.testo.coverage.TestoCoverageEngine
import com.github.xepozz.testo.coverage.perTest.TestoCoverageKeys
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Paints per-line coverage in the editor gutter off the active Testo suite's `ProjectData` — the plugin's own stand-in
 * for the platform's editor annotator, whose entry point (`CoverageEngine.createSrcFileAnnotator`) is
 * `@ApiStatus.Internal` and closed to third-party engines (arch §4.2). Same primitives the platform uses underneath:
 * line highlighters in the document markup model carrying a gutter renderer.
 *
 * Lifecycle: installed lazily from [com.github.xepozz.testo.coverage.TestoCoverageAnnotator.onSuiteChosen] (the first
 * coverage activity), then driven by [CoverageSuiteListener.coverageDataCalculated] — fired on the EDT once the report
 * is parsed, so applying never loads data on the UI thread. Closing a suite fires no listener event, only the
 * annotator's `onSuiteChosen`; that call routes back into [refresh], which finds no active Testo bundle and clears.
 *
 * Highlighters are `RangeMarker`s, so edits shift them with the code for free. A marker torn across two lines by an
 * Enter in its middle is dropped — the run measured a line that no longer exists — while everything below keeps its
 * colour at the new position. The platform instead drops every touched marker and restores from VCS history; that
 * mapper is internal, and keeping the marker under ordinary typing serves the live-edit case better anyway.
 */
@Service(Service.Level.PROJECT)
class TestoCoverageEditorHighlighter(private val project: Project) : Disposable {
    private val installed = AtomicBoolean()

    // EDT-confined.
    private var generation = 0
    private var shownData: ProjectData? = null
    private var shownIndex: Map<String, ClassData> = emptyMap()
    private val annotated = HashMap<Document, AnnotatedDocument>()

    private class AnnotatedDocument(val highlighters: MutableList<RangeHighlighter>, val listenerDisposable: Disposable)

    /** Idempotent; safe off the EDT. Registers the suite/editor listeners once and reconciles the current state. */
    fun install() {
        if (installed.compareAndSet(false, true)) {
            CoverageDataManager.getInstance(project).addSuiteListener(object : CoverageSuiteListener {
                override fun coverageDataCalculated(bundle: CoverageSuitesBundle) = refresh()
            }, this)
            EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    if (event.editor.project === project) annotate(event.editor.document)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor.project !== project) return
                    val document = event.editor.document
                    if (EditorFactory.getInstance().editors(document, project).noneMatch { it !== event.editor }) {
                        dropDocument(document)
                    }
                }
            }, this)
        }
        refresh()
    }

    fun refresh() {
        ApplicationManager.getApplication().invokeLater({ reconcile() }, project.disposed)
    }

    private fun reconcile() {
        val bundle = CoverageDataManager.getInstance(project).activeSuites()
            .firstOrNull { it.coverageEngine is TestoCoverageEngine }
        val gen = ++generation
        if (bundle == null) {
            clearAll()
            return
        }
        // getCoverageData() parses the report when the soft cache is empty — keep that possibility off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val data = bundle.coverageData ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ show(gen, data) }, project.disposed)
        }
    }

    private fun show(gen: Int, data: ProjectData) {
        if (gen != generation) return
        if (shownData !== data) {
            clearAll()
            shownData = data
            shownIndex = data.classes.entries.associate { TestoCoverageKeys.normalize(it.key) to it.value }
        }
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project === project) annotate(editor.document)
        }
    }

    private fun annotate(document: Document) {
        if (shownData == null || document in annotated) return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val classData = shownIndex[TestoCoverageKeys.normalize(file.path)]
            ?: file.canonicalPath?.let { shownIndex[TestoCoverageKeys.normalize(it)] }
            ?: return
        val lines = classData.getLines() ?: return

        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val scheme = EditorColorsManager.getInstance().globalScheme
        val highlighters = ArrayList<RangeHighlighter>()
        for (raw in lines) {
            val lineData = raw as? LineData ?: continue
            val docLine = lineData.lineNumber - 1
            if (docLine < 0 || docLine >= document.lineCount) continue

            val renderer = TestoCoverageGutterRenderer(project, file.path, lineData)
            val highlighter = markup.addLineHighlighter(docLine, HighlighterLayer.SELECTION - 1, null)
            highlighter.lineMarkerRenderer = renderer
            val attributes = scheme.getAttributes(renderer.attributesKey)
            if (lineData.status == 0) {
                // Uncovered lines mark the scrollbar, as the platform's stripe does; covered ones would only be noise.
                highlighter.setErrorStripeMarkColor(attributes.errorStripeColor)
                highlighter.setThinErrorStripeMark(true)
            }
            highlighters += highlighter
            // The default coverage colours carry no background — this only fires when the user configured one.
            if (attributes.backgroundColor != null) {
                highlighters += markup.addLineHighlighter(docLine, HighlighterLayer.ADDITIONAL_SYNTAX - 1, attributes)
            }
        }
        if (highlighters.isEmpty()) return

        val listenerDisposable = Disposer.newDisposable(this)
        document.addDocumentListener(SplitLineDropper(highlighters), listenerDisposable)
        annotated[document] = AnnotatedDocument(highlighters, listenerDisposable)
    }

    /**
     * Line markers survive edits by shifting, which is the point — but an Enter inside a marked line leaves one marker
     * spanning two lines, a claim the coverage run never made. Drop those (and any the platform invalidated).
     */
    private class SplitLineDropper(private val highlighters: MutableList<RangeHighlighter>) : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val document = event.document
            val iterator = highlighters.iterator()
            while (iterator.hasNext()) {
                val highlighter = iterator.next()
                if (highlighter.isValid &&
                    document.getLineNumber(highlighter.startOffset) == document.getLineNumber(highlighter.endOffset)
                ) continue
                highlighter.dispose()
                iterator.remove()
            }
        }
    }

    private fun dropDocument(document: Document) {
        val entry = annotated.remove(document) ?: return
        entry.highlighters.forEach { it.dispose() }
        Disposer.dispose(entry.listenerDisposable)
    }

    private fun clearAll() {
        for (entry in annotated.values) {
            entry.highlighters.forEach { it.dispose() }
            Disposer.dispose(entry.listenerDisposable)
        }
        annotated.clear()
        shownData = null
        shownIndex = emptyMap()
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): TestoCoverageEditorHighlighter =
            project.getService(TestoCoverageEditorHighlighter::class.java)
    }
}
