package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.util.EnumMap

/**
 * Per-test Testo status and assertion count of the current run, plus the tally the toolbar summary renders.
 *
 * Keyed by `nodeId` — see [TestoNodeIndex] for why neither the name nor the location hint identifies a node. Group
 * nodes are kept apart from the tests: they have a verdict to draw but must not reach the tally.
 *
 * The tally is incremental because the tree is mutated off the EDT while the run streams, so walking it on a timer
 * would race; [recountFrom] walks it once, after the run.
 */
class TestoStatusStore(private val nodes: TestoNodeIndex) {
    private class Entry(val status: TestoTestStatus, val reported: Boolean)

    private val lock = Any()
    private val byNode = HashMap<String, Entry>()
    private val suiteByNode = HashMap<String, TestoTestStatus>()
    private val counts = EnumMap<TestoTestStatus, Int>(TestoTestStatus::class.java)
    private val assertionsByNode = HashMap<String, Int>()
    private var started = 0
    private var declaredTotal = 0

    /** Testo's own verdict, off the `status` attribute. */
    fun note(nodeId: String, status: TestoTestStatus) = put(nodeId, status, reported = true, onlyIfAbsent = false)

    /**
     * The verdict of a Testo too old to send `status`, taken from which message closed the test.
     *
     * @param onlyIfAbsent for the guess that a bare `testFinished` means a pass — it follows `testFailed` and
     *        `testIgnored` rather than replacing them, so it only counts while the test has said nothing else.
     */
    fun noteInferred(nodeId: String, status: TestoTestStatus, onlyIfAbsent: Boolean) =
        put(nodeId, status, reported = false, onlyIfAbsent = onlyIfAbsent)

    /** A reported status outranks an inferred one whichever arrives first. */
    private fun put(nodeId: String, status: TestoTestStatus, reported: Boolean, onlyIfAbsent: Boolean) {
        synchronized(lock) {
            val previous = byNode[nodeId]
            if (previous != null && (onlyIfAbsent || (previous.reported && !reported))) return
            byNode[nodeId] = Entry(status, reported)
            if (previous?.status == status) return
            // Drop a counter that runs out rather than leave a zero: the widget reads the map as "what to show".
            previous?.let { counts.computeIfPresent(it.status) { _, n -> (n - 1).takeIf { left -> left > 0 } } }
            counts.merge(status, 1, Int::plus)
        }
    }

    /** The rolled-up verdict of a group node, off the `status` of its `testSuiteFinished`. Never counted as a test. */
    fun noteSuite(nodeId: String, status: TestoTestStatus) {
        synchronized(lock) { suiteByNode[nodeId] = status }
    }

    /** Known status, or the platform's coarse reading for a node this run never announced (an imported one). */
    fun statusOf(proxy: SMTestProxy): TestoTestStatus? {
        if (proxy.isInProgress) return null
        val nodeId = nodes.nodeIdOf(proxy)
        val known = nodeId?.let {
            synchronized(lock) { if (proxy.isSuite) suiteByNode[it] else byNode[it]?.status }
        }
        return known ?: TestoTestStatus.fromProxy(proxy)
    }

    fun counts(): Map<TestoTestStatus, Int> = synchronized(lock) { EnumMap(counts) }

    fun finishedCount(): Int = synchronized(lock) { counts.values.sum() }

    /** Per test rather than summed on arrival, so a re-reported test replaces its own figure. */
    fun noteAssertions(nodeId: String, assertions: Int) {
        synchronized(lock) { assertionsByNode[nodeId] = assertions }
    }

    /** Assertions across the run, or `null` when this Testo reports none. */
    fun assertionCount(): Int? = synchronized(lock) {
        if (assertionsByNode.isEmpty()) null else assertionsByNode.values.sum()
    }

    fun noteStarted() {
        synchronized(lock) { started++ }
    }

    /** Added, not assigned: Testo sends one `testCount` per suite, as the platform's own progress bar reads them. */
    fun noteDeclaredTotal(total: Int) {
        synchronized(lock) { declaredTotal += total }
    }

    /** What the ring divides by: the announced total, or however many tests have started until one arrives. */
    fun totalHint(): Int = synchronized(lock) { maxOf(declaredTotal, started, counts.values.sum()) }

    /**
     * Replaces the tally with what the finished tree holds, including the tests a Stop left unfinished. Call only
     * once nothing is streaming: this is the one place that reads a node's children.
     */
    fun recountFrom(root: SMTestProxy) {
        val leaves = root.allTests.filter { it !== root && !it.isSuite }
        val fresh = EnumMap<TestoTestStatus, Int>(TestoTestStatus::class.java)
        for (leaf in leaves) statusOf(leaf)?.let { fresh.merge(it, 1, Int::plus) }
        synchronized(lock) {
            counts.clear()
            counts.putAll(fresh)
            started = leaves.size
        }
    }

    fun clear() {
        synchronized(lock) {
            byNode.clear()
            suiteByNode.clear()
            counts.clear()
            assertionsByNode.clear()
            started = 0
            declaredTotal = 0
        }
    }
}
