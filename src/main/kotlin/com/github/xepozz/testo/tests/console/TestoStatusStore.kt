package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import java.util.EnumMap

/**
 * The Testo status of each test of the current run, as reported in the `status` attribute of its service messages,
 * plus the assertion counts and the running tally the toolbar widget shows.
 *
 * Keyed exactly like [ChannelOutputStore] — through [ChannelOutputStore.keyFor], i.e. by the location hint remembered
 * on `testStarted` — so the converter (which writes) and the widget (which reads a tree node) agree on the same test
 * without ever touching `SMTestProxy.locationUrl`.
 *
 * The tally is kept incrementally rather than by walking the tree on a timer: the tree is mutated off the EDT while
 * the run streams in, so repeatedly iterating it from the UI is a race. [recountFrom] does walk it, but only once the
 * run is over — which is also what gives an imported history run its numbers, since a replay never reaches our
 * converter.
 */
class TestoStatusStore(private val channels: ChannelOutputStore) {
    private val lock = Any()
    private val byKey = HashMap<String, TestoTestStatus>()
    private val counts = EnumMap<TestoTestStatus, Int>(TestoTestStatus::class.java)
    private val assertionsByKey = HashMap<String, Int>()
    private var started = 0
    private var declaredTotal = 0

    /** @param name the `name` attribute of the service message the status came with. */
    fun note(name: String, status: TestoTestStatus) {
        synchronized(lock) {
            val key = channels.keyFor(name)
            val previous = byKey.put(key, status)
            if (previous == status) return
            // Drop a counter that runs out instead of leaving a zero behind — the widget reads the map as "what to show".
            previous?.let { counts.computeIfPresent(it) { _, n -> (n - 1).takeIf { left -> left > 0 } } }
            counts.merge(status, 1, Int::plus)
        }
    }

    /** Reported status of the node, or the platform's coarse reading of it when Testo sent none. */
    fun statusOf(proxy: SMTestProxy): TestoTestStatus? {
        if (proxy.isInProgress) return null
        val reported = synchronized(lock) { byKey[channels.keyFor(proxy.name)] }
        return reported ?: TestoTestStatus.fromProxy(proxy)
    }

    fun counts(): Map<TestoTestStatus, Int> = synchronized(lock) { EnumMap(counts) }

    fun finishedCount(): Int = synchronized(lock) { counts.values.sum() }

    /**
     * How many assertions the test ran, off the `assertions` attribute of its `testFinished`. Stored per test rather
     * than summed on arrival so a re-reported test replaces its own figure instead of doubling the total.
     */
    fun noteAssertions(name: String, assertions: Int) {
        synchronized(lock) { assertionsByKey[channels.keyFor(name)] = assertions }
    }

    /** Assertions across the whole run, or `null` when this Testo reports none at all. */
    fun assertionCount(): Int? = synchronized(lock) {
        if (assertionsByKey.isEmpty()) null else assertionsByKey.values.sum()
    }

    /** One more test has begun; what the progress ring divides by until a better number turns up. */
    fun noteStarted() {
        synchronized(lock) { started++ }
    }

    /** The `count` of a `testCount` service message — the only number that makes the ring exact from the first tick. */
    fun noteDeclaredTotal(total: Int) {
        synchronized(lock) { declaredTotal = total }
    }

    /**
     * How many tests the ring should divide by. Testo does not announce a total, so until it does this is however
     * many have started — the ring then approximates rather than lies, exactly as the platform's own progress bar
     * does, and becomes exact the moment a `testCount` message arrives.
     */
    fun totalHint(): Int = synchronized(lock) { maxOf(declaredTotal, started, counts.values.sum()) }

    /**
     * Replaces the tally with what the finished tree actually holds. Call only when nothing is streaming any more:
     * this is the one place that reads a node's children, and it also picks up the tests a Stop left unfinished.
     */
    fun recountFrom(root: SMTestProxy) {
        val leaves = root.allTests.filter { it !== root && !it.isSuite }
        val fresh = EnumMap<TestoTestStatus, Int>(TestoTestStatus::class.java)
        for (leaf in leaves) statusOf(leaf)?.let { fresh.merge(it, 1, Int::plus) }
        synchronized(lock) {
            counts.clear()
            counts.putAll(fresh)
            // The tree is now the exact total, including whatever a Stop left unstarted.
            started = leaves.size
        }
    }

    fun clear() {
        synchronized(lock) {
            byKey.clear()
            counts.clear()
            assertionsByKey.clear()
            started = 0
            declaredTotal = 0
        }
    }
}
