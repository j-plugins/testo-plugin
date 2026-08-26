package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoIcons
import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.IconUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
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
import java.awt.Image
import java.awt.RenderingHints
import java.lang.reflect.Field
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

// The label shown for a test in aggregated channel output (the per-test header / hyperlink). Pure string logic, kept
// top-level so it is unit-testable without the IDE platform. Handles:
//  - a class method:            php_qn://file::\Ns\Class::method            -> \Ns\Class::method
//  - a root-namespace method:   php_qn://file::\Class::method               -> \Class::method
//  - a standalone function:     php_qn://file::\Ns\func (or \func)          -> \Ns\func (no doubling of the short name)
//  - a dataset:                 php_qn://file::\Class::method with data set #N (presentable "Dataset #0:0 [0]")
//                               -> \Class::method:Dataset #0:0 [0]  (Testo's " with data set #N" suffix is dropped in
//                                  favour of the dataset's own presentable name, which already carries the index/value)
private val DATASET_INDEX_SUFFIX = Regex("(:\\d+)+$")

internal fun testoDisplayName(locationUrl: String?, presentableName: String): String {
    val raw = locationUrl
        ?.substringAfter("://", "")
        ?.substringAfter("::", "")
        ?.takeIf { it.isNotBlank() }
        ?: return presentableName
    // Reduce the location to the bare method FQN by dropping Testo's two dataset markers — the " with data set #N"
    // suffix and the numeric "method:0[:0]" index — so we can re-render the dataset uniformly below.
    val fqn = raw.substringBefore(" with data set").replace(DATASET_INDEX_SUFFIX, "")
    val method = fqn.substringAfterLast("::").substringAfterLast('\\')
    if (presentableName == method) return fqn
    // A dataset's presentable name is "Dataset #<index> [<value>]"; show it as "<method> with data set #<index>"
    // (Testo's wording), dropping the bracketed value. Non-dataset names fall back to "<method>:<name>".
    val datasetIndex = presentableName.substringAfter("Dataset #", "").substringBefore(" [").trim()
    return if (datasetIndex.isNotEmpty()) "$fqn with data set #$datasetIndex" else "$fqn:$presentableName"
}

// A run of metadata that shares a name prefix (everything before the first dot) and a type *bucket* — the unit one
// metadata card renders. [type] is the representative for the bucket: [TestoMetadataType.NUMBER] for the numeric bucket
// (which pools number/ms/bytes/percent — all numbers, only the unit differs), else the exact non-numeric type.
internal data class MetadataGroup(val prefix: String, val type: TestoMetadataType, val entries: List<TestoMetadataEntry>)

// Splits a test's metadata into cards, in first-appearance order. Grouped by (prefix, bucket): all numeric types pool
// into one bucket per prefix (so a `bench.*` table isn't torn into one card per unit), while each non-numeric type stays
// on its own. Dotless names share the empty prefix so a handful of loose scalars don't fragment into a card apiece.
// Kept top-level so it is unit-testable without the IDE platform.
internal fun groupMetadata(entries: List<TestoMetadataEntry>): List<MetadataGroup> {
    // The bucket key: null for the shared numeric bucket, else the exact type.
    val groups = LinkedHashMap<Pair<String, TestoMetadataType?>, MutableList<TestoMetadataEntry>>()
    for (entry in entries) {
        val prefix = if (entry.name.contains('.')) entry.name.substringBefore('.') else ""
        val bucket = if (entry.type.isNumeric) null else entry.type
        groups.getOrPut(prefix to bucket) { mutableListOf() }.add(entry)
    }
    return groups.map { (key, entries) -> MetadataGroup(key.first, key.second ?: TestoMetadataType.NUMBER, entries) }
}

// One group's entries as a `name = value` block — the `.properties` shape, so the card is syntax-highlighted like any
// other channel. A genuinely shared prefix (more than one key under it) is dropped from each key, since the card header
// already carries it; a lone `report.csv`-style name keeps its dot.
internal fun formatMetadata(group: MetadataGroup): String {
    val strip = group.prefix.isNotEmpty() && group.entries.size > 1
    return group.entries.joinToString("\n") { entry ->
        val name = if (strip) entry.name.removePrefix("${group.prefix}.") else entry.name
        "$name = ${entry.value}"
    }
}

