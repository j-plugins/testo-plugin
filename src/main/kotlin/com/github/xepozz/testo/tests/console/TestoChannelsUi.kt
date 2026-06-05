package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.filters.CompositeFilter
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.IconUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLayeredPane
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.lang.reflect.Field
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JViewport
import javax.swing.Scrollable

// The label shown for a test in aggregated channel output (the per-test header / hyperlink). Pure string logic, kept
// top-level so it is unit-testable without the IDE platform. Handles:
//  - a class method:            php_qn://file::\Ns\Class::method            -> \Ns\Class::method
//  - a root-namespace method:   php_qn://file::\Class::method               -> \Class::method
//  - a standalone function:     php_qn://file::\Ns\func (or \func)          -> \Ns\func (no doubling of the short name)
//  - a dataset:                 php_qn://file::\Class::method with data set #N (presentable "Dataset #0:0 [0]")
//                               -> \Class::method:Dataset #0:0 [0]  (Testo's " with data set #N" suffix is dropped in
//                                  favour of the dataset's own presentable name, which already carries the index/value)
internal fun testoDisplayName(locationUrl: String?, presentableName: String): String {
    val raw = locationUrl
        ?.substringAfter("://", "")
        ?.substringAfter("::", "")
        ?.takeIf { it.isNotBlank() }
        ?: return presentableName
    val fqn = raw.substringBefore(" with data set")
    val method = fqn.substringAfterLast("::").substringAfterLast('\\')
    return if (presentableName == method) fqn else "$fqn:$presentableName"
}

object TestoChannelsUi {
    // The platform console lives in TestResultsPanel.myConsole with no public accessor; reach it via reflection.
    private val myConsoleField: Field? = runCatching {
        Class.forName("com.intellij.execution.testframework.ui.TestResultsPanel")
            .getDeclaredField("myConsole")
            .apply { isAccessible = true }
    }.getOrNull()

    fun install(
        console: SMTRunnerConsoleView,
        store: ChannelOutputStore,
        levelFilter: LogLevelFilter,
        project: Project,
        parent: Disposable,
        // Imports pass the root proxy to render the whole tree deterministically; a live run leaves this null and uses
        // whatever is selected (nothing yet at startup).
        initialSelection: SMTestProxy? = null,
    ) {
        val field = myConsoleField
        if (field == null) {
            thisLogger().warn("Testo channels disabled: TestResultsPanel.myConsole not found")
            return
        }
        val controller = ChannelTabsController(project, store, levelFilter, console, field)
        Disposer.register(parent, controller)
        val viewer = console.resultsViewer
        viewer.addEventsListener(controller)
        // addEventsListener only forwards FUTURE tree-selection changes (it installs a TreeSelectionListener and never
        // replays the current selection). A live run selects nodes after we attach, so that's fine — but an imported
        // console is already populated by the time the augmenter hands it to us, so the channel view would stay empty
        // until the user clicks. Render now. For imports we pass the root explicitly rather than reading
        // treeView.selectedTest, because the tree selection is applied asynchronously (AsyncTreeModel) and is commonly
        // still null at this instant. SMTestRunnerResultsForm is both the TestResultsViewer and the model onSelected
        // needs. invokeLater lets the holder swap in ensureInstalled() settle first.
        (viewer as? SMTestRunnerResultsForm)?.let { form ->
            val proxy = initialSelection ?: (form.treeView?.selectedTest as? SMTestProxy)
            ApplicationManager.getApplication().invokeLater { controller.onSelected(proxy, form, form) }
        }
    }

    private class ChannelTabsController(
        private val project: Project,
        private val store: ChannelOutputStore,
        private val levelFilter: LogLevelFilter,
        private val console: SMTRunnerConsoleView,
        private val myConsoleField: Field,
    ) : TestResultsViewer.EventsListener, Disposable {

        private var tabs: JBEditorTabs? = null
        private var outputComponent: JComponent? = null
        private val dynamicConsoles = mutableListOf<ConsoleViewImpl>()
        private val subscriptions = mutableListOf<() -> Unit>()
        // A per-parent live stream: console (LiveAggregate) or syntax-highlighted cards (CardsAggregate). Late leaves
        // (onTestNodeAdded) are pushed into every active stream.
        private val activeAggregates = mutableListOf<LeafStream>()
        private val activeCards = mutableListOf<MessageCards>()
        private var currentSelected: SMTestProxy? = null
        private var currentModel: TestFrameworkRunningModel? = null
        private var currentViewer: TestResultsViewer? = null

        init {
            // Toggling a log level rebuilds the shown tabs: hidden-only channels disappear, re-enabled ones return.
            levelFilter.onChange = {
                ApplicationManager.getApplication().invokeLater {
                    val viewer = currentViewer
                    val model = currentModel
                    if (viewer != null && model != null) onSelected(currentSelected, viewer, model)
                }
            }
        }

        override fun onTestingStarted(viewer: TestResultsViewer) {
            store.clear()
            ensureInstalled()
        }

        override fun onSelected(
            selected: SMTestProxy?,
            viewer: TestResultsViewer,
            model: TestFrameworkRunningModel,
        ) {
            val tabbed = ensureInstalled() ?: return
            val platform = outputComponent ?: return
            tabbed.removeAllTabs()
            disposeDynamicConsoles()
            currentSelected = selected
            currentModel = model
            currentViewer = viewer

            if (selected == null) {
                addComponentTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, platform)
                return
            }

            if (!selected.isLeaf) {
                val leaves = selected.allTests.filter { it !== selected && it.isLeaf }

                // All: syntax-highlighted cards, language picked per message from its own channel.
                val allCards = newCards(null)
                store.header().forEach { allCards.add(it) }
                addCardsAggregate(allCards, viewer, leaves) { key, sink -> store.attachAll(key, sink) }
                addComponentTab(tabbed, ALL_TAB, AllIcons.Actions.Show, allCards.component)

                addAggregateTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, viewer, leaves, attach = store::attachOutput)

                // Every channel renders as cards (one per test, that test's messages merged); only the Output tab above
                // stays a console. A language channel highlights each card; a format-less one keeps its ANSI.
                for (channel in channelsAcross(leaves)) {
                    if (!channelHasVisible(leaves, channel)) continue
                    val sample = leaves.firstNotNullOfOrNull { leaf ->
                        keyOf(leaf)?.let { store.channelsFor(it)[channel] }?.takeIf { it.isNotEmpty() }
                    } ?: emptyList()
                    val cards = newCards(channelFileType(channel))
                    addCardsAggregate(cards, viewer, leaves) { key, sink -> store.attachChannel(key, channel, sink) }
                    addComponentTab(tabbed, humanize(channel), channelIcon(channel, sample), cards.component)
                }
                return
            }

