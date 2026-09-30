package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.coverage.format.TestId
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.pom.Navigatable
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.ui.JBColor
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.IntUnaryOperator
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.TreePath

/** One mutation run: the files Infection mutated, their mutants, and whatever the selected one has to show. */
class TestoMutationPanel(val project: Project, val run: TestoMutationRun) :
    SimpleToolWindowPanel(true, true), UiDataProvider, Disposable {

    var escapedOnly = false
        set(value) {
            field = value
            structureModel.invalidateAsync()
        }

    /** What the tree groups mutants under; kept for the next tab and the next session. */
    var grouping: TestoMutationGrouping = TestoMutationGrouping.stored()
        set(value) {
            field = value
            TestoMutationGrouping.store(value)
            expanded = 0
            structureModel.invalidateAsync().thenRun { UIUtil.invokeLaterIfNeeded(::expandWhileSmall) }
        }

    /** Mutators, by short name, whose mutants the tree leaves out. */
    var hiddenMutators: Set<String> = emptySet()
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
    internal val diff = DiffManager.getInstance().createRequestPanel(project, this, null)
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        border = JBUI.Borders.empty(6, 8)
    }
    internal val outputPane = ScrollPaneFactory.createScrollPane(output, true)
    private val diffSplitter = OnePixelSplitter(true, 0.6f)
    private val mainSplitter = OnePixelSplitter(true, 0.55f)
    private var diffShown = false
    private var shown: Any? = null

    private val lines = ConcurrentHashMap<String, TestoByteLines>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val scheduled = AtomicBoolean()
    private val dirty = AtomicBoolean(true)
    private var expanded = 0
    private val listener: () -> Unit = {
        dirty.set(true)
        schedule()
    }

    // Files whose code is no longer what Infection mutated, by the interpreter's path: their mutants may not match it.
    private val changed = ConcurrentHashMap.newKeySet<String>()

    // Mutants written into their file with Apply Mutation, by node id: told off the file's text, so an undo counts too.
    private val applied = ConcurrentHashMap.newKeySet<String>()
    private val checkAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var checkedFinished = false

    init {
        // One text area under the diff for every selection: a second one stacked on it for the log showed through the diff.
        diffSplitter.firstComponent = diff.component
        diffSplitter.secondComponent = outputPane
        mainSplitter.secondComponent = diffSplitter

        mainSplitter.firstComponent = JPanel(BorderLayout()).apply {
            add(summary, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        setContent(mainSplitter)
        layoutDetails()
        TestoMutationDetails.addListener(this, ::layoutDetails)
        toolbar = createToolbar()

        EditSourceOnDoubleClickHandler.install(tree)
        EditSourceOnEnterKeyHandler.install(tree)
        PopupHandler.installPopupMenu(tree, POPUP_GROUP, ActionPlaces.getPopupPlace(PLACE))
        tree.addTreeSelectionListener { showDetails() }

        run.addListener(listener)
        Disposer.register(this) { run.removeListener(listener) }
        watchChanges()
        refresh()
    }

    private fun watchChanges() {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val paths = localPaths()
                if (events.any { it.path in paths }) requestCheck()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.path in localPaths()) requestCheck()
            }
        }, this)
    }

    /** Selects [mutant]'s row, first letting it through any toggle that hides it. */
    fun select(mutant: Mutant) {
        if (!shown(mutant)) {
            escapedOnly = false
            hiddenMutators = emptySet()
        }
        structureModel.select(mutant, tree) { path -> tree.scrollPathToVisible(path) }
    }

    /** Whether [file]'s code is no longer what Infection mutated, as last checked. */
    internal fun isChanged(file: MutatedFile): Boolean = file.path in changed

    private fun localPaths(): Set<String> =
        run.files.mapNotNullTo(HashSet()) { file -> run.localPath(file.path)?.let(FileUtil::toSystemIndependentName) }

    private fun requestCheck() {
        checkAlarm.cancelAllRequests()
        checkAlarm.addRequest(::checkChanges, CHECK_MS)
    }

    // An unsaved edit counts: the code under the caret is what the user reads the mutants against.
    private fun checkChanges() {
        val now = run.files.filter { file ->
            val expected = run.fingerprints[file.path] ?: return@filter false
            val local = run.localPath(file.path) ?: return@filter false
            val virtual = LocalFileSystem.getInstance().findFileByPath(local)
            val unsaved = virtual != null && ApplicationManager.getApplication().runReadAction(Computable { FileDocumentManager.getInstance().isFileModified(virtual) })
            unsaved || fingerprintOf(Path.of(local)) != expected
        }.mapTo(HashSet()) { it.path }
        val appliedNow = ApplicationManager.getApplication().runReadAction(Computable {
            run.files.filter { it.path in now }.flatMap { it.mutants }.filter { TestoMutationApply.isApplied(run, it) }.mapTo(HashSet()) { it.nodeId }
        })
        if (now == changed && appliedNow == applied) return
        changed.retainAll(now)
        changed.addAll(now)
        applied.retainAll(appliedNow)
        applied.addAll(appliedNow)
        lines.clear()
        listener()
    }

    private fun createToolbar(): JComponent {
        val manager = ActionManager.getInstance()
        val expander = DefaultTreeExpander(tree)
        val group = DefaultActionGroup().apply {
            manager.getAction(TOOLBAR_GROUP)?.let(::add)
            addSeparator()
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
            add(TestoMutationViewOptionsGroup())
        }
        return manager.createActionToolbar(PLACE, group, true).apply { targetComponent = this@TestoMutationPanel }.component
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) alarm.addRequest(::refresh, REFRESH_MS)
    }

    private fun refresh() {
        scheduled.set(false)
        if (dirty.getAndSet(false)) structureModel.invalidateAsync().thenRun { UIUtil.invokeLaterIfNeeded(::expandWhileSmall) }
        summary.icon = if (run.isBusy) MutantStatus.RUNNING_ICON else verdictIcon()
        summary.text = summaryText()
        showDetails()
        if (run.isBusy) schedule()
        else if (!checkedFinished) {
            checkedFinished = true
            requestCheck()
        }
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
            is Mutant -> "m|${element.nodeId}|${element.file.path in changed}|${element.nodeId in applied}|${element.status}|${element.previousStatus}|${element.rerunning}|${original != null}|${element.firstLine}|${element.output != null}"
            is MutatedFile -> "f|${element.nodeId}|${element.path in changed}|${element.mutants.count { it.finished }}"
            is MutantGroup -> "g|${element.grouping}|${element.key}|${mutantsOf(element).count { it.finished }}"
            else -> run.log()
        }
        if (key == shown) return
        shown = key

        if (mutant != null && original != null && mutated != null) {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(mutant.file.path)
            val factory = DiffContentFactory.getInstance()
            diff.setRequest(
                SimpleDiffRequest(
                    "${mutant.mutator} · ${mutant.file.name}",
                    numbered(factory.create(project, original, fileType), mutant.firstLine),
                    numbered(factory.create(project, mutated, fileType), mutant.firstLine),
                    TestoBundle.message("infection.diff.original"),
                    TestoBundle.message("infection.diff.mutant"),
                ).apply {
                    // The diff's own toolbar: Apply turns into Revert once the file reads the mutant.
                    ActionManager.getInstance().getAction(APPLY_ACTION)?.let { putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(it)) }
                }
            )
            output.text = describe(mutant)
            output.caretPosition = 0
            diffShown = true
            layoutDetails()
            loadTests(mutant, key)
            return
        }
        if (mutant != null) loadTests(mutant, key)
        output.text = when (element) {
            is Mutant -> describe(element)
            is MutatedFile -> describe(element)
            is MutantGroup -> describe(element)
            else -> run.log()
        }
        output.caretPosition = 0
        diffShown = false
        layoutDetails()
    }

    // Parts are hidden rather than taken out: moving the diff panel in and out of the hierarchy redrew it over and over.
    private fun layoutDetails() {
        val showDiff = TestoMutationDetails.showDiff
        val showOutput = TestoMutationDetails.showOutput
        diff.component.isVisible = showDiff && diffShown
        outputPane.isVisible = showOutput
        diffSplitter.isVisible = showOutput || diffShown && showDiff
        diffSplitter.revalidate()
        mainSplitter.revalidate()
    }

    // The snippet is a few lines of the file; its gutter counts them where the file has them.
    private fun numbered(content: DocumentContent, firstLine: Int?): DocumentContent {
        if (firstLine != null && firstLine > 1) {
            content.putUserData(DiffUserDataKeysEx.LINE_NUMBER_CONVERTOR, IntUnaryOperator { it + firstLine - 1 })
        }
        return content
    }

    // Off the EDT: the first mutant of a file reads that file's coverage report.
    private fun loadTests(mutant: Mutant, key: Any) {
        val service = TestoMutationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val tests = service.testsOf(run, mutant)
            if (tests.covering.isEmpty() && tests.killing.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (shown != key) return@invokeLater
                output.text = describe(mutant, tests)
                output.caretPosition = 0
            }, project.disposed)
        }
    }

    private fun describe(mutant: Mutant, tests: TestoMutantTests? = null): String = buildString {
        appendLine(mutant.mutatorClass)
        appendLine(mutant.status?.label ?: TestoBundle.message("infection.status.running"))
        mutant.previousStatus?.takeIf { it != mutant.status }?.let { appendLine(TestoBundle.message("infection.details.before", it.label)) }
        val position = position(mutant)
        appendLine(if (position != null) "${mutant.file.name}:${position.first + 1}" else mutant.file.name)
        appendLine(TestoBundle.message("infection.details.id", mutant.hash))
        when {
            mutant.nodeId in applied -> appendLine(TestoBundle.message("infection.details.applied"))
            mutant.file.path in changed -> appendLine(TestoBundle.message("infection.file.changed"))
        }
        mutant.durationMs?.let { appendLine(TestoBundle.message("infection.details.duration", it.toString())) }
        tests?.killing?.takeIf { it.isNotEmpty() }?.let { appendTests(TestoBundle.message("infection.details.killedBy", it.size), it) }
        tests?.covering?.takeIf { it.isNotEmpty() }?.let { appendTests(TestoBundle.message("infection.details.coveredBy", it.size), it) }
        val output = mutant.output
        when {
            !output.isNullOrBlank() -> appendLine().appendLine(output)
            output == null && (run.isRunning || mutant.rerunning) -> appendLine().appendLine(TestoBundle.message("infection.details.outputPending"))
        }
    }

    private fun StringBuilder.appendTests(title: String, tests: List<TestId>) {
        appendLine().appendLine(title)
        tests.forEach { appendLine("  ${it.fqcn}::${it.method}") }
    }

    private fun describe(file: MutatedFile): String = buildString {
        appendLine(file.name)
        if (file.path in changed) appendLine(TestoBundle.message("infection.file.changed"))
        file.mutants.mapNotNull { it.status }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.ordinal }
            .forEach { (status, count) -> appendLine("${status.label}: $count") }
    }

    private fun describe(group: MutantGroup): String = buildString {
        val title = when (group.grouping) {
            TestoMutationGrouping.STATUS -> MutantStatus.entries.firstOrNull { it.name == group.key }?.label
                ?: TestoBundle.message("infection.status.running")
            else -> group.key
        }
        appendLine(title)
        mutantsOf(group).mapNotNull { it.status }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key.ordinal }
            .forEach { (status, count) -> appendLine("${status.label}: $count") }
    }

    private fun localFile(file: MutatedFile) = run.localPath(file.path)?.let(LocalFileSystem.getInstance()::findFileByPath)

    /** 0-based line and column of the mutated code. */
    private fun position(mutant: Mutant): Pair<Int, Int>? {
        val offset = mutant.start ?: return null
        val local = run.localPath(mutant.file.path) ?: return null
        val index = lines[local]
            ?: runCatching { TestoByteLines(Files.readAllBytes(Path.of(local))) }.getOrNull()?.also { lines[local] = it }
            ?: return null
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
        sink[SELECTED_MUTANTS] = selectedMutants()
        val element = selected()
        sink.lazy(CommonDataKeys.NAVIGATABLE) { navigatable(element) }
    }

    override fun dispose() = Unit

    private object Root

    /** The mutants the toggles let through: escaped only, and not of a mutator filtered out. */
    private fun shown(mutant: Mutant) = (!escapedOnly || escapedOrWas(mutant)) && mutant.mutator !in hiddenMutators

    // A mutant rerun from this view stays in it, so its way from escaped to killed can be seen.
    private fun escapedOrWas(mutant: Mutant) =
        mutant.status == MutantStatus.ESCAPED || mutant.previousStatus == MutantStatus.ESCAPED || mutant.rerunning

    private fun groupOf(mutant: Mutant): Any = when (grouping) {
        TestoMutationGrouping.FILE -> mutant.file
        TestoMutationGrouping.MUTATOR -> MutantGroup(grouping, mutant.mutator)
        TestoMutationGrouping.STATUS -> MutantGroup(grouping, mutant.status?.name.orEmpty())
    }

    private fun mutantsOf(group: MutantGroup): List<Mutant> = run.mutants.filter { shown(it) && groupOf(it) == group }

    private inner class Structure : AbstractTreeStructure() {
        override fun getRootElement(): Any = Root

        override fun getChildElements(element: Any): Array<Any> = when (element) {
            Root -> when (grouping) {
                TestoMutationGrouping.FILE -> run.files.filter { file -> file.mutants.any(::shown) }.sortedBy { it.name }
                TestoMutationGrouping.MUTATOR -> run.mutants.filter(::shown).map(::groupOf).distinct()
                    .sortedBy { (it as MutantGroup).key }
                TestoMutationGrouping.STATUS -> run.mutants.filter(::shown).map(::groupOf).distinct()
                    .sortedBy { group -> MutantStatus.entries.indexOfFirst { it.name == (group as MutantGroup).key } }
            }.toTypedArray()
            is MutatedFile -> element.mutants.filter(::shown).sortedBy { it.start ?: Int.MAX_VALUE }.toTypedArray()
            is MutantGroup -> mutantsOf(element)
                .sortedWith(compareBy<Mutant> { it.file.name }.thenBy { it.start ?: Int.MAX_VALUE })
                .toTypedArray()
            else -> emptyArray()
        }

        override fun getParentElement(element: Any): Any? = when (element) {
            is Mutant -> groupOf(element)
            is MutatedFile, is MutantGroup -> Root
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
                    if (value.path in changed) {
                        presentation.addText("  " + TestoBundle.message("infection.node.changed"), CHANGED)
                        presentation.tooltip = TestoBundle.message("infection.file.changed")
                    }
                }
                is MutantGroup -> {
                    val status = MutantStatus.entries.firstOrNull { it.name == value.key }
                    when (value.grouping) {
                        TestoMutationGrouping.STATUS -> {
                            presentation.setIcon(status?.icon ?: MutantStatus.RUNNING_ICON)
                            presentation.addText(
                                status?.label ?: TestoBundle.message("infection.status.running"),
                                SimpleTextAttributes.REGULAR_ATTRIBUTES,
                            )
                        }
                        else -> {
                            presentation.setIcon(AllIcons.Nodes.Function)
                            presentation.addText(value.key, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        }
                    }
                    val mutants = mutantsOf(value)
                    val escaped = mutants.count { it.status == MutantStatus.ESCAPED }
                    if (escaped > 0 && value.grouping != TestoMutationGrouping.STATUS) {
                        presentation.addText("  " + TestoBundle.message("infection.node.escaped", escaped.toString()), ESCAPED)
                    }
                    presentation.addText("  ${mutants.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Mutant -> {
                    val status = value.status
                    presentation.setIcon(
                        when {
                            status != null -> status.icon
                            run.isRunning || value.rerunning -> MutantStatus.RUNNING_ICON
                            else -> MutantStatus.UNFINISHED_ICON
                        }
                    )
                    // Under a file the mutator names the row; under a mutator or a status the file has to.
                    val title = when (grouping) {
                        TestoMutationGrouping.FILE -> value.mutator
                        TestoMutationGrouping.MUTATOR -> value.file.name
                        TestoMutationGrouping.STATUS -> "${value.mutator} · ${value.file.name}"
                    }
                    presentation.addText(title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    position(value)?.let {
                        presentation.addText("  " + TestoBundle.message("infection.node.line", (it.first + 1).toString()), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    val previous = value.previousStatus
                    val label = when {
                        previous == null || previous == status -> status?.label
                        else -> TestoBundle.message(
                            "infection.node.change",
                            previous.label,
                            status?.label ?: TestoBundle.message("infection.status.running"),
                        )
                    }
                    label?.let { presentation.addText("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    if (value.nodeId in applied) {
                        presentation.addText("  " + TestoBundle.message("infection.node.applied"), CHANGED)
                    } else if (grouping != TestoMutationGrouping.FILE && value.file.path in changed) {
                        presentation.addText("  " + TestoBundle.message("infection.node.changed"), CHANGED)
                    }
                    presentation.tooltip = value.mutatorClass
                }
            }
        }
    }

    companion object {
        const val PLACE = "TestoMutations"
        const val TOOLBAR_GROUP = "Testo.Mutations.Toolbar"
        const val POPUP_GROUP = "Testo.Mutations.Popup"
        const val APPLY_ACTION = "Testo.Mutations.Apply"

        @JvmField
        val PANEL: DataKey<TestoMutationPanel> = DataKey.create("testo.mutations.panel")

        /** The selection, taken on the EDT: for actions that update in the background. */
        @JvmField
        val SELECTED_MUTANTS: DataKey<List<Mutant>> = DataKey.create("testo.mutations.selected")

        private const val REFRESH_MS = 300
        private const val EXPAND_LIMIT = 500
        private const val CHECK_MS = 300

        private val ESCAPED = SimpleTextAttributes.ERROR_ATTRIBUTES
        private val CHANGED = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_ITALIC,
            JBColor.namedColor("Label.warningForeground", JBColor(0xA8631E, 0xD9A343)),
        )
    }
}

/** What the *Mutations* tree groups mutants under. */
enum class TestoMutationGrouping(private val labelKey: String) {
    FILE("infection.grouping.file"),
    MUTATOR("infection.grouping.mutator"),
    STATUS("infection.grouping.status");

    val label: String get() = TestoBundle.message(labelKey)

    companion object {
        private const val KEY = "testo.mutations.grouping"

        fun stored(): TestoMutationGrouping =
            PropertiesComponent.getInstance().getValue(KEY)?.let { name -> entries.firstOrNull { it.name == name } } ?: FILE

        fun store(grouping: TestoMutationGrouping) = PropertiesComponent.getInstance().setValue(KEY, grouping.name, FILE.name)
    }
}

/** Which parts under the *Mutations* tree are shown, in every tab; kept for the next session. */
internal object TestoMutationDetails {
    private const val DIFF_KEY = "testo.mutations.details.diff"
    private const val OUTPUT_KEY = "testo.mutations.details.output"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var showDiff: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(DIFF_KEY, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(DIFF_KEY, value, true)
            listeners.forEach { it() }
        }

    /** The raw text: a mutant's details and test output, a file's or group's summary, the run's log. */
    var showOutput: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(OUTPUT_KEY, false)
        set(value) {
            PropertiesComponent.getInstance().setValue(OUTPUT_KEY, value, false)
            listeners.forEach { it() }
        }

    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }
}

/** The mutants of one mutator, or of one status (its name; empty while still running), under the tree's root. */
internal data class MutantGroup(val grouping: TestoMutationGrouping, val key: String)
