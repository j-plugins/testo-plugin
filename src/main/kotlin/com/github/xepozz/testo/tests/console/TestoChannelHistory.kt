package com.github.xepozz.testo.tests.console

import com.google.gson.Gson
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Channel output survives a run only in memory ([ChannelOutputStore]); the IDE's test-history XML keeps just the plain
 * per-test stdout/stderr text and drops our channel/level/icon structure. And on import the platform forces its own
 * [com.intellij.execution.testframework.sm.runner.history.ImportedTestConsoleProperties] +
 * `ImportedToGeneralTestEventsConverter`, so neither our console nor our converter runs — the channel tabs never appear.
 *
 * This bridges both gaps using the one per-test datum the history writer round-trips: [SMTestProxy.getMetainfo]. On a
 * live run we encode each test's whole "all" stream (every chunk in order, tagged with its channel/level, plus the
 * icon/color of each channel it used) into the proxy's metainfo, which [com.intellij.execution.testframework.export.TestResultsXmlFormatter]
 * serializes. On import we decode it back into a fresh store and install the same channel UI. Our test locator does not
 * read metainfo, so this is free to use.
 */
internal object TestoChannelHistory {
    private val gson = Gson()
    private const val VERSION = 1

    // Short field names keep the serialized metainfo (an XML attribute) compact. Nulls are omitted by Gson on write and
    // arrive as null on read, so a non-Testo metainfo string deserializes to v=0 and is ignored.
    private data class Wire(val v: Int = 0, val c: List<WChunk> = emptyList(), val m: Map<String, WMeta> = emptyMap())
    private data class WChunk(val t: String = "", val l: String? = null, val ch: String? = null)
    private data class WMeta(val i: String? = null, val co: String? = null)

    /**
     * Subscribe (for the lifetime of [console]) so each finished test stamps its channel output onto its proxy's
     * metainfo, before the history export reads it. Called from the live install path.
     */
    fun subscribeMetainfoWriter(project: Project, console: SMTRunnerConsoleView, store: ChannelOutputStore) {
        val connection = project.messageBus.connect(console)
        var root: SMTestProxy? = null
        connection.subscribe(
            com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener.TEST_STATUS,
            object : SMTRunnerEventsAdapter() {
                override fun onTestingStarted(testsRoot: SMRootTestProxy) {
                    root = testsRoot
                }

                override fun onTestFinished(test: SMTestProxy) {
                    // Topic is project-wide; ignore proxies from other concurrent runs.
                    if (root != null && !isUnder(root!!, test)) return
                    val key = store.keyFor(test.name)
                    test.metainfo = encode(store, key) ?: return
                }
            },
        )
    }

    /**
     * Wire a console built by the platform's own "Import Test Results": once its tree is built, decode every proxy's
     * metainfo into a fresh store and install the channel UI. Our own history goes through the run archive instead
     * (`com.github.xepozz.testo.runs`), which replays the real stream and needs none of this.
     */
    fun installForImport(project: Project, console: SMTRunnerConsoleView) {
        // The platform builds the imported console, so there is no shared delegate state. Rebuild the channels from the
        // metainfo the run stored into each proxy, into a fresh store + level filter.
        val store = ChannelOutputStore()
        val levelFilter = LogLevelFilter()
        whenTreeStable(console) { root ->
            root?.let { forEachDescendant(it) { proxy -> decode(store, levelFilter, proxy) } }
            // Pass the root so install() renders the whole imported tree's aggregate immediately, independent of the
            // async JTree selection (which is often still null at this instant).
            TestoChannelsUi.install(console, store, levelFilter, project, console, root)
        }
    }

    /**
     * Select the node of [url] once the tree has finished building — for a replayed archive, where the tree is still
     * filling while the recorded output streams in.
     */
    fun selectWhenReady(console: SMTRunnerConsoleView, url: String) {
        whenTreeStable(console) { root -> root?.let { select(console, it, url) } }
    }

