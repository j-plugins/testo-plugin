package com.github.xepozz.testo.tests.console

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.IconUtil
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.lang.reflect.Field
import javax.swing.Icon
import javax.swing.JComponent

object TestoChannelsUi {
    private val myConsoleField: Field? = runCatching {
        Class.forName("com.intellij.execution.testframework.ui.TestResultsPanel")
            .getDeclaredField("myConsole")
            .apply { isAccessible = true }
    }.getOrNull()

    fun install(console: SMTRunnerConsoleView, store: ChannelOutputStore, project: Project, parent: Disposable) {
        val field = myConsoleField
        if (field == null) {
            thisLogger().warn("Testo channels disabled: TestResultsPanel.myConsole not found")
            return
        }
        val controller = ChannelTabsController(project, store, console, field)
        Disposer.register(parent, controller)
        console.resultsViewer.addEventsListener(controller)
    }

    private class ChannelTabsController(
        private val project: Project,
        private val store: ChannelOutputStore,
        private val console: SMTRunnerConsoleView,
        private val myConsoleField: Field,
    ) : TestResultsViewer.EventsListener, Disposable {

        private var tabs: JBTabbedPane? = null
        private var outputComponent: JComponent? = null
        private val dynamicConsoles = mutableListOf<ConsoleViewImpl>()

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
            while (tabbed.tabCount > 0) tabbed.removeTabAt(0)
            disposeDynamicConsoles()

            if (selected == null) {
                tabbed.addTab(OUTPUT_TAB, AllIcons.Debugger.Console, platform)
                return
            }

            if (!selected.isLeaf) {
                val leaves = selected.allTests.filter { it !== selected && it.isLeaf }
                addTab(tabbed, ALL_TAB, AllIcons.Actions.Show, aggregate(leaves, viewer) { store.allFor(it) })
                addTab(tabbed, OUTPUT_TAB, AllIcons.Debugger.Console, aggregate(leaves, viewer) { store.outputFor(it) })
                for (channel in channelsAcross(leaves)) {
                    val sample = leaves.firstNotNullOfOrNull { leaf ->
                        keyOf(leaf)?.let { store.channelsFor(it)[channel] }?.takeIf { it.isNotEmpty() }
                    } ?: emptyList()
                    addTab(tabbed, humanize(channel), channelIcon(channel, sample), aggregate(leaves, viewer) {
                        store.channelsFor(it)[channel] ?: emptyList()
                    })
                }
                return
            }

            val key = keyOf(selected)
            val all = if (key != null) store.allFor(key) else emptyList()
            if (all.isNotEmpty()) addTab(tabbed, ALL_TAB, AllIcons.Actions.Show, newConsole(all))
            tabbed.addTab(OUTPUT_TAB, AllIcons.Debugger.Console, platform)
            val channels = if (key != null) store.channelsFor(key) else emptyMap()
            for ((channel, chunks) in channels) {
                addTab(tabbed, humanize(channel), channelIcon(channel, chunks), newConsole(chunks))
            }
        }

        override fun dispose() {
            disposeDynamicConsoles()
            tabs = null
            outputComponent = null
        }

        private fun addTab(tabbed: JBTabbedPane, title: String, icon: Icon, view: ConsoleViewImpl?) {
            if (view != null) tabbed.addTab(title, icon, view.component)
        }

        private fun disposeDynamicConsoles() {
            dynamicConsoles.forEach { Disposer.dispose(it) }
            dynamicConsoles.clear()
        }

        private fun channelsAcross(leaves: List<SMTestProxy>): Set<String> {
            val channels = LinkedHashSet<String>()
            for (leaf in leaves) keyOf(leaf)?.let { channels.addAll(store.channelsFor(it).keys) }
            return channels
        }

        private fun channelIcon(channel: String, chunks: List<ChannelOutputStore.Chunk>): Icon {
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

        private fun aggregate(
            leaves: List<SMTestProxy>,
            viewer: TestResultsViewer,
            chunksFor: (String) -> List<ChannelOutputStore.Chunk>,
        ): ConsoleViewImpl? {
            val sections = leaves.mapNotNull { leaf ->
                val key = keyOf(leaf) ?: return@mapNotNull null
                val chunks = chunksFor(key)
                if (chunks.isEmpty()) null else leaf to chunks
            }
            if (sections.isEmpty()) return null
            val view = newConsole(emptyList())
            sections.forEachIndexed { index, (leaf, chunks) ->
                if (index > 0) view.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
                view.printHyperlink(fullName(leaf), HyperlinkInfo { selectInTree(viewer, leaf) })
                view.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
                printChunks(view, chunks)
            }
            return view
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

        private fun fullName(leaf: SMTestProxy): String {
            val fqn = leaf.locationUrl
                ?.substringAfter("://", "")
                ?.substringAfter("::", "")
                ?.takeIf { it.isNotBlank() }
                ?: return leaf.presentableName
            val method = fqn.substringAfterLast("::")
            return if (leaf.presentableName == method) fqn else "$fqn:${leaf.presentableName}"
        }

        private fun keyOf(leaf: SMTestProxy): String? =
            TestoOutputToGeneralEventsConverter.keyFor(leaf.locationUrl, leaf.name)

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
            for (chunk in chunks) {
                val outputType =
                    if (chunk.level == "stderr") ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
                decoder.escapeText(chunk.text, outputType) { text, key ->
                    view.print(text, ConsoleViewContentType.getConsoleViewType(key))
                }
            }
        }

        private fun ensureInstalled(): JBTabbedPane? {
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
            val tabbed = JBTabbedPane()
            tabbed.addTab(OUTPUT_TAB, AllIcons.Debugger.Console, original)
            holder.add(tabbed, BorderLayout.CENTER)
            holder.revalidate()
            holder.repaint()
            tabs = tabbed
            outputComponent = original
            return tabbed
        }

        companion object {
            private const val ALL_TAB = "All"
            private const val OUTPUT_TAB = "Output"

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