/** A metadata value the plugin should open in a browser rather than the local filesystem. */
internal fun isMetadataUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

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
        metadataStore: TestoMetadataStore,
        levelFilter: LogLevelFilter,
        project: Project,
        parent: Disposable,
        // Maps a run-environment path (as a metadata image/artifact carries it) to a local one — the deployment mapper
        // under a remote interpreter, identity locally. Defaults to identity so replays and tests need not supply it.
        resolveLocalPath: (String) -> String? = { it },
        // Imports pass the root proxy to render the whole tree deterministically; a live run leaves this null and uses
        // whatever is selected (nothing yet at startup).
        initialSelection: SMTestProxy? = null,
    ) {
        val field = myConsoleField
        if (field == null) {
            thisLogger().warn("Testo channels disabled: TestResultsPanel.myConsole not found")
            return
        }
        val controller = ChannelTabsController(project, store, metadataStore, levelFilter, console, field, resolveLocalPath)
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
        private val metadataStore: TestoMetadataStore,
        private val levelFilter: LogLevelFilter,
        private val console: SMTRunnerConsoleView,
        private val myConsoleField: Field,
        private val resolveLocalPath: (String) -> String?,
    ) : TestResultsViewer.EventsListener, Disposable {

        private var tabs: JBEditorTabs? = null
        private var outputComponent: JComponent? = null
        // The log-level filter, shown at the right edge of the tab row. It rides on every tab's tabPaneActions rather
        // than on JBTabs.getEntryPointActionGroup(): that getter is @ApiStatus.Internal and fails the plugin verifier,
        // while setTabPaneActions is public and the platform feeds the selected tab's group into the same toolbar.
        private val entryPointActions = DefaultActionGroup(TestoLogLevelFilterAction(levelFilter))
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
            metadataStore.clear()
            ensureInstalled()
        }

        override fun onSelected(
            selected: SMTestProxy?,
            viewer: TestResultsViewer,
            model: TestFrameworkRunningModel,
        ) {
            val tabbed = ensureInstalled() ?: return
            val platform = outputComponent ?: return
            // Keep the open channel across a rebuild (a log-level toggle or a test switch): remember its title now and
            // reselect the same-named tab afterwards, falling back to Output when that channel is gone from the new set.
            val previousTitle = tabbed.selectedInfo?.text
            tabbed.removeAllTabs()
            lazyTabs.clear()
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

                // Output first: it is the raw process console, outside the channel-aggregating "All" scope.
                val outputTab =
                    addAggregateTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, viewer, leaves, attach = store::attachOutput)

                // Metadata second, when any of these tests reported some: one card per test.
                if (hasMetadata(leaves)) addMetadataTab(tabbed, viewer, leaves, showLeafLabels = true)

                // All: syntax-highlighted cards, language picked per message from its own channel.
                val allCards = newCards(null, aggregate = true)
                store.header().forEach { allCards.add(it) }
                addCardsAggregate(allCards, viewer, leaves) { key, sink -> store.attachAll(key, sink) }
                addComponentTab(tabbed, ALL_TAB, AllIcons.Actions.Show, allCards.component)

                // Every channel renders as cards (one per test, that test's messages merged); only the Output tab above
                // stays a console. A language channel highlights each card; a format-less one keeps its ANSI.
                for (channel in channelsAcross(leaves)) {
                    if (!channelHasVisible(leaves, channel)) continue
                    val sample = leaves.firstNotNullOfOrNull { leaf ->
                        keyOf(leaf)?.let { store.channelsFor(it)[channel] }?.takeIf { it.isNotEmpty() }
                    } ?: emptyList()
                    // Build this channel's cards only when its tab is opened — see addLazyTab.
                    addLazyTab(tabbed, humanize(channel), channelIcon(channel, sample)) {
                        val cards = newCards(channelFileType(channel), aggregate = true, showChannel = false)
                        addCardsAggregate(cards, viewer, leaves) { key, sink -> store.attachChannel(key, channel, sink) }
                        cards.component
                    }
                }
                selectPreferredTab(tabbed, previousTitle, outputTab)
                return
            }

            // Output first: the raw process console, outside the channel-aggregating "All" scope.
            val outputTab = addComponentTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, platform)
            val key = keyOf(selected)
            // Metadata second, when this test reported some.
            if (hasMetadata(listOf(selected))) addMetadataTab(tabbed, viewer, listOf(selected), showLeafLabels = false)
            val header = store.header()
            // All: highlighted cards (per-message language). Header chunks first, then the live "all" stream replays
            // and keeps appending, so a streaming test's messages show up as they arrive.
            if (key != null || header.isNotEmpty()) {
                val allCards = newCards(null)
                header.forEach { allCards.add(it) }
                if (key != null) subscriptions += store.attachAll(key) { allCards.add(it) }
                addComponentTab(tabbed, ALL_TAB, AllIcons.Actions.Show, allCards.component)
            }
            if (key != null) {
                for ((channel, chunks) in store.channelsFor(key)) {
                    if (chunks.none { levelFilter.isVisible(it.level) }) continue
                    val cards = newCards(channelFileType(channel), showChannel = false)
                    subscriptions += store.attachChannel(key, channel) { cards.add(it) }
                    addComponentTab(tabbed, humanize(channel), channelIcon(channel, chunks), cards.component)
                }
            }
            selectPreferredTab(tabbed, previousTitle, outputTab)
        }

        // Reselect the tab whose title the user last had open, so a rebuild keeps their channel; Output when it is gone.
        private fun selectPreferredTab(tabbed: JBEditorTabs, preferredTitle: String?, fallback: TabInfo?) {
            val target = preferredTitle?.let { title -> tabbed.tabs.firstOrNull { it.text == title } } ?: fallback
            target?.let { tabbed.select(it, false) }
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

        private fun addTab(tabbed: JBEditorTabs, title: String, icon: Icon, view: ConsoleViewImpl?): TabInfo? =
            view?.let { addComponentTab(tabbed, title, icon, it.component) }

        private fun addComponentTab(tabbed: JBEditorTabs, title: String, icon: Icon, component: JComponent): TabInfo {
            val info = TabInfo(component).setText(title).setIcon(icon).setTabPaneActions(entryPointActions)
            tabbed.addTab(info)
            return info
        }

        // Each card is a full editor, so building every channel tab up front (10+ tabs × up to MAX_CARDS editors) froze
        // the EDT for ~1.8s when a big suite/root was selected. A lazy tab builds its (potentially hundreds of) editors
        // only the first time it is shown — selecting a node now only builds the visible "All" tab.
        private class LazyTab(val build: () -> JComponent) {
            var built = false
        }

        // Builder per lazy tab, keyed by its TabInfo (a side map avoids TabInfo.setObject/getObject, whose getter is
        // absent on some builds). Cleared on every re-render in onSelected.
        private val lazyTabs = HashMap<TabInfo, LazyTab>()

        private fun addLazyTab(tabbed: JBEditorTabs, title: String, icon: Icon, build: () -> JComponent) {
            val placeholder = JBPanel<Nothing>(BorderLayout()).apply { isOpaque = false }
            val info = TabInfo(placeholder).setText(title).setIcon(icon).setTabPaneActions(entryPointActions)
            lazyTabs[info] = LazyTab(build)
            tabbed.addTab(info)
        }

        private fun buildLazyTab(info: TabInfo?) {
            val tab = info ?: return
            val lazy = lazyTabs[tab] ?: return
            if (lazy.built) return
            lazy.built = true
            (tab.component as? JComponent)?.apply {
                add(lazy.build(), BorderLayout.CENTER)
                revalidate()
                repaint()
            }
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

        private fun newCards(fixedFileType: FileType?, aggregate: Boolean = false, showChannel: Boolean = true): MessageCards =
            MessageCards(fixedFileType, aggregate, showChannel).also { activeCards += it }

        private fun channelsAcross(leaves: List<SMTestProxy>): Set<String> {
            val channels = LinkedHashSet<String>()
            for (leaf in leaves) keyOf(leaf)?.let { channels.addAll(store.channelsFor(it).keys) }
            return channels
        }

        // Aggregate-only: a channel earns a tab when a shown leaf has a chunk that both passes the level filter and is
        // not test-only (a channel of nothing but `/t` noise would give an empty tab under a suite selection).
        private fun channelHasVisible(leaves: List<SMTestProxy>, channel: String): Boolean =
            leaves.any { leaf ->
                keyOf(leaf)?.let { store.channelsFor(it)[channel] }
                    ?.any { levelFilter.isVisible(it.level) && !it.flags.testOnly } == true
            }

        private fun hasMetadata(leaves: List<SMTestProxy>): Boolean =
            leaves.any { keyOf(it)?.let(metadataStore::hasEntries) == true }

        // The metadata channel: standard TeamCity testMetadata the converter consumed, shown as `.properties`-highlighted
        // cards (one per test). Lazy like the other channel tabs — a big suite's cards are built only when it is opened.
        private fun addMetadataTab(
            tabbed: JBEditorTabs,
            viewer: TestResultsViewer,
            leaves: List<SMTestProxy>,
            showLeafLabels: Boolean,
        ) {
            addLazyTab(tabbed, METADATA_TAB, AllIcons.General.Information) {
                val cards = newCards(metadataFileType(), aggregate = showLeafLabels)
                for (leaf in leaves) {
                    val key = keyOf(leaf) ?: continue
                    val leafLabel = if (showLeafLabels) fullName(leaf) else null
                    val onLeafClick = if (showLeafLabels) ({ selectInTree(viewer, leaf) }) else null
                    val description = if (showLeafLabels) store.description(key) else null
                    for (group in groupMetadata(metadataStore.entriesFor(key))) {
                        val label = metadataGroupLabel(group)
                        when (group.type) {
                            // Links: one card of clickable labels (each name opens its URL).
                            TestoMetadataType.LINK ->
                                cards.addComponentCard(buildLinksCard(group), label, leafLabel, onLeafClick, description)
                            // Images / downloadable artifacts: one card each, keyed by the datum's own (full) name.
                            TestoMetadataType.IMAGE ->
                                for (entry in group.entries)
                                    cards.addComponentCard(buildImageCard(entry), entry.name, leafLabel, onLeafClick, description)
                            TestoMetadataType.ARTIFACT, TestoMetadataType.VIDEO ->
                                for (entry in group.entries)
                                    cards.addComponentCard(buildArtifactCard(entry), entry.name, leafLabel, onLeafClick, description)
                            // Numbers (incl. ms/bytes/percent): a full grid becomes a table, else the flat key/value
                            // list; free-form text takes that same list.
                            else -> {
                                val matrix = if (group.type.isNumeric) buildMetadataMatrix(group) else null
                                if (matrix != null) {
                                    cards.addComponentCard(buildMatrixCard(matrix), label, leafLabel, onLeafClick, description)
                                } else {
                                    cards.add(ChannelOutputStore.Chunk(formatMetadata(group), null, label), leafLabel, onLeafClick, description)
                                }
                            }
                        }
                    }
                }
                cards.component
            }
        }

        // The card header's channel slot: the group prefix, with the type appended unless it is the plain-number default
        // (so `bench` numbers read as "bench", while a screenshot reads as "shots · image").
        private fun metadataGroupLabel(group: MetadataGroup): String {
            val prefix = group.prefix.ifEmpty { METADATA_TAB.lowercase() }
            return if (group.type == TestoMetadataType.NUMBER) prefix else "$prefix · ${group.type.wire}"
        }

        // The `.properties` file type gives the metadata card real key/value highlighting; null (the type absent) falls
        // back to a plain editor, which still reads fine.
        private fun metadataFileType(): FileType? {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension("properties")
            return fileType.takeUnless { it is UnknownFileType || it is PlainTextFileType }
        }

        private fun buildLinksCard(group: MetadataGroup): JComponent =
            JBPanel<Nothing>(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6)
                for (entry in group.entries) {
                    add(HyperlinkLabel(entry.name).apply {
                        setHyperlinkTarget(entry.value)
                        toolTipText = entry.value
                    })
                }
            }

        // The image, loaded off the EDT, scaled to fit, click-to-open; a clickable path/URL when the bytes can't be read.
        private fun buildImageCard(entry: TestoMetadataEntry): JComponent {
            val panel = JBPanel<Nothing>(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(4) }
            panel.add(JBLabel("Loading…").apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }, BorderLayout.NORTH)
            loadImage(entry.value) { image ->
                panel.removeAll()
                if (image != null) {
                    panel.add(JBLabel(scaleIcon(image)).apply {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        toolTipText = entry.value
                        addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) = openResource(entry.value)
                        })
                    }, BorderLayout.CENTER)
                } else {
                    panel.add(openableLink(entry.value), BorderLayout.CENTER)
                }
                panel.revalidate(); panel.repaint()
            }
            return panel
        }

        // A downloadable artifact (or video): a clickable path/URL plus a Copy button. Opening a local file hands it to
        // the IDE (its own editor/viewer); a URL goes to the browser.
        private fun buildArtifactCard(entry: TestoMetadataEntry): JComponent =
            JBPanel<Nothing>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 4)
                add(openableLink(entry.value))
                add(InplaceButton(IconButton("Copy path", AllIcons.Actions.Copy)) {
                    CopyPasteManager.getInstance().setContents(StringSelection(entry.value))
                }.apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) })
            }

        private fun openableLink(value: String): HyperlinkLabel =
            HyperlinkLabel(value).apply {
                toolTipText = value
                addHyperlinkListener { openResource(value) }
            }

        // A URL opens in the browser; a local path (deployment-mapped when needed) opens in the IDE, falling back to the
        // OS handler when the VFS doesn't know the file.
        private fun openResource(value: String) {
            if (isMetadataUrl(value)) {
                BrowserUtil.browse(value)
                return
            }
            val local = resolveLocalPath(value) ?: value
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(local.replace('\\', '/'))
            if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
            else runCatching { BrowserUtil.browse(java.io.File(local)) }
        }

        // Off-EDT read (network or disk), back onto the EDT with the decoded image or null. The URL read is bounded by
        // timeouts — ImageIO.read(URL) has none, and a stalled host would hold the pooled thread indefinitely.
        private fun loadImage(value: String, onDone: (Image?) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val image = runCatching {
                    if (isMetadataUrl(value)) {
                        val connection = java.net.URI(value).toURL().openConnection()
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        connection.getInputStream().use(ImageIO::read)
                    } else {
                        (resolveLocalPath(value) ?: value).let { java.io.File(it).takeIf(java.io.File::isFile)?.let(ImageIO::read) }
                    }
                }.getOrNull()
                ApplicationManager.getApplication().invokeLater({ onDone(image) }, ModalityState.any())
            }
        }

        // Fit within a sensible box (never upscaled), preserving aspect ratio.
        private fun scaleIcon(image: Image): Icon {
            val w = image.getWidth(null).coerceAtLeast(1)
            val h = image.getHeight(null).coerceAtLeast(1)
            val scale = minOf(JBUI.scale(480).toDouble() / w, JBUI.scale(360).toDouble() / h, 1.0)
            if (scale >= 1.0) return ImageIcon(image)
            val scaled = image.getScaledInstance((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Image.SCALE_SMOOTH)
            return ImageIcon(scaled)
        }

        // The card body for a number matrix: the leftover scalars as a caption, the table, and a row of view toggles
        // below (Table / Bars / Lines / Pie + Swap axes). The views are mutually exclusive without a ButtonGroup: each
        // toggle's selected state is derived from `mode`.
        private fun buildMatrixCard(base: MetadataMatrix): JComponent {
            val center = JBPanel<Nothing>(BorderLayout()).apply { isOpaque = false }
            var transposed = false
            var mode: ChartMode? = null
            // Cached: current()/uniform() run on every toolbar update tick, and transposed() rebuilds the whole matrix.
            val transposedMatrix by lazy { base.transposed() }
            fun current() = if (transposed) transposedMatrix else base
            fun uniform(): Boolean {
                val matrix = current()
                return matrix.columns.map { matrix.typeOf(it) }.distinct().size == 1
            }

            fun renderCenter() {
                val matrix = current()
                center.removeAll()
                center.add(mode?.let { matrixChart(matrix, it) } ?: buildMatrixTable(matrix), BorderLayout.CENTER)
                center.revalidate(); center.repaint()
            }

            fun viewToggle(text: String, viewMode: ChartMode?) = object : ToggleAction(text) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = mode == viewMode
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state && mode != viewMode) { mode = viewMode; renderCenter() }
                }
            }

            val group = DefaultActionGroup().apply {
                add(viewToggle("Table", null))
                add(viewToggle("Bars", ChartMode.BARS))
                add(object : ToggleAction("Lines") {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = mode == ChartMode.LINES
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state && mode != ChartMode.LINES) { mode = ChartMode.LINES; renderCenter() }
                    }
                    // Lines share one axis, so the toggle only shows when the current orientation's columns share a unit.
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isEnabledAndVisible = uniform()
                    }
                })
                add(object : ToggleAction("Pie") {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = mode == ChartMode.PIE
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state && mode != ChartMode.PIE) { mode = ChartMode.PIE; renderCenter() }
                    }
                    // A pie needs a single row or column of one unit.
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isEnabledAndVisible = pieable(current())
                    }
                })
                addSeparator()
                add(object : ToggleAction("Swap Axes") {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = transposed
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        transposed = state
                        // The flipped matrix may no longer offer the active chart.
                        if (mode == ChartMode.LINES && !uniform()) mode = null
                        if (mode == ChartMode.PIE && !pieable(current())) mode = null
                        renderCenter()
                    }
                })
            }
            // These actions carry no icon; without this the toolbar draws an empty icon button instead of the label.
            group.getChildActionsOrStubs().forEach {
                it.templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
            val toolbar = ActionManager.getInstance().createActionToolbar("TestoMetadataChart", group, true).apply {
                targetComponent = center
            }

            renderCenter()
            return JBPanel<Nothing>(BorderLayout()).apply {
                isOpaque = false
                if (base.scalars.isNotEmpty()) {
                    add(JBLabel(base.scalars.joinToString("     ") { "${it.first} = ${it.second}" }).apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = JBColor.GRAY
                        border = JBUI.Borders.empty(3, 6, 2, 6)
                    }, BorderLayout.NORTH)
                }
                add(center, BorderLayout.CENTER)
                add(toolbar.component, BorderLayout.SOUTH)
            }
        }

        // The matrix as a read-only JBTable: row names in the first column, a chart glyph in each uniform column header
        // and row-name cell, numeric-aware sort on a header-name click, and a spanning columnGroup band for depth-3.
        private fun buildMatrixTable(matrix: MetadataMatrix): JComponent {
            val model = MatrixTableModel(matrix)
            // JBTable.configureEnclosingScrollPane (fired on addNotify, and on every re-add) resets the scroll's column
            // header to the plain table header, which would drop our columnGroup band — so re-install the band after it.
            var reinstallHeader: (() -> Unit)? = null
            val table = object : JBTable(model) {
                override fun configureEnclosingScrollPane() {
                    super.configureEnclosingScrollPane()
                    reinstallHeader?.invoke()
                }
            }.apply {
                setShowGrid(true)
                autoResizeMode = JTable.AUTO_RESIZE_OFF
                tableHeader.reorderingAllowed = false
                tableHeader.resizingAllowed = false
            }
            val rightAligned = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }
            for (c in 1 until model.columnCount) table.columnModel.getColumn(c).cellRenderer = rightAligned
            table.columnModel.getColumn(0).cellRenderer = MatrixRowHeaderRenderer(model)
            table.tableHeader.defaultRenderer = MatrixColumnHeaderRenderer(model)
            sizeColumns(table, model)

            val scroll = JBScrollPane(table).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
            if (matrix.hasColumnGroups) {
                val composite = groupHeaderComposite(table, matrix)
                // super.configureEnclosingScrollPane reparents the table header out of the composite; reclaim it first.
                reinstallHeader = {
                    composite.add(table.tableHeader, BorderLayout.CENTER)
                    scroll.setColumnHeaderView(composite)
                }
                reinstallHeader.invoke()
            }

            // A click in a header's chart-glyph zone charts that column; elsewhere in the header it sorts. A mixed
            // column draws no glyph, so its whole header — glyph zone included — sorts.
            table.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val viewColumn = table.columnAtPoint(e.point)
                    if (viewColumn < 0) return
                    val rect = table.tableHeader.getHeaderRect(viewColumn)
                    val onGlyph = viewColumn >= 1 && e.x >= rect.x + rect.width - CHART_ZONE &&
                            matrix.isColumnUniform(matrix.columns[viewColumn - 1])
                    if (onGlyph) showColumnChart(table, matrix, viewColumn)
                    else model.toggleSort(viewColumn)
                }
            })
            // A click in the row-name cell's glyph zone charts that row.
            table.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val row = table.rowAtPoint(e.point)
                    if (row < 0 || table.columnAtPoint(e.point) != 0) return
                    val rect = table.getCellRect(row, 0, false)
                    if (e.x >= rect.x + rect.width - CHART_ZONE) showRowChart(table, model, row)
                }
            })

            return object : JBPanel<Nothing>(BorderLayout()) {
                init {
                    isOpaque = false
                    add(scroll, BorderLayout.CENTER)
                }

                // Grow to fit every row; the outer cards scroll takes vertical overflow, the inner one takes horizontal.
                override fun getPreferredSize(): Dimension {
                    val headerHeight = scroll.columnHeader?.view?.preferredSize?.height
                        ?: table.tableHeader.preferredSize.height
                    var height = headerHeight + table.rowHeight * model.rowCount + JBUI.scale(4)
                    scroll.horizontalScrollBar?.takeIf { it.isVisible }?.let { height += it.preferredSize.height }
                    return Dimension(super.getPreferredSize().width, height)
                }
            }
        }

        // A uniform column charted across its rows (the caller gates on uniformity, matching the hidden glyph).
        private fun showColumnChart(anchor: JComponent, matrix: MetadataMatrix, viewColumn: Int) {
            val column = matrix.columns[viewColumn - 1]
            val values = matrix.rows.map { matrix.value(it, column).toDoubleOrNull() ?: Double.NaN }
            openSeriesChartPopup(anchor, columnLabel(column), column.name, matrix.rows, values, matrix.typeOf(column))
        }

        // A uniform row charted across its columns (nothing to show for a mixed row — its glyph is hidden).
        private fun showRowChart(anchor: JComponent, model: MatrixTableModel, viewRow: Int) {
            val row = model.rowNameAt(viewRow)
            val matrix = model.matrix
            if (!matrix.isRowUniform(row)) return
            val values = matrix.columns.map { matrix.value(row, it).toDoubleOrNull() ?: Double.NaN }
            openSeriesChartPopup(anchor, row, row, matrix.columns.map(::columnLabel), values, matrix.rowType(row))
        }

        // A single uniform series (a row or a column) shown as bars or a pie, toggled in the popup.
        private fun openSeriesChartPopup(
            anchor: JComponent, title: String, seriesName: String, labels: List<String>, values: List<Double>,
            type: TestoMetadataType,
        ) {
            val format = chartValueFormatter(type, values)
            val center = JBPanel<Nothing>(BorderLayout()).apply { isOpaque = false }
            fun render(pie: Boolean) {
                center.removeAll()
                center.add(
                    if (pie) TestoPieChart(title, labels, values, format)
                    else TestoBarChart(title, labels, listOf(ChartSeries(seriesName, values)), axisFormat = format, hoverFormat = { _, _, v -> format(v) }),
                    BorderLayout.CENTER,
                )
                center.revalidate(); center.repaint()
            }
            var pie = false
            val group = DefaultActionGroup().apply {
                add(seriesToggle("Bars", { !pie }) { pie = false; render(false) })
                add(seriesToggle("Pie", { pie }) { pie = true; render(true) })
            }
            group.getChildActionsOrStubs().forEach { it.templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
            val toolbar = ActionManager.getInstance().createActionToolbar("TestoMetadataSeriesChart", group, true).apply { targetComponent = center }
            render(false)
            val panel = JBPanel<Nothing>(BorderLayout()).apply {
                add(center, BorderLayout.CENTER)
                add(toolbar.component, BorderLayout.SOUTH)
                preferredSize = Dimension(JBUI.scale(480), JBUI.scale(360))
            }
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, center)
                .setResizable(true).setMovable(true).setRequestFocus(true)
                .createPopup().showInCenterOf(anchor)
        }

        private fun seriesToggle(text: String, selected: () -> Boolean, onSelect: () -> Unit): ToggleAction =
            object : ToggleAction(text) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = selected()
                override fun setSelected(e: AnActionEvent, state: Boolean) { if (state) onSelect() }
            }

        // The whole matrix as one chart: a category per column, a series per row. When every column shares one unit the
        // axis converts to it; otherwise the axis is plain and hovers convert per column (bars only — lines need one
        // shared unit, so that button is offered only for a uniform matrix).
        private fun matrixChart(matrix: MetadataMatrix, mode: ChartMode): JComponent {
            val title = matrix.prefix.ifEmpty { "metadata" }
            if (mode == ChartMode.PIE) return JBScrollPane(pieChart(matrix, title)).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
            val series = matrix.rows.map { row ->
                ChartSeries(row, matrix.columns.map { matrix.value(row, it).toDoubleOrNull() ?: Double.NaN })
            }
            val labels = matrix.columns.map(::columnLabel)
            val uniformType = matrix.columns.map { matrix.typeOf(it) }.distinct().singleOrNull()
            val chart = if (uniformType != null) {
                val format = chartValueFormatter(uniformType, series.flatMap { it.values })
                TestoBarChart(title, labels, series, axisFormat = format, hoverFormat = { _, _, v -> format(v) }, mode = mode)
            } else {
                TestoBarChart(title, labels, series, mode = mode, hoverFormat = { cat, _, v ->
                    formatMetadataDouble(v, matrix.typeOf(matrix.columns[cat]))
                })
            }
            return JBScrollPane(chart).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        }

        private fun columnLabel(column: MetadataColumn): String =
            column.group?.let { "$it · ${column.name}" } ?: column.name

        // A pie needs a single row or column of same-unit values: slice per row (single column) or per column (single row).
        private fun pieable(matrix: MetadataMatrix): Boolean = when {
            matrix.columns.size == 1 -> matrix.isColumnUniform(matrix.columns[0])
            matrix.rows.size == 1 -> matrix.isRowUniform(matrix.rows[0])
            else -> false
        }

        private fun pieChart(matrix: MetadataMatrix, title: String): JComponent {
            val (labels, values, type) = if (matrix.columns.size == 1) {
                val column = matrix.columns[0]
                Triple(matrix.rows, matrix.rows.map { matrix.value(it, column).toDoubleOrNull() ?: Double.NaN }, matrix.typeOf(column))
            } else {
                val row = matrix.rows[0]
                Triple(matrix.columns.map(::columnLabel), matrix.columns.map { matrix.value(row, it).toDoubleOrNull() ?: Double.NaN }, matrix.typeOf(matrix.columns[0]))
            }
            return TestoPieChart(title, labels, values, chartValueFormatter(type, values))
        }

        // Content-derived column widths (min == max, so nothing resizes and a wide table simply scrolls); the band
        // paints straight off these widths, so pinning them keeps it aligned without a column-model listener. Header
        // width reserves room for the sort arrow and the chart glyph, so neither overlaps the name.
        private fun sizeColumns(table: JBTable, model: MatrixTableModel) {
            val fm = table.getFontMetrics(table.font)
            val headerFm = table.getFontMetrics(table.tableHeader.font)
            for (c in 0 until model.columnCount) {
                var cellWidth = 0
                for (r in 0 until model.rowCount) cellWidth = maxOf(cellWidth, fm.stringWidth(model.getValueAt(r, c)))
                if (c == 0) cellWidth += CHART_ZONE
                val headerReserve = JBUI.scale(16) + (if (c >= 1) CHART_ZONE else 0)
                val headerWidth = headerFm.stringWidth(model.getColumnName(c)) + headerReserve
                val width = (maxOf(cellWidth, headerWidth) + JBUI.scale(14)).coerceIn(JBUI.scale(56), JBUI.scale(360))
                table.columnModel.getColumn(c).apply {
                    preferredWidth = width; minWidth = width; maxWidth = width
                }
            }
        }

        // The column-header view: a band of columnGroup cells (one per contiguous run of columns sharing a group,
        // spanning their combined width) over the plain table header. Set as the scroll's column-header view, so it
        // scrolls with the header. The table header is (re)parented into it by the caller's reinstall hook.
        private fun groupHeaderComposite(table: JBTable, matrix: MetadataMatrix): JBPanel<Nothing> {
            val runs = mutableListOf<GroupRun>()
            var i = 0
            while (i < matrix.columns.size) {
                val group = matrix.columns[i].group
                var j = i
                while (j + 1 < matrix.columns.size && matrix.columns[j + 1].group == group) j++
                // +1 across the board: view column 0 is the row-name column, which no group covers.
                runs += GroupRun(group, i + 1, j + 1)
                i = j + 1
            }
            val band = GroupBand(table, runs)
            return JBPanel<Nothing>(BorderLayout()).apply {
                isOpaque = false
                add(band, BorderLayout.NORTH)
            }
        }

        private fun channelIcon(channel: String, chunks: List<ChannelOutputStore.Chunk>): Icon {
            // A channel whose suffix maps to a real file type shows that type's file icon (query.sql -> the SQL icon).
            channelFileType(channel)?.icon?.let { return it }
            // Colour is always resolvable: explicit hint, ANSI in the output, else a stable hue hashed from the name.
            val color = channelColor(channel, chunks)
            // Semantic icon: the explicit `icon=` hint first, then the channel name itself, matched against the map.
            val mapped = store.channelIcon(channel)?.let { ChannelIcons.match(it) } ?: ChannelIcons.match(channel)
            if (mapped != null) return IconUtil.colorize(mapped, color)
            // No name match: a stable icon from the pool, tinted with the channel colour.
            val index = ((channel.hashCode() % ICON_POOL_SIZE) + ICON_POOL_SIZE) % ICON_POOL_SIZE
            return if (index < BASE_ICONS.size) IconUtil.colorize(BASE_ICONS[index], color) else DotIcon(color)
        }

        private fun channelColor(channel: String, chunks: List<ChannelOutputStore.Chunk>): Color {
            store.channelColor(channel)?.let { parseColor(it) }?.let { return it }
            val decoder = AnsiEscapeDecoder()
            val ansi = chunks.firstNotNullOfOrNull { chunk ->
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
            // No declared/ANSI colour: derive a stable one from the name so the tab still gets a distinct, tinted icon.
            return ansi ?: hashColor(channel)
        }

        // Deterministic per-name colour: hue from the name hash, fixed saturation/brightness tuned per theme.
        private fun hashColor(channel: String): Color {
            val hue = (((channel.hashCode() % 360) + 360) % 360) / 360f
            return JBColor(Color.getHSBColor(hue, 0.55f, 0.55f), Color.getHSBColor(hue, 0.50f, 0.82f))
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
        ): TabInfo? {
            val view = newConsole(emptyList())
            if (prependHeader) printChunks(view, store.header())
            val aggregate = LiveAggregate(view, viewer, attach)
            leaves.forEach { aggregate.addLeaf(it) }
            activeAggregates += aggregate
            return addTab(tabbed, title, icon, view)
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
                val desc = store.description(key)
                subscriptions += attach(key) { chunk -> cards.add(chunk, fullName(leaf), onLeafClick = { selectInTree(viewer, leaf) }, description = desc) }
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
        private inner class MessageCards(
            private val fixedFileType: FileType?,
            private val aggregate: Boolean = false,
            // The card header names its channel only where cards mix channels (the All tab) or carry a group label
            // (Metadata); a single-channel tab already names it, so it stays off there.
            private val showChannel: Boolean = true,
        ) {
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
            // Replaying a big aggregate (one card == one editor) blocked the EDT for ~1s. Cards built during the
            // synchronous replay are queued and flushed in small batches off the event so selection stays responsive.
            private val pending = ArrayDeque<Runnable>()
            private var flushScheduled = false
            private val editors = mutableListOf<EditorEx>()
            private val fileEditors = mutableListOf<Pair<FileEditorProvider, FileEditor>>()

            // The previous card, for folding consecutive same-channel messages of one test into one canvas. lastEditor is
            // null when that card can't be appended to (a `/c` card, or a preview-bodied one); lastSeparation is the
            // previous message's mode, which decides the break before the next one.
            private var lastMergeKey: MergeKey? = null
            private var lastEditor: EditorEx? = null
            private var lastCard: JComponent? = null
            private var lastSeparation: ChannelSeparation? = null

            // Editors aren't released by merely removing their Swing component; the controller calls this on rebuild.
            // `released` also stops a chunk task queued just before this from materializing (and leaking) a new editor.
            fun release() {
                released = true
                pending.clear()
                editors.forEach { EditorFactory.getInstance().releaseEditor(it) }
                editors.clear()
                fileEditors.forEach { (provider, fileEditor) -> runCatching { provider.disposeEditor(fileEditor) } }
                fileEditors.clear()
            }

            // Queue a card-build to run in a later, small batch (keeps replay order; FIFO). Used for the on-EDT replay;
            // live chunks (off the EDT) still post one-by-one as they arrive.
            private fun schedule(task: Runnable) {
                pending.addLast(task)
                if (flushScheduled) return
                flushScheduled = true
                ApplicationManager.getApplication().invokeLater(::flushBatch)
            }

            private fun flushBatch() {
                flushScheduled = false
                if (released) {
                    pending.clear()
                    return
                }
                var n = 0
                while (pending.isNotEmpty() && n < CARDS_PER_BATCH) {
                    pending.removeFirst().run()
                    n++
                }
                if (pending.isNotEmpty()) {
                    flushScheduled = true
                    ApplicationManager.getApplication().invokeLater(::flushBatch)
                }
            }

            // Chunks arrive on the test-reader thread (replay happens on the EDT inside onSelected); hop to the EDT for
            // the Swing/editor mutation. invokeLater is FIFO, so message order is preserved. onLeafClick, when set,
            // makes the per-test name in the header navigate back to that test in the tree.
            fun add(chunk: ChannelOutputStore.Chunk, leafLabel: String? = null, onLeafClick: (() -> Unit)? = null, description: String? = null) {
                if (released || !levelFilter.isVisible(chunk.level)) return
                // A `/t` message shows only under its own test's selection; an aggregate (suite/root) skips it.
                if (aggregate && chunk.flags.testOnly) return
                // Decode ANSI once: the plain text drives the body; segments tint plain (format-less) cards. Bytes are
                // kept as emitted — joining inserts breaks, it never strips what a message carries.
                val (plain, segments) = decodeAnsi(chunk.text)
                if (plain.isEmpty()) return
                // Plain process output (no channel) is raw-glued; a named channel obeys its own separation flag.
                val separation = if (chunk.channel == null) ChannelSeparation.STREAM else chunk.flags.separation
                val ownCard = chunk.channel != null && chunk.flags.card
                val fileType = fixedFileType ?: channelFileType(chunk.channel)
                val app = ApplicationManager.getApplication()
                val task = Runnable {
                    if (released) return@Runnable
                    // Consecutive messages of the same channel/test fold into one canvas; a `/c` message opts out and
                    // breaks the run so the next message starts a fresh card.
                    val mergeKey = if (ownCard) null else MergeKey(chunk.channel, leafLabel)
                    val target = if (mergeKey != null && mergeKey == lastMergeKey) {
                        lastEditor?.takeUnless { it.isDisposed }
                    } else {
                        null
                    }
                    if (target != null) {
                        // A language card highlights from its lexer, not from ANSI, so its appends drop the segments.
                        val appendSegments = if (fileType == null) segments else emptyList()
                        val separator = "\n".repeat(separatorNewlines(lastSeparation, separation, target.document.charsSequence, plain))
                        appendToEditor(target, separator, plain, appendSegments)
                        lastSeparation = separation
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
                    val (component, editor) = card(++index, chunk, leafLabel, onLeafClick, description, fileType, plain, segments)
                    list.add(component)
                    lastMergeKey = mergeKey
                    lastEditor = if (ownCard) null else editor
                    lastSeparation = separation
                    lastCard = component
                    list.revalidate()
                    list.repaint()
                }
                if (app.isDispatchThread) schedule(task) else app.invokeLater(task)
            }

            // A card whose body is a ready-made component (a metadata table) rather than a text editor. Shares the card
            // chrome — numbered header, per-test link, border, sticky overlay — with the text cards, so the two kinds
            // stack in one list. No ANSI/merge/copy path: the body owns its own interaction (the table copies cells).
            fun addComponentCard(
                body: JComponent,
                channel: String?,
                leafLabel: String?,
                onLeafClick: (() -> Unit)?,
                description: String?,
            ) {
                if (released) return
                val task = Runnable {
                    if (released) return@Runnable
                    if (index >= MAX_CARDS) {
                        if (!truncated) {
                            truncated = true
                            list.add(truncationNotice())
                            list.revalidate(); list.repaint()
                        }
                        return@Runnable
                    }
                    val n = ++index
                    val chunk = ChannelOutputStore.Chunk("", null, channel)
                    val wrapper = JBPanel<Nothing>(BorderLayout()).apply {
                        border = JBUI.Borders.customLine(JBColor.border(), 1)
                        add(buildHeader(n, chunk, leafLabel, onLeafClick, description), BorderLayout.NORTH)
                        add(body, BorderLayout.CENTER)
                    }
                    cardEntries += CardEntry(wrapper) { buildHeader(n, chunk, leafLabel, onLeafClick, description) }
                    list.add(wrapper)
                    lastMergeKey = null
                    lastEditor = null
                    lastCard = wrapper
                    list.revalidate(); list.repaint()
                }
                if (ApplicationManager.getApplication().isDispatchThread) schedule(task) else ApplicationManager.getApplication().invokeLater(task)
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
                description: String?,
                fileType: FileType?,
                plain: String,
                segments: List<AnsiSegment>,
            ): Pair<JComponent, EditorEx?> {
                // A language message gets that language's highlighting (ANSI dropped); a format-less message keeps its
                // ANSI colors over a plain-text viewer. Both editor-backed cards fold following messages of the same
                // channel into one canvas; only a preview-bodied card (markdown/HTML) can't be appended to.
                val mergeableEditor: EditorEx?
                val body: JComponent
                if (fileType != null) {
                    val preview = previewCard(fileType, plain)
                    if (preview != null) {
                        body = preview
                        mergeableEditor = null
                    } else {
                        val (component, editor) = editorCard(fileType, plain, null)
                        body = component
                        mergeableEditor = editor
                    }
                } else {
                    val (component, editor) = editorCard(PlainTextFileType.INSTANCE, plain, segments)
                    body = component
                    mergeableEditor = editor
                }
                val wrapper = JBPanel<Nothing>(BorderLayout()).apply {
                    border = JBUI.Borders.customLine(JBColor.border(), 1)
                    add(buildHeader(n, chunk, leafLabel, onLeafClick, description), BorderLayout.NORTH)
                    add(withFloatingActions(body, mergeableEditor, plain), BorderLayout.CENTER)
                }
                // Register for the sticky overlay (rebuild the header on demand so its hyperlink/level stay live).
                cardEntries += CardEntry(wrapper) { buildHeader(n, chunk, leafLabel, onLeafClick, description) }
                return wrapper to mergeableEditor
            }

            // Header: #N · channel on the left, (in aggregate tabs) the test name as a tree-navigating link, and the log
            // level pinned to the right tinted by severity. Rebuilt fresh for both the card and the sticky overlay.
            private fun buildHeader(
                n: Int,
                chunk: ChannelOutputStore.Chunk,
                leafLabel: String?,
                onLeafClick: (() -> Unit)?,
                description: String? = null,
            ): JComponent {
                val left = JBPanel<Nothing>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(3, 6, 2, 6)
                    val idText = buildString { append('#').append(n); if (showChannel) chunk.channel?.let { append("  ·  ").append(it) } }
                    add(JBLabel(idText).apply { font = JBUI.Fonts.smallFont(); foreground = JBColor.GRAY })
                    if (leafLabel != null) {
                        add(JBLabel("  ·  ").apply { font = JBUI.Fonts.smallFont(); foreground = JBColor.GRAY })
                        add(HyperlinkLabel(leafLabel).apply {
                            addHyperlinkListener { onLeafClick?.invoke() }
                            if (!description.isNullOrBlank()) toolTipText = description
                        })
                    }
                    if (!description.isNullOrBlank()) {
                        add(JBLabel("  — $description").apply {
                            font = JBUI.Fonts.smallFont()
                            foreground = JBColor.GRAY
                        })
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

            private fun previewCard(fileType: FileType, text: String): JComponent? {
                if (fileType.name.equals("HTML", ignoreCase = true)) {
                    return htmlPreviewCard(text)
                }
                // Gate cheaply by type so we never build a heavy editor just to learn the type has no preview, then
                // confirm by contract (the editor must actually be a TextEditorWithPreview) rather than provider FQN.
                if (!fileType.name.equals("Markdown", ignoreCase = true)) return null
                // Built on the EDT from a tree-selection event, which doesn't hold read access by default; provider
                // createEditor reads the document, so wrap in a read action.
                return writeIntentRead {
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
                    } ?: return@writeIntentRead null
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

            private fun htmlPreviewCard(html: String): JComponent {
                val pane = javax.swing.JEditorPane().apply {
                    contentType = "text/html"
                    text = html
                    isEditable = false
                    isOpaque = false
                    border = JBUI.Borders.empty(4)
                    putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                    font = UIUtil.getLabelFont()
                }
                return object : JBPanel<Nothing>(BorderLayout()) {
                    init {
                        isOpaque = false
                        add(JBScrollPane(pane).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
                    }

                    override fun getPreferredSize(): Dimension =
                        Dimension(super.getPreferredSize().width, JBUI.scale(300))
                }
            }

            // Card editors are built on the EDT from a tree-selection event, which on the 2024+/2026 threading model
            // holds neither read nor write-intent by default. Editor creation reads the document AND (setHighlighter)
            // needs write-intent, so a plain ReadAction fails on 2026.1+ ('WriteIntentReadAction can not be called from
            // ReadAction'). Use the Application's write-intent-read action — available across supported builds, unlike
            // the newer static WriteIntentReadAction.compute(ThrowableComputable) overload (NoSuchMethodError on older).
            private fun <T> writeIntentRead(block: () -> T): T =
                ApplicationManager.getApplication().runWriteIntentReadAction(ThrowableComputable { block() })

            // A real viewer editor (not EditorTextField, which restricts the popup so Copy is greyed out): read-only,
            // but with working selection/copy, a lexer highlighter, line numbers and folding.
            private fun editorCard(fileType: FileType, text: String, ansiSegments: List<AnsiSegment>?): Pair<JComponent, EditorEx> =
                // Built on the EDT from a tree-selection event, which (since the platform 2024+ threading model) does NOT
                // hold read access by default — getDocument/editor creation read the model, so wrap in a read action.
                writeIntentRead {
                val ext = fileType.defaultExtension.ifBlank { "txt" }
                val vFile = LightVirtualFile("testo-message-$index.$ext", fileType, text)
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                    ?: EditorFactory.getInstance().createDocument(text)
                val editor = EditorFactory.getInstance().createViewer(document, null) as EditorEx
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

            private fun appendToEditor(editor: EditorEx, separator: String, plain: String, segments: List<AnsiSegment>) {
                val document = editor.document
                val base = document.textLength
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(base, separator + plain) }
                applyAnsi(editor, segments, base + separator.length)
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

        // Identifies the card a message folds into: same channel and same test. A typed key (not a joined string) keeps
        // a null channel — the plain output stream — distinct from a channel literally named "null".
        private data class MergeKey(val channel: String?, val leaf: String?)

        private class AnsiSegment(val text: String, val attributes: TextAttributes?)

        // A rendered message card plus a factory that rebuilds its header (for the sticky overlay copy).
        private class CardEntry(val panel: JComponent, val buildHeader: () -> JComponent)

        // A columnGroup's span in the table's view-column coordinates (inclusive), for the grouped header band.
        private class GroupRun(val label: String?, val first: Int, val last: Int)

        // Paints the columnGroup header row: one bordered, centered cell over each run's combined column width. Reads
        // widths straight off the table's column model (pinned in sizeColumns), so it needs no resize listener.
        private class GroupBand(private val table: JBTable, private val runs: List<GroupRun>) : JComponent() {
            init {
                isOpaque = true
                background = table.tableHeader.background
                font = table.tableHeader.font
            }

            override fun getPreferredSize(): Dimension =
                Dimension(table.tableHeader.preferredSize.width, table.tableHeader.preferredSize.height)

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRect(0, 0, width, height)
                    val columnModel = table.columnModel
                    val fm = g2.fontMetrics
                    for (run in runs) {
                        if (run.label.isNullOrEmpty()) continue
                        var x = 0
                        for (c in 0 until run.first) x += columnModel.getColumn(c).width
                        var w = 0
                        for (c in run.first..run.last) w += columnModel.getColumn(c).width
                        g2.color = JBColor.border()
                        g2.drawRect(x, 0, w - 1, height - 1)
                        g2.color = table.tableHeader.foreground
                        val tx = (x + (w - fm.stringWidth(run.label)) / 2).coerceAtLeast(x + JBUI.scale(2))
                        val ty = (height + fm.ascent - fm.descent) / 2
                        g2.drawString(run.label, tx, ty)
                    }
                } finally {
                    g2.dispose()
                }
            }
        }

        // Manual sort model: keeps a view→data row order so a header-name click can sort without a TableRowSorter, whose
        // built-in header handler would also fire on the chart-glyph click. Numeric-aware, like the channel sort.
        private inner class MatrixTableModel(val matrix: MetadataMatrix) : AbstractTableModel() {
            private val order = MutableList(matrix.rows.size) { it }
            var sortColumn = -1; private set
            var sortAscending = true; private set

            override fun getRowCount() = matrix.rows.size
            override fun getColumnCount() = matrix.columns.size + 1
            override fun getColumnName(column: Int) = if (column == 0) "" else matrix.columns[column - 1].name
            override fun isCellEditable(row: Int, column: Int) = false
            // The cell shows the unit-formatted value; sorting (below) compares the raw one, so a `ms` column orders by
            // real duration, not by "115 ns" vs "1.5 µs" text.
            override fun getValueAt(row: Int, column: Int): String {
                val dataRow = order[row]
                if (column == 0) return matrix.rows[dataRow]
                return matrix.displayValue(matrix.rows[dataRow], matrix.columns[column - 1])
            }
            fun rowNameAt(viewRow: Int): String = matrix.rows[order[viewRow]]

            fun toggleSort(column: Int) {
                sortAscending = if (sortColumn == column) !sortAscending else true
                sortColumn = column
                val base = Comparator<Int> { a, b -> NUMERIC_AWARE.compare(rawAt(a, column), rawAt(b, column)) }
                order.sortWith(if (sortAscending) base else base.reversed())
                fireTableDataChanged()
            }

            private fun rawAt(dataRow: Int, column: Int): String {
                val row = matrix.rows[dataRow]
                return if (column == 0) row else matrix.value(row, matrix.columns[column - 1])
            }
        }

        // Row-name cell: the bold name (WEST) with a chart glyph pinned to the right edge (EAST), so the glyph sits
        // exactly where the click zone is tested — click it to chart the row. The glyph shows only for a uniform row.
        private class MatrixRowHeaderRenderer(private val model: MatrixTableModel) : TableCellRenderer {
            private val glyph = ChartGlyphIcon(JBColor.GRAY)
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component = JBPanel<Nothing>(BorderLayout()).apply {
                isOpaque = true
                background = if (isSelected) table.selectionBackground else table.background
                add(JBLabel(value?.toString().orEmpty()).apply {
                    font = table.font.deriveFont(java.awt.Font.BOLD)
                    foreground = if (isSelected) table.selectionForeground else table.foreground
                    border = JBUI.Borders.empty(1, 6, 1, 0)
                }, BorderLayout.WEST)
                if (model.matrix.isRowUniform(model.rowNameAt(row))) {
                    add(JBLabel(glyph).apply { border = JBUI.Borders.empty(1, 4) }, BorderLayout.EAST)
                }
            }
        }

        // Column header: the name plus a sort arrow when this is the sort key (WEST), and — for data columns — a chart
        // glyph pinned to the right edge (EAST) where the click zone is tested. The arrow is drawn ourselves: no
        // TableRowSorter, whose default arrow overlaps a cramped name.
        private class MatrixColumnHeaderRenderer(private val model: MatrixTableModel) : TableCellRenderer {
            private val glyph = ChartGlyphIcon(JBColor.GRAY)
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val header = table.tableHeader
                val arrow = when {
                    column != model.sortColumn -> ""
                    model.sortAscending -> "  ▲"
                    else -> "  ▼"
                }
                return JBPanel<Nothing>(BorderLayout()).apply {
                    isOpaque = true
                    background = header.background
                    add(JBLabel("${value?.toString().orEmpty()}$arrow").apply {
                        font = header.font
                        foreground = header.foreground
                        border = JBUI.Borders.empty(2, 6, 2, 0)
                    }, BorderLayout.WEST)
                    // The glyph (chart this column) shows only for a uniform column — a mixed one has nothing comparable.
                    if (column >= 1 && model.matrix.isColumnUniform(model.matrix.columns[column - 1])) {
                        add(JBLabel(glyph).apply { border = JBUI.Borders.empty(2, 4) }, BorderLayout.EAST)
                    }
                }
            }
        }

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
        // Live chunks arrive off the EDT (test-reader thread), but performWhenNoDeferredOutput asserts EDT — hop first.
        private fun addTestoIconInlay(view: ConsoleViewImpl, offset: Int) {
            val app = ApplicationManager.getApplication()
            val task = Runnable {
                view.performWhenNoDeferredOutput {
                    val editor = view.editor as? EditorEx ?: return@performWhenNoDeferredOutput
                    if (offset in 0..editor.document.textLength) {
                        editor.inlayModel.addInlineElement(offset, false, TestoIconInlayRenderer)
                    }
                }
            }
            if (app.isDispatchThread) task.run() else app.invokeLater(task)
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
            // don't fit, instead of wrapping to extra rows like JBTabbedPane. The log-level filter rides on each tab's
            // tabPaneActions (see entryPointActions), which the platform paints at the right edge of that row.
            val tabbed = JBEditorTabs(project, this@ChannelTabsController)
            tabbed.addListener(object : com.intellij.ui.tabs.TabsListener {
                override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) = buildLazyTab(newSelection)
            })
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
            private const val METADATA_TAB = "Metadata"
            private const val FADE_STEP = 0.18f

            // Each message is a full editor; cap how many we materialize so a chatty channel can't spawn thousands.
            private const val MAX_CARDS = 300

            // Cards built per EDT event during replay — small enough to keep the UI responsive, big enough to fill fast.
            private const val CARDS_PER_BATCH = 25

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

            // The clickable width at the right edge of a header cell / row-name cell: the chart glyph plus padding.
            private val CHART_ZONE = JBUI.scale(20)

            // Metadata table sort: compare as numbers when both cells parse, else fall back to case-insensitive text —
            // so a `meanUs` column orders 9 before 10, while a row-name column still sorts alphabetically.
            private val NUMERIC_AWARE = Comparator<String> { a, b ->
                val na = a.toDoubleOrNull()
                val nb = b.toDoubleOrNull()
                if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b, ignoreCase = true)
            }

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

    // A tiny three-bar glyph for the per-column / per-row "show chart" affordance, drawn so it needs no bundled icon.
    private class ChartGlyphIcon(private val color: Color) : Icon {
        private val w get() = JBUI.scale(11)
        private val h get() = JBUI.scale(10)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = color
                val barW = JBUI.scale(2)
                val gap = JBUI.scale(1)
                var bx = x
                for (bh in intArrayOf(h / 2, h, h * 2 / 3)) {
                    g2.fillRect(bx, y + h - bh, barW, bh)
                    bx += barW + gap
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = w
        override fun getIconHeight(): Int = h
    }
}