    /**
     * Run [action] with the results tree once it has stopped growing (stable and non-empty), or after ~10s with
     * whatever is there. We poll rather than subscribe to `SMTRunnerEventsListener`: a short run can finish replaying
     * before we are handed the console, and its events are then already fired and missed.
     */
    private fun whenTreeStable(console: SMTRunnerConsoleView, action: (SMTestProxy?) -> Unit) {
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, console)
        var lastCount = -1
        fun poll(attempt: Int) {
            val root = (console.resultsViewer as? SMTestRunnerResultsForm)?.testsRootNode
            val count = root?.let { countDescendants(it) } ?: 0
            if ((count > 0 && count == lastCount) || attempt >= 200) {
                action(root)
                return
            }
            lastCount = count
            alarm.addRequest({ poll(attempt + 1) }, 50)
        }
        alarm.addRequest({ poll(0) }, 0)
    }

    private fun select(console: SMTRunnerConsoleView, root: SMTestProxy, url: String) {
        val form = console.resultsViewer as? SMTestRunnerResultsForm ?: return
        val match = findByLocationUrl(root, url) ?: return
        ApplicationManager.getApplication().invokeLater { form.selectAndNotify(match) }
    }

    private fun countDescendants(node: SMTestProxy): Int {
        var n = 0
        for (child in node.children) n += 1 + countDescendants(child)
        return n
    }

    private fun forEachDescendant(node: SMTestProxy, action: (SMTestProxy) -> Unit) {
        for (child in node.children) {
            action(child)
            forEachDescendant(child, action)
        }
    }

    // Find the node for a clicked test. Prefer an exact locationUrl match; fall back to a node whose url starts with the
    // target (a data-provider method whose datasets carry a " with data set #N" suffix), so selecting it shows the
    // method's aggregate.
    private fun findByLocationUrl(root: SMTestProxy, url: String): SMTestProxy? {
        var prefixMatch: SMTestProxy? = null
        var result: SMTestProxy? = null
        forEachDescendant(root) { proxy ->
            val loc = proxy.locationUrl
            if (loc == url) result = result ?: proxy
            else if (prefixMatch == null && loc != null && loc.startsWith(url)) prefixMatch = proxy
        }
        return result ?: prefixMatch
    }

    /** Encodes the test's full "all" stream (and the icon/color of every channel it used) for [SMTestProxy.setMetainfo]. */
    private fun encode(store: ChannelOutputStore, key: String): String? {
        val chunks = store.allFor(key)
        if (chunks.isEmpty()) return null
        val channels = chunks.mapNotNullTo(LinkedHashSet()) { it.channel }
        val meta = channels.associateWith { WMeta(store.channelIcon(it), store.channelColor(it)) }
        val wire = Wire(VERSION, chunks.map { WChunk(it.text, it.level, it.channel) }, meta)
        return gson.toJson(wire)
    }

    /** Replays a decoded proxy's chunks into [store] through the same calls the live converter makes. */
    private fun decode(store: ChannelOutputStore, levelFilter: LogLevelFilter, proxy: SMTestProxy) {
        val raw = proxy.metainfo?.takeIf { it.isNotBlank() } ?: return
        val wire = runCatching { gson.fromJson(raw, Wire::class.java) }.getOrNull() ?: return
        if (wire.v != VERSION) return
        // Key the same way the channel UI looks tests up: keyFor(name) -> locationUrl once remembered.
        val key = proxy.locationUrl ?: proxy.name
        store.rememberLocation(proxy.name, key)
        wire.m.forEach { (channel, m) ->
            m.i?.let { store.setChannelIcon(channel, it) }
            m.co?.let { store.setChannelColor(channel, it) }
        }
        wire.c.forEach { chunk ->
            levelFilter.noteSeen(chunk.l)
            store.appendAll(key, chunk.t, chunk.l, chunk.ch)
            if (chunk.ch != null) store.append(key, chunk.ch, chunk.t, chunk.l)
            else store.appendOutput(key, chunk.t, chunk.l)
        }
    }

    private fun isUnder(root: SMTestProxy, node: SMTestProxy): Boolean {
        var current: SMTestProxy? = node
        while (current != null) {
            if (current === root) return true
            current = current.parent
        }
        return false
    }
}
