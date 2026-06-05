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
     * Wire an imported-history console: once the replayed tree is fully built, decode every proxy's metainfo into the
     * store and install the channel UI. Called when the augmenter sees an `ImportedTestConsoleProperties` console.
     *
     * We poll instead of subscribing to `SMTRunnerEventsListener`: the augmenter only hands us the console after
     * `processStarted`, by which point a small import may have already replayed and fired (and missed) its events,
     * leaving the channels empty even though the tree shows. Polling for a stable node count is immune to that race and
     * never double-decodes (a single pass over the finished tree).
     */
    fun installForImport(project: Project, console: SMTRunnerConsoleView) {
        // When we drive the import (TestoImportedConsoleProperties), reuse its delegate's store + level filter so the
        // toolbar log-level filter and the channel UI share one state. The platform import path (clock dropdown) has no
        // delegate, so fall back to fresh ones.
        val delegate = (console.properties as? TestoImportedConsoleProperties)?.delegate
        val store = delegate?.channelStore ?: ChannelOutputStore()
        val levelFilter = delegate?.levelFilter ?: LogLevelFilter()
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, console)
        var lastCount = -1
        fun poll(attempt: Int) {
            val root = (console.resultsViewer as? SMTestRunnerResultsForm)?.testsRootNode
            val count = root?.let { countDescendants(it) } ?: 0
            // Install once the tree has stopped growing (stable, non-empty), or give up after ~10s and show what we have.
            if ((count > 0 && count == lastCount) || attempt >= 200) {
                root?.let { forEachDescendant(it) { proxy -> decode(store, levelFilter, proxy) } }
                // Pass the root so install() renders the whole imported tree's aggregate immediately, independent of the
                // async JTree selection (which is often still null at this instant).
                TestoChannelsUi.install(console, store, levelFilter, project, console, root)
                return
            }
            lastCount = count
            alarm.addRequest({ poll(attempt + 1) }, 50)
        }
        alarm.addRequest({ poll(0) }, 0)
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
