package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.TreePath

/** One mutation run: the files Infection mutated, their mutants, and whatever the selected one has to show. */
class TestoMutationPanel(val project: Project, val run: TestoMutationRun) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    /** Show only the mutants the tests let through. */
    var escapedOnly = false
        set(value) {
            field = value
            structureModel.invalidateAsync()
        }

    private val structureModel = StructureTreeModel(Structure(), this)
    private val tree = Tree(AsyncTreeModel(structureModel, this)).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = NodeRenderer()
    }
    val preferredFocus: JComponent get() = tree

    private val summary = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
    private val details = JPanel(CardLayout())
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        border = JBUI.Borders.empty(6, 8)
    }
    private val diff = DiffManager.getInstance().createRequestPanel(project, this, null)
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        border = JBUI.Borders.empty(6, 8)
    }
    private var shown: Any? = null

    private val lines = ConcurrentHashMap<String, LineIndex?>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scheduled = AtomicBoolean()
    private val dirty = AtomicBoolean(true)
    private var expanded = 0
    private val listener: () -> Unit = {
        dirty.set(true)
        schedule()
    }

    init {
        details.add(ScrollPaneFactory.createScrollPane(text, true), TEXT_CARD)
        details.add(
            OnePixelSplitter(true, 0.6f).apply {
                firstComponent = diff.component
                secondComponent = ScrollPaneFactory.createScrollPane(output, true)
            },
            DIFF_CARD,
        )

        val treePane = JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(OnePixelSplitter(true, 0.55f).apply {
            firstComponent = treePane
            secondComponent = details
        })
        toolbar = createToolbar()

        EditSourceOnDoubleClickHandler.install(tree)
        EditSourceOnEnterKeyHandler.install(tree)
        PopupHandler.installPopupMenu(tree, POPUP_GROUP, ActionPlaces.getPopupPlace(PLACE))
        tree.addTreeSelectionListener { showDetails() }

        run.addListener(listener)
        Disposer.register(this) { run.removeListener(listener) }
        refresh()
    }

    private fun createToolbar(): JComponent {
        val manager = ActionManager.getInstance()
        val expander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup().apply {
            manager.getAction(TOOLBAR_GROUP)?.let(::add)
            addSeparator()
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        return manager.createActionToolbar(PLACE, group, true).apply { targetComponent = this@TestoMutationPanel }.component
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) alarm.addRequest(::refresh, REFRESH_MS)
    }

    private fun refresh() {
        scheduled.set(false)
        if (dirty.getAndSet(false)) structureModel.invalidateAsync().thenRun { UIUtil.invokeLaterIfNeeded(::expandWhileSmall) }
        summary.icon = if (run.isRunning) MutantStatus.RUNNING_ICON else verdictIcon()
        summary.text = summaryText()
        showDetails()
        if (run.isRunning) schedule()
    }

    // Expanded for the first file and again once the run is over, unless the tree has grown too big to read that way.
    private fun expandWhileSmall() {
        val stage = if (run.isRunning) 1 else 2
        if (expanded >= stage || run.files.isEmpty() || run.mutants.size > EXPAND_LIMIT) return
        expanded = stage
        TreeUtil.promiseExpandAll(tree)
    }

    private fun verdictIcon() = when {
        run.stopRequested -> TestoIcons.Status.FAILURE_CANCELLED
        run.exitCode != 0 && run.mutants.isEmpty() -> TestoIcons.Status.FAILURE
        run.score().escaped > 0 -> TestoIcons.Status.FAILURE
        else -> TestoIcons.Status.SUCCESS
    }

    private fun summaryText(): String {
        val score = run.score()
        val done = run.finishedCount().toString()
        val total = maxOf(run.expected, run.mutants.size).toString()
        val elapsed = "${run.elapsedMs() / 1000}s"
        return if (score.msi == null) TestoBundle.message("infection.summary.empty", done, total, elapsed)
        else TestoBundle.message(
            "infection.summary",
            score.msi.toString(),
            (score.coveredMsi ?: 0).toString(),
            score.escaped.toString(),
            done,
            total,
            elapsed,
        )
    }

    private fun selected(): Any? = tree.selectionPath?.let(::elementOf)

    fun selectedMutants(): List<Mutant> = tree.selectionPaths.orEmpty().mapNotNull { elementOf(it) as? Mutant }

    private fun elementOf(path: TreePath): Any? = TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element

    private fun showDetails() {
        val element = selected()
        val mutant = element as? Mutant
        val original = mutant?.original
        val mutated = mutant?.mutated
        val key = when (element) {
            is Mutant -> "m|${element.nodeId}|${element.status}|${original != null}|${element.output != null}"
            is MutatedFile -> "f|${element.nodeId}|${element.mutants.count { it.finished }}"
            else -> run.log()
        }
        if (key == shown) return
        shown = key

        val cards = details.layout as CardLayout
        if (mutant != null && original != null && mutated != null) {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(mutant.file.path)
            val factory = DiffContentFactory.getInstance()
            diff.setRequest(
                SimpleDiffRequest(
                    "${mutant.mutator} · ${mutant.file.name}",
                    factory.create(project, original, fileType),
                    factory.create(project, mutated, fileType),
                    TestoBundle.message("infection.diff.original"),
                    TestoBundle.message("infection.diff.mutant"),
                )
            )
            output.text = describe(mutant)
            output.caretPosition = 0
            cards.show(details, DIFF_CARD)
            return
        }
        text.text = when (element) {
            is Mutant -> describe(element)
            is MutatedFile -> describe(element)
            else -> run.log()
        }
        text.caretPosition = 0
        cards.show(details, TEXT_CARD)
    }

    private fun describe(mutant: Mutant): String = buildString {
        appendLine(mutant.mutatorClass)
        appendLine(mutant.status?.label ?: TestoBundle.message("infection.status.running"))
        val position = position(mutant)
        appendLine(if (position != null) "${mutant.file.name}:${position.first + 1}" else mutant.file.name)
        appendLine(TestoBundle.message("infection.details.id", mutant.hash))
        mutant.durationMs?.let { appendLine(TestoBundle.message("infection.details.duration", it.toString())) }
        val tests = mutant.output
        when {
            !tests.isNullOrBlank() -> appendLine().appendLine(tests)
            tests == null && run.isRunning -> appendLine().appendLine(TestoBundle.message("infection.details.outputPending"))
        }
    }

    private fun describe(file: MutatedFile): String = buildString {
        appendLine(file.name)
        file.mutants.mapNotNull { it.status }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.ordinal }
            .forEach { (status, count) -> appendLine("${status.label}: $count") }
    }

    private fun localFile(file: MutatedFile) = run.localPath(file.path)?.let(LocalFileSystem.getInstance()::findFileByPath)

    /** 0-based line and column of the mutated code; Infection's offsets count bytes, so they are resolved on bytes. */
    private fun position(mutant: Mutant): Pair<Int, Int>? {
        val offset = mutant.start ?: return null
        val local = run.localPath(mutant.file.path) ?: return null
        val index = lines.computeIfAbsent(local) { path ->
            runCatching { LineIndex(Files.readAllBytes(Path.of(path))) }.getOrNull()
        } ?: return null
        return index.position(offset)
    }

    private fun navigatable(element: Any?): Navigatable? = when (element) {
        is Mutant -> localFile(element.file)?.let { file ->
            val position = position(element)
            if (position == null) OpenFileDescriptor(project, file)
            else OpenFileDescriptor(project, file, position.first, position.second)
        }
        is MutatedFile -> localFile(element)?.let { OpenFileDescriptor(project, it) }
        else -> null
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[PANEL] = this
        val element = selected()
        sink.lazy(CommonDataKeys.NAVIGATABLE) { navigatable(element) }
    }

    override fun dispose() = Unit

    private object Root

    private inner class Structure : AbstractTreeStructure() {
        override fun getRootElement(): Any = Root

        override fun getChildElements(element: Any): Array<Any> = when (element) {
            Root -> run.files
                .filter { file -> !escapedOnly || file.mutants.any { it.status == MutantStatus.ESCAPED } }
                .sortedBy { it.name }
                .toTypedArray()
            is MutatedFile -> element.mutants
                .filter { !escapedOnly || it.status == MutantStatus.ESCAPED }
                .sortedBy { it.start ?: Int.MAX_VALUE }
                .toTypedArray()
            else -> emptyArray()
        }

        override fun getParentElement(element: Any): Any? = when (element) {
            is Mutant -> element.file
            is MutatedFile -> Root
            else -> null
        }

        override fun createDescriptor(element: Any, parentDescriptor: NodeDescriptor<*>?): NodeDescriptor<*> =
            Node(element, parentDescriptor)

        override fun isAlwaysLeaf(element: Any): Boolean = element is Mutant

        override fun commit() = Unit

        override fun hasSomethingToCommit(): Boolean = false
    }

    private inner class Node(private val value: Any, parent: NodeDescriptor<*>?) :
        PresentableNodeDescriptor<Any>(project, parent) {

        override fun getElement(): Any = value

        override fun update(presentation: PresentationData) {
            when (value) {
                is MutatedFile -> {
                    presentation.setIcon(TestoIcons.PHP.FILE)
                    presentation.addText(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val statuses = value.mutants.mapNotNull { it.status }
                    val escaped = statuses.count { it == MutantStatus.ESCAPED }
                    if (escaped > 0) {
                        presentation.addText("  " + TestoBundle.message("infection.node.escaped", escaped.toString()), ESCAPED)
                    }
                    presentation.addText(
                        "  " + TestoBundle.message("infection.node.total", statuses.size.toString(), value.mutants.size.toString()),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is Mutant -> {
                    val status = value.status
                    presentation.setIcon(
                        when {
                            status != null -> status.icon
                            run.isRunning -> MutantStatus.RUNNING_ICON
                            else -> MutantStatus.UNFINISHED_ICON
                        }
                    )
                    presentation.addText(value.mutator, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    position(value)?.let {
                        presentation.addText("  " + TestoBundle.message("infection.node.line", (it.first + 1).toString()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    status?.let { presentation.addText("  ${it.label}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    presentation.tooltip = value.mutatorClass
                }
            }
        }
    }

    /** Line starts of a file, by byte offset. */
    private class LineIndex(private val bytes: ByteArray) {
        private val starts: IntArray = buildList {
            add(0)
            bytes.forEachIndexed { i, b -> if (b == '\n'.code.toByte()) add(i + 1) }
        }.toIntArray()

        fun position(offset: Int): Pair<Int, Int> {
            val bounded = offset.coerceIn(0, bytes.size)
            val found = starts.binarySearch(bounded)
            val line = if (found >= 0) found else -found - 2
            val start = starts[line]
            return line to String(bytes, start, bounded - start, Charsets.UTF_8).length
        }
    }

    companion object {
        const val PLACE = "TestoMutations"
        const val TOOLBAR_GROUP = "Testo.Mutations.Toolbar"
        const val POPUP_GROUP = "Testo.Mutations.Popup"

        @JvmField
        val PANEL: DataKey<TestoMutationPanel> = DataKey.create("testo.mutations.panel")

        private const val TEXT_CARD = "text"
        private const val DIFF_CARD = "diff"
        private const val REFRESH_MS = 300
        private const val EXPAND_LIMIT = 500

        private val ESCAPED = SimpleTextAttributes.ERROR_ATTRIBUTES
    }
}