            val key = keyOf(selected)
            val header = store.header()
            // All: highlighted cards (per-message language). Header chunks first, then the live "all" stream replays
            // and keeps appending, so a streaming test's messages show up as they arrive.
            if (key != null || header.isNotEmpty()) {
                val allCards = newCards(null)
                header.forEach { allCards.add(it) }
                if (key != null) subscriptions += store.attachAll(key) { allCards.add(it) }
                addComponentTab(tabbed, ALL_TAB, AllIcons.Actions.Show, allCards.component)
            }
            addComponentTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, platform)
            if (key != null) {
                for ((channel, chunks) in store.channelsFor(key)) {
                    if (chunks.none { levelFilter.isVisible(it.level) }) continue
                    val cards = newCards(channelFileType(channel))
                    subscriptions += store.attachChannel(key, channel) { cards.add(it) }
                    addComponentTab(tabbed, humanize(channel), channelIcon(channel, chunks), cards.component)
                }
            }
        }

        override fun onTestNodeAdded(viewer: TestResultsViewer, test: SMTestProxy) {
            // Fired off the EDT (test-events thread), so any console/tab mutation must hop to the EDT.
            if (!test.isLeaf) return
            ApplicationManager.getApplication().invokeLater {
                val current = currentSelected ?: return@invokeLater
                if (current.isLeaf || !isUnder(current, test)) return@invokeLater
                // A leaf that appears after its ancestor was selected must join the shown view:
                //  - if the ancestor is already an aggregate, just subscribe the new leaf (no rebuild);
                //  - if it was selected while still EMPTY it looked like a leaf and was rendered as one (no
                //    aggregates); now that it has a child it is a suite, so re-render it as an aggregate — that also
                //    creates the channel tabs an empty selection could not yet know about.
                if (activeAggregates.isEmpty()) {
                    currentModel?.let { onSelected(current, viewer, it) }
                } else {
                    activeAggregates.forEach { it.addLeaf(test) }
                }
            }
        }

        private fun isUnder(ancestor: SMTestProxy, node: SMTestProxy): Boolean {
            var parent: SMTestProxy? = node.parent
            while (parent != null) {
                if (parent === ancestor) return true
                parent = parent.parent
            }
            return false
        }

        override fun dispose() {
            levelFilter.onChange = null
            disposeDynamicConsoles()
            tabs = null
            outputComponent = null
        }

        private fun addTab(tabbed: JBEditorTabs, title: String, icon: Icon, view: ConsoleViewImpl?) {
            if (view != null) addComponentTab(tabbed, title, icon, view.component)
        }

        private fun addComponentTab(tabbed: JBEditorTabs, title: String, icon: Icon, component: JComponent) {
            tabbed.addTab(TabInfo(component).setText(title).setIcon(icon))
        }

        private fun addCardsAggregate(
            cards: MessageCards,
            viewer: TestResultsViewer,
            leaves: List<SMTestProxy>,
            attach: (String, (ChannelOutputStore.Chunk) -> Unit) -> (() -> Unit),
        ) {
            val aggregate = CardsAggregate(cards, viewer, attach)
            leaves.forEach { aggregate.addLeaf(it) }
            activeAggregates += aggregate
        }

        // The channel suffix after the last dot picks the language (query.sql -> SQL); null when there's no real
        // language to highlight (no suffix, or the language's plugin isn't installed) -> caller falls back to console.
        private fun channelFileType(channel: String?): FileType? {
            val ext = channel?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: return null
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
            return fileType.takeUnless { it is UnknownFileType || it is PlainTextFileType }
        }

        private fun disposeDynamicConsoles() {
            subscriptions.forEach { it() }
            subscriptions.clear()
            activeAggregates.clear()
            activeCards.forEach { it.release() }
            activeCards.clear()
            dynamicConsoles.forEach { Disposer.dispose(it) }
            dynamicConsoles.clear()
        }

        private fun newCards(fixedFileType: FileType?): MessageCards =
            MessageCards(fixedFileType).also { activeCards += it }

        private fun channelsAcross(leaves: List<SMTestProxy>): Set<String> {
            val channels = LinkedHashSet<String>()
            for (leaf in leaves) keyOf(leaf)?.let { channels.addAll(store.channelsFor(it).keys) }
            return channels
        }

        private fun channelHasVisible(leaves: List<SMTestProxy>, channel: String): Boolean =
            leaves.any { leaf ->
                keyOf(leaf)?.let { store.channelsFor(it)[channel] }?.any { levelFilter.isVisible(it.level) } == true
            }

        private fun channelIcon(channel: String, chunks: List<ChannelOutputStore.Chunk>): Icon {
            // A channel whose suffix maps to a real file type shows that type's file icon (query.sql -> the SQL icon).
            channelFileType(channel)?.icon?.let { return it }
            val color = channelColor(channel, chunks)
            val mapped = store.channelIcon(channel)?.lowercase()?.let { ChannelIcons.MAP[it] }
            if (mapped != null) return if (color != null) IconUtil.colorize(mapped, color) else mapped
            if (color == null) return AllIcons.General.Filter
            val index = ((channel.hashCode() % ICON_POOL_SIZE) + ICON_POOL_SIZE) % ICON_POOL_SIZE
            return if (index < BASE_ICONS.size) IconUtil.colorize(BASE_ICONS[index], color) else DotIcon(color)
        }

        private fun channelColor(channel: String, chunks: List<ChannelOutputStore.Chunk>): Color? {
            store.channelColor(channel)?.let { parseColor(it) }?.let { return it }
            val decoder = AnsiEscapeDecoder()
            return chunks.firstNotNullOfOrNull { chunk ->
                val outputType =
                    if (chunk.level == "stderr") ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
                var found: Color? = null
                decoder.escapeText(chunk.text, outputType) { text, key ->
                    if (found == null && text.isNotBlank() &&
                        key !== ProcessOutputTypes.STDOUT && key !== ProcessOutputTypes.STDERR) {
                        found = ConsoleViewContentType.getConsoleViewType(key).attributes.foregroundColor
                    }
                }
                found
            }
        }

        private fun parseColor(spec: String): Color? {
            val token = spec.trim()
            return runCatching {
                when {
                    token.startsWith("#") -> Color.decode(token)
                    token.matches(HEX_COLOR) -> Color.decode("#$token")
                    else -> NAMED_COLORS[token.lowercase()]
                }
            }.getOrNull()
        }

        // Tint a level label by PSR severity; unknown levels stay neutral gray.
        private fun levelColor(level: String): Color = when (level.lowercase()) {
            "emergency", "alert", "critical", "error", "stderr" -> NAMED_COLORS.getValue("red")
            "warning" -> NAMED_COLORS.getValue("orange")
            "notice" -> NAMED_COLORS.getValue("yellow")
            "info" -> NAMED_COLORS.getValue("blue")
            "debug" -> NAMED_COLORS.getValue("cyan")
            else -> JBColor.GRAY
        }

        private fun addAggregateTab(
            tabbed: JBEditorTabs,
            title: String,
            icon: Icon,
            viewer: TestResultsViewer,
            leaves: List<SMTestProxy>,
            prependHeader: Boolean = false,
            attach: (String, (ChannelOutputStore.Chunk) -> Unit) -> (() -> Unit),
        ) {
            val view = newConsole(emptyList())
            if (prependHeader) printChunks(view, store.header())
            val aggregate = LiveAggregate(view, viewer, attach)
            leaves.forEach { aggregate.addLeaf(it) }
            activeAggregates += aggregate
            addTab(tabbed, title, icon, view)
        }

        // A parent-node tab fed by several leaves; late leaves (onTestNodeAdded) are pushed in after selection.
        private interface LeafStream {
            fun addLeaf(leaf: SMTestProxy)
        }

        // Cards counterpart of LiveAggregate: each leaf's chunks become highlighted cards, tagged with the test name.
        private inner class CardsAggregate(
            private val cards: MessageCards,
            private val viewer: TestResultsViewer,
            private val attach: (String, (ChannelOutputStore.Chunk) -> Unit) -> (() -> Unit),
        ) : LeafStream {
            private val attached = HashSet<String>()

            override fun addLeaf(leaf: SMTestProxy) {
                val key = keyOf(leaf) ?: return
                if (!attached.add(key)) return
                subscriptions += attach(key) { chunk -> cards.add(chunk, fullName(leaf)) { selectInTree(viewer, leaf) } }
            }
        }

        /**
         * One parent-node tab: subscribes leaves' streams into a single console and appends as output arrives. A
         * leaf's hyperlinked header is printed lazily on its first chunk and re-printed when output switches to
         * another leaf, so replayed history and the live tail stay grouped per test (tests run sequentially, so
         * arrival order already matches that grouping). [addLeaf] also accepts leaves discovered after selection.
         */
        private inner class LiveAggregate(
            private val view: ConsoleViewImpl,
            private val viewer: TestResultsViewer,
            private val attach: (String, (ChannelOutputStore.Chunk) -> Unit) -> (() -> Unit),
        ) : LeafStream {
            private var lastKey: String? = null
            private val attached = HashSet<String>()

            override fun addLeaf(leaf: SMTestProxy) {
                val key = keyOf(leaf) ?: return
                if (!attached.add(key)) return
                val decoder = AnsiEscapeDecoder()
                subscriptions += attach(key) { chunk ->
                    if (!levelFilter.isVisible(chunk.level)) return@attach
                    if (lastKey != key) {
                        if (lastKey != null) view.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        val headerOffset = view.contentSize
                        view.printHyperlink(fullName(leaf), HyperlinkInfo { selectInTree(viewer, leaf) })
                        view.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        addTestoIconInlay(view, headerOffset)
                        lastKey = key
                    }
                    printChunk(decoder, view, chunk)
                }
            }
        }

        // A vertical, scrollable stack of read-only "cards" — one per message. Each card body is a real viewer editor
        // over a PSI-backed light file, so it renders with the full editor experience: language syntax highlighting,
        // folding and annotators. fixedFileType is set for single-language channel tabs; null means derive the language
        // per message from its own channel (used by the mixed All tab).
        private inner class MessageCards(private val fixedFileType: FileType?) {
            private val list = object : JBPanel<Nothing>(VerticalLayout(JBUI.scale(6))), Scrollable {
                override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
                override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
                override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(96)
                override fun getScrollableTracksViewportWidth() = true
                override fun getScrollableTracksViewportHeight() = false
            }.apply { border = JBUI.Borders.empty(4) }

            private val scroll = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                // No blit-copy scrolling: it leaves the sticky overlay un-repainted (it "disappears" until something
                // forces a full repaint). SIMPLE mode repaints the viewport each scroll so the overlay stays drawn.
                viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
            }

            // Sticky card header: the header of the message currently at the top stays pinned (like the editor's sticky
            // lines), pushed up as the next card's header arrives. Overlaid so it takes no layout space.
            private val sticky = JBPanel<Nothing>(BorderLayout()).apply {
                isVisible = false
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            }
            private var stickyOffsetY = 0
            private var stickyX = 0
            private var stickyWidth = 0
            private var stickyIdx = -1
            private val cardEntries = mutableListOf<CardEntry>()

            val component: JComponent = object : JBLayeredPane() {
                init {
                    add(scroll, JLayeredPane.DEFAULT_LAYER as Any)
                    add(sticky, JLayeredPane.PALETTE_LAYER as Any)
                }

                override fun getPreferredSize(): Dimension = scroll.preferredSize

                override fun doLayout() {
                    scroll.setBounds(0, 0, width, height)
                    sticky.setBounds(stickyX, stickyOffsetY, if (stickyWidth > 0) stickyWidth else width, sticky.preferredSize.height)
                }
            }

            init {
                scroll.viewport.addChangeListener { updateSticky() }
                // Cards stream in (the list grows) without a viewport move — refresh the pinned header then too.
                list.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) = updateSticky()
                })
            }

            private var index = 0
            private var released = false
            private var truncated = false
            private val editors = mutableListOf<EditorEx>()
            private val fileEditors = mutableListOf<Pair<FileEditorProvider, FileEditor>>()

            // The previous card, for folding consecutive format-less same-channel messages into one canvas.
            private var lastMergeKey: String? = null
            private var lastEditor: EditorEx? = null
            private var lastCard: JComponent? = null

            // Editors aren't released by merely removing their Swing component; the controller calls this on rebuild.
            // `released` also stops a chunk task queued just before this from materializing (and leaking) a new editor.
            fun release() {
                released = true
                editors.forEach { EditorFactory.getInstance().releaseEditor(it) }
                editors.clear()
                fileEditors.forEach { (provider, fileEditor) -> runCatching { provider.disposeEditor(fileEditor) } }
                fileEditors.clear()
            }

            // Chunks arrive on the test-reader thread (replay happens on the EDT inside onSelected); hop to the EDT for
            // the Swing/editor mutation. invokeLater is FIFO, so message order is preserved. onLeafClick, when set,
            // makes the per-test name in the header navigate back to that test in the tree.
            fun add(chunk: ChannelOutputStore.Chunk, leafLabel: String? = null, onLeafClick: (() -> Unit)? = null) {
                if (released || !levelFilter.isVisible(chunk.level)) return
                // Decode ANSI once: the plain text drives the blank check and the body; segments tint plain cards.
                val (plain, segments) = decodeAnsi(chunk.text.trim('\n'))
                if (plain.isBlank()) return
                val fileType = fixedFileType ?: channelFileType(chunk.channel)
                val app = ApplicationManager.getApplication()
                val task = Runnable {
                    if (released) return@Runnable
                    // Consecutive format-less messages from the same channel/test fold into one canvas: append to the
                    // previous card's editor instead of stacking another card.
                    val mergeKey = if (fileType == null) "${chunk.channel} $leafLabel" else null
                    val target = if (mergeKey != null && mergeKey == lastMergeKey) {
                        lastEditor?.takeUnless { it.isDisposed }
                    } else {
                        null
                    }
                    if (target != null) {
                        appendToEditor(target, plain, segments)
                        lastCard?.revalidate()
                        list.revalidate()
                        list.repaint()
                        return@Runnable
                    }
                    if (index >= MAX_CARDS) {
                        if (!truncated) {
                            truncated = true
                            list.add(truncationNotice())
                            list.revalidate()
                            list.repaint()
                        }
                        return@Runnable
                    }
                    val (component, editor) = card(++index, chunk, leafLabel, onLeafClick, fileType, plain, segments)
                    list.add(component)
                    lastMergeKey = mergeKey
                    lastEditor = if (mergeKey != null) editor else null
                    lastCard = component
                    list.revalidate()
                    list.repaint()
                }
                if (app.isDispatchThread) task.run() else app.invokeLater(task)
            }

            private fun truncationNotice(): JComponent =
                JBLabel("… further messages hidden (showing first $MAX_CARDS)").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(6)
                }

            private fun card(
                n: Int,
                chunk: ChannelOutputStore.Chunk,
                leafLabel: String?,
                onLeafClick: (() -> Unit)?,
                fileType: FileType?,
                plain: String,
                segments: List<AnsiSegment>,
            ): Pair<JComponent, EditorEx?> {
                // A language message gets that language's highlighting (ANSI dropped); a format-less message keeps its
                // ANSI colors over a plain-text viewer — and only those (mergeableEditor) fold into one canvas.
                val mergeableEditor: EditorEx?
                val body: JComponent
                if (fileType != null) {
                    body = previewCard(fileType, plain) ?: editorCard(fileType, plain, null).first
                    mergeableEditor = null
                } else {
                    val (component, editor) = editorCard(PlainTextFileType.INSTANCE, plain, segments)
                    body = component
                    mergeableEditor = editor
                }
                val wrapper = JBPanel<Nothing>(BorderLayout()).apply {
                    border = JBUI.Borders.customLine(JBColor.border(), 1)
                    add(buildHeader(n, chunk, leafLabel, onLeafClick), BorderLayout.NORTH)
                    add(withFloatingActions(body, mergeableEditor, plain), BorderLayout.CENTER)
                }
                // Register for the sticky overlay (rebuild the header on demand so its hyperlink/level stay live).
                cardEntries += CardEntry(wrapper) { buildHeader(n, chunk, leafLabel, onLeafClick) }
                return wrapper to mergeableEditor
            }

            // Header: #N · channel on the left, (in aggregate tabs) the test name as a tree-navigating link, and the log
            // level pinned to the right tinted by severity. Rebuilt fresh for both the card and the sticky overlay.
            private fun buildHeader(
                n: Int,
                chunk: ChannelOutputStore.Chunk,
                leafLabel: String?,
                onLeafClick: (() -> Unit)?,
            ): JComponent {
                val left = JBPanel<Nothing>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(3, 6, 2, 6)
                    val idText = buildString { append('#').append(n); chunk.channel?.let { append("  ·  ").append(it) } }
                    add(JBLabel(idText).apply { font = JBUI.Fonts.smallFont(); foreground = JBColor.GRAY })
                    if (leafLabel != null) {
                        add(JBLabel("  ·  ").apply { font = JBUI.Fonts.smallFont(); foreground = JBColor.GRAY })
                        add(HyperlinkLabel(leafLabel).apply { addHyperlinkListener { onLeafClick?.invoke() } })
                    }
                }
                return JBPanel<Nothing>(BorderLayout()).apply {
                    isOpaque = false
                    add(left, BorderLayout.WEST)
                    chunk.level?.takeIf { it.isNotBlank() }?.let { level ->
                        add(JBLabel(level).apply {
                            font = JBUI.Fonts.smallFont()
                            foreground = levelColor(level)
                            border = JBUI.Borders.empty(3, 8, 2, 8)
                        }, BorderLayout.EAST)
                    }
                }
            }

            // Recompute the pinned header on scroll: which card is at the top, and how far the next one has pushed it.
            private fun updateSticky() {
                val y = scroll.viewport.viewPosition.y
                val topIdx = cardEntries.indexOfLast { it.panel.y <= y }
                if (topIdx < 0 || y <= cardEntries[topIdx].panel.y) {
                    if (sticky.isVisible) {
                        sticky.isVisible = false
                        stickyIdx = -1
                        component.repaint()
                    }
                    return
                }
                if (topIdx != stickyIdx) {
                    stickyIdx = topIdx
                    sticky.removeAll()
                    sticky.add(cardEntries[topIdx].buildHeader(), BorderLayout.CENTER)
                    sticky.validate()
                }
                val stickyHeight = sticky.preferredSize.height
                val next = cardEntries.getOrNull(topIdx + 1)
                stickyOffsetY = if (next != null) (next.panel.y - y - stickyHeight).coerceAtMost(0) else 0
                // Align the overlay exactly over the card (the list's border + the card's own border shift it right),
                // so the pinned header doesn't drift sideways from where the real header sat.
                val panel = cardEntries[topIdx].panel
                stickyX = panel.x + panel.insets.left
                stickyWidth = (panel.width - panel.insets.left - panel.insets.right).coerceAtLeast(0)
                // Position immediately (don't wait for an async revalidate/doLayout) and force a repaint of the overlay.
                sticky.setBounds(stickyX, stickyOffsetY, stickyWidth, stickyHeight)
                sticky.isVisible = true
                component.repaint()
            }

            // A right-aligned floating action bar laid over the message body (for now a single Copy button — the
            // embedded viewer copies via Cmd+C but its right-click popup can't). Hidden until the card is hovered, then
            // faded in, so it never sits on top of the text when you aren't reaching for it.
            private fun withFloatingActions(body: JComponent, editor: EditorEx?, staticText: String): JComponent {
                val copy = InplaceButton(IconButton("Copy", AllIcons.Actions.Copy)) {
                    // Read the editor live so a merged (appended-to) card copies its full current text, not just the first message.
                    val text = editor?.takeUnless { it.isDisposed }?.document?.text ?: staticText
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }.apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
                val actions = object : JBPanel<Nothing>(FlowLayout(FlowLayout.RIGHT, 0, 0)) {
                    var alpha = 0f
                    init {
                        isOpaque = false
                        isVisible = false
                        add(copy)
                    }

                    override fun paint(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        try {
                            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
                            super.paint(g2)
                        } finally {
                            g2.dispose()
                        }
                    }
                }
                val layered = object : JBLayeredPane() {
                    init {
                        isOpaque = false
                        add(body, JLayeredPane.DEFAULT_LAYER as Any)
                        add(actions, JLayeredPane.PALETTE_LAYER as Any)
                    }

                    override fun getPreferredSize(): Dimension = body.preferredSize

                    override fun doLayout() {
                        body.setBounds(0, 0, width, height)
                        val size = actions.preferredSize
                        actions.setBounds(width - size.width - JBUI.scale(8), JBUI.scale(6), size.width, size.height)
                    }
                }

                var fade: javax.swing.Timer? = null
                fun fadeTo(target: Float) {
                    if (target > 0f) actions.isVisible = true
                    fade?.stop()
                    fade = javax.swing.Timer(16, null).apply {
                        addActionListener {
                            val delta = if (actions.alpha < target) FADE_STEP else -FADE_STEP
                            actions.alpha = (actions.alpha + delta).coerceIn(0f, 1f)
                            actions.repaint()
                            if (kotlin.math.abs(actions.alpha - target) <= FADE_STEP) {
                                actions.alpha = target
                                actions.repaint()
                                if (target == 0f) actions.isVisible = false
                                (it.source as javax.swing.Timer).stop()
                            }
                        }
                        start()
                    }
                }

                object : com.intellij.ui.hover.HoverListener() {
                    override fun mouseEntered(component: Component, x: Int, y: Int) = fadeTo(1f)
                    override fun mouseMoved(component: Component, x: Int, y: Int) = Unit
                    override fun mouseExited(component: Component) = fadeTo(0f)
                }.addTo(layered)

                return layered
            }

            // For a type whose own editor ships a preview (Markdown), embed that real editor — source + rendered
            // preview with the platform's layout toggle — instead of a plain source viewer. Returns null for types
            // without such an editor (the caller then uses the source viewer).
            private fun previewCard(fileType: FileType, text: String): JComponent? {
                // Gate cheaply by type so we never build a heavy editor just to learn the type has no preview, then
                // confirm by contract (the editor must actually be a TextEditorWithPreview) rather than provider FQN.
                if (!fileType.name.equals("Markdown", ignoreCase = true)) return null
                // Built on the EDT from a tree-selection event, which doesn't hold read access by default; provider
                // createEditor reads the document, so wrap in a read action.
                return WriteIntentReadAction.compute<JComponent?, RuntimeException> {
                    val ext = fileType.defaultExtension.ifBlank { "txt" }
                    val vFile = LightVirtualFile("testo-message-$index.$ext", fileType, text).apply { isWritable = false }
                    val providers = runCatching { FileEditorProviderManager.getInstance().getProviderList(project, vFile) }
                        .getOrNull().orEmpty()
                    // Pick the provider that actually yields a preview editor (not just the first registered one).
                    val previewEditor = providers.firstNotNullOfOrNull { provider ->
                        val fileEditor = runCatching { provider.createEditor(project, vFile) }.getOrNull()
                        when (fileEditor) {
                            is TextEditorWithPreview -> { fileEditors += provider to fileEditor; fileEditor }
                            null -> null
                            else -> { runCatching { provider.disposeEditor(fileEditor) }; null }
                        }
                    } ?: return@compute null
                    previewEditor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
                    val fileEditor: FileEditor = previewEditor
                    object : JBPanel<Nothing>(BorderLayout()) {
                        init {
                            isOpaque = false
                            add(fileEditor.component, BorderLayout.CENTER)
                        }

                        // The preview's height is HTML-driven and unknown up front; give the card a fixed, roomy height.
                        override fun getPreferredSize(): Dimension =
                            Dimension(super.getPreferredSize().width, JBUI.scale(260))
                    }
                }
            }

            // A real viewer editor (not EditorTextField, which restricts the popup so Copy is greyed out): read-only,
            // but with working selection/copy, a lexer highlighter, line numbers and folding.
            private fun editorCard(fileType: FileType, text: String, ansiSegments: List<AnsiSegment>?): Pair<JComponent, EditorEx> =
                // Built on the EDT from a tree-selection event, which (since the platform 2024+ threading model) does NOT
                // hold read access by default — getDocument/editor creation read the model, so wrap in a read action.
                WriteIntentReadAction.compute<Pair<JComponent, EditorEx>, RuntimeException> {
                val ext = fileType.defaultExtension.ifBlank { "txt" }
                val vFile = LightVirtualFile("testo-message-$index.$ext", fileType, text)
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                    ?: EditorFactory.getInstance().createDocument(text)
                val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
                editors += editor
                editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vFile)
                editor.setVerticalScrollbarVisible(false)
                // Keep the horizontal scrollbar (as-needed) so long lines can be scrolled — the card itself never
                // grows wider than the channel tab, and soft wraps are off.
                editor.setHorizontalScrollbarVisible(true)
                editor.setBorder(JBUI.Borders.empty(2, 0))
                editor.settings.apply {
                    isLineNumbersShown = true
                    isFoldingOutlineShown = true
                    isLineMarkerAreaShown = false
                    isIndentGuidesShown = false
                    isUseSoftWraps = false
                    isCaretRowShown = false
                    isVirtualSpace = false
                    isAdditionalPageAtBottom = false
                    isRightMarginShown = false
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                }
                ansiSegments?.let { applyAnsi(editor, it) }
                // Folding commits the PSI (a write); defer off the construction path to a non-modal invokeLater. Hook up
                // clickable file:line links here too (the console path got these from message filters).
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!editor.isDisposed) {
                            runCatching { CodeFoldingManager.getInstance(project).buildInitialFoldings(editor) }
                            attachHyperlinks(editor)
                        }
                    },
                    ModalityState.nonModal(),
                )
                val wrapper = object : JBPanel<Nothing>(BorderLayout()) {
                    init {
                        isOpaque = false
                        add(editor.component, BorderLayout.CENTER)
                    }

                    // Grow to fit every line; the outer scroll pane handles vertical overflow. Reserve room for the
                    // horizontal scrollbar when it's showing so it doesn't cover the last line.
                    override fun getPreferredSize(): Dimension {
                        val lines = editor.document.lineCount.coerceAtLeast(1)
                        var height = editor.lineHeight * lines + JBUI.scale(8)
                        editor.scrollPane.horizontalScrollBar?.takeIf { it.isVisible }?.let { height += it.preferredSize.height }
                        return Dimension(super.getPreferredSize().width, height)
                    }
                }
                wrapper to editor
            }

            // Append a format-less message to an existing card's editor (the "one canvas" merge) and tint its ANSI run.
            private fun appendToEditor(editor: EditorEx, plain: String, segments: List<AnsiSegment>) {
                val document = editor.document
                val base = document.textLength
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(base, "\n$plain") }
                applyAnsi(editor, segments, base + 1)
            }

            private fun applyAnsi(editor: EditorEx, segments: List<AnsiSegment>, base: Int = 0) {
                var offset = base
                val max = editor.document.textLength
                for (segment in segments) {
                    val end = (offset + segment.text.length).coerceAtMost(max)
                    if (segment.attributes != null && offset < end) {
                        editor.markupModel.addRangeHighlighter(
                            offset, end, HighlighterLayer.SYNTAX, segment.attributes, HighlighterTargetArea.EXACT_RANGE,
                        )
                    }
                    offset = end
                }
            }

            // Same filters the console path attached (PhpBacktraceFileFilter + ConsoleFilterProvider defaults), applied
            // to the embedded viewer so file:line references in stack traces / failure details stay clickable.
            private fun attachHyperlinks(editor: EditorEx) {
                val filters = buildList<Filter> {
                    add(PhpBacktraceFileFilter(project))
                    for (provider in ConsoleFilterProvider.FILTER_PROVIDERS.extensionList) {
                        runCatching { provider.getDefaultFilters(project) }.getOrNull()?.let { addAll(it) }
                    }
                }
                runCatching {
                    EditorHyperlinkSupport.get(editor)
                        .highlightHyperlinks(CompositeFilter(project, filters), 0, editor.document.lineCount)
                }
            }
        }

        private class AnsiSegment(val text: String, val attributes: TextAttributes?)

        // A rendered message card plus a factory that rebuilds its header (for the sticky overlay copy).
        private class CardEntry(val panel: JComponent, val buildHeader: () -> JComponent)

        // Splits ANSI-coloured text into the plain string plus per-run color attributes (null for the default color).
        private fun decodeAnsi(raw: String): Pair<String, List<AnsiSegment>> {
            val plain = StringBuilder()
            val segments = mutableListOf<AnsiSegment>()
            AnsiEscapeDecoder().escapeText(raw, ProcessOutputTypes.STDOUT) { text, key ->
                val attributes = if (key === ProcessOutputTypes.STDOUT) null
                else ConsoleViewContentType.getConsoleViewType(key).attributes
                segments += AnsiSegment(text, attributes)
                plain.append(text)
            }
            return plain.toString() to segments
        }

        private fun selectInTree(viewer: TestResultsViewer, leaf: SMTestProxy) {
            val properties = console.properties
            if (leaf.isPassed && TestConsoleProperties.HIDE_PASSED_TESTS.value(properties)) {
                TestConsoleProperties.HIDE_PASSED_TESTS.set(properties, false)
                ApplicationManager.getApplication().invokeLater { viewer.selectAndNotify(leaf) }
            } else {
                viewer.selectAndNotify(leaf)
            }
        }

        private fun fullName(leaf: SMTestProxy): String = testoDisplayName(leaf.locationUrl, leaf.presentableName)

        private fun keyOf(leaf: SMTestProxy): String? = store.keyFor(leaf.name)

        private fun humanize(channel: String): String =
            channel.replace('-', ' ').replace('_', ' ').trim()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        private fun newConsole(chunks: List<ChannelOutputStore.Chunk>): ConsoleViewImpl {
            val view = ConsoleViewImpl(project, GlobalSearchScope.allScope(project), true, false)
            Disposer.register(this, view)
            dynamicConsoles.add(view)
            attachFilters(view)
            view.component
            printChunks(view, chunks)
            return view
        }

        private fun attachFilters(view: ConsoleViewImpl) {
            view.addMessageFilter(PhpBacktraceFileFilter(project))
            for (provider in ConsoleFilterProvider.FILTER_PROVIDERS.extensionList) {
                runCatching { provider.getDefaultFilters(project) }.getOrNull()?.forEach { view.addMessageFilter(it) }
            }
        }

        private fun printChunks(view: ConsoleViewImpl, chunks: List<ChannelOutputStore.Chunk>) {
            val decoder = AnsiEscapeDecoder()
            for (chunk in chunks) if (levelFilter.isVisible(chunk.level)) printChunk(decoder, view, chunk)
        }

        private fun printChunk(decoder: AnsiEscapeDecoder, view: ConsoleViewImpl, chunk: ChannelOutputStore.Chunk) {
            val outputType = if (chunk.level == "stderr") ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
            decoder.escapeText(chunk.text, outputType) { text, key ->
                view.print(text, ConsoleViewContentType.getConsoleViewType(key))
            }
        }

        // Prefix the aggregate's per-test header (a hyperlink to the test) with the Testo icon. Console output is
        // buffered, so the editor offset only resolves once it is flushed — hence performWhenNoDeferredOutput.
        private fun addTestoIconInlay(view: ConsoleViewImpl, offset: Int) {
            view.performWhenNoDeferredOutput {
                val editor = view.editor as? EditorEx ?: return@performWhenNoDeferredOutput
                if (offset in 0..editor.document.textLength) {
                    editor.inlayModel.addInlineElement(offset, false, TestoIconInlayRenderer)
                }
            }
        }

        private fun ensureInstalled(): JBEditorTabs? {
            tabs?.let { return it }
            val original = myConsoleField.get(console.resultsViewer) as? JComponent ?: run {
                thisLogger().warn("Testo channels disabled: TestResultsPanel.myConsole is not a JComponent")
                return null
            }
            val holder = original.parent ?: return null
            if (holder.layout !is BorderLayout) {
                thisLogger().warn("Testo channels disabled: unexpected console holder layout ${holder.layout}")
                return null
            }

            holder.remove(original)
            // The editor's own tabs widget: one row that scrolls and shows a "hidden tabs" dropdown when the channels
            // don't fit, instead of wrapping to extra rows like JBTabbedPane.
            val tabbed = JBEditorTabs(project, this)
            addComponentTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, original)
            holder.add(tabbed.component, BorderLayout.CENTER)
            holder.revalidate()
            holder.repaint()
            tabs = tabbed
            outputComponent = original
            return tabbed
        }

        companion object {
            private const val ALL_TAB = "All"
            private const val OUTPUT_TAB = "Output"
            private const val FADE_STEP = 0.18f

            // Each message is a full editor; cap how many we materialize so a chatty channel can't spawn thousands.
            private const val MAX_CARDS = 300

            private val BASE_ICONS = listOf(
                AllIcons.General.Filter,
                AllIcons.Actions.Lightning,
                AllIcons.Actions.Refresh,
                AllIcons.General.Add,
                AllIcons.General.Settings,
                AllIcons.General.Information,
                AllIcons.Vcs.Branch,
                AllIcons.Actions.Execute,
                AllIcons.Nodes.Tag,
                AllIcons.General.Note,
            )
            private val ICON_POOL_SIZE = BASE_ICONS.size + 1

            private val HEX_COLOR = Regex("[0-9a-fA-F]{6}")

            private val NAMED_COLORS: Map<String, Color> = mapOf(
                "black" to Color(0x555555),
                "red" to Color(0xCC4040),
                "green" to Color(0x59A869),
                "yellow" to Color(0xC8A415),
                "blue" to Color(0x3592C4),
                "magenta" to Color(0xB95EAA),
                "purple" to Color(0xB95EAA),
                "cyan" to Color(0x42A4A4),
                "white" to Color(0xBBBBBB),
                "gray" to Color(0x808080),
                "grey" to Color(0x808080),
                "orange" to Color(0xCC7832),
            )
        }
    }

    // Renders the Testo icon inline, vertically centered, with a small gap before the following text.
    private object TestoIconInlayRenderer : EditorCustomElementRenderer {
        private val icon get() = TestoIcons.TESTO

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = icon.iconWidth + JBUI.scale(4)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val y = targetRegion.y + (targetRegion.height - icon.iconHeight) / 2
            icon.paintIcon(inlay.editor.component, g, targetRegion.x, maxOf(targetRegion.y, y))
        }
    }

    private class DotIcon(private val color: Color) : Icon {
        private val size get() = JBUI.scale(8)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, size, size)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size
    }
}
