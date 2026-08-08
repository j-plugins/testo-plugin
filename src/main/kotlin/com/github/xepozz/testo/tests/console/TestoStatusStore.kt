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
 * Group nodes are kept in a map of their own ([noteSuite]): they have a verdict worth showing in the tree, but they
 * are not tests and must not reach the tally.
 *
 * A Testo too old to send `status` is not left without counters: [noteInferred] derives one from the message that
 * closed the test, which is the same three the TeamCity protocol has always had — `testFailed`, `testIgnored` and a
 * plain `testFinished`. A reported status always wins over an inferred one whichever arrives first, so a run that
 * does send `status` never has its verdict coarsened by the fallback.
 *
 * The tally is kept incrementally rather than by walking the tree on a timer: the tree is mutated off the EDT while
 * the run streams in, so repeatedly iterating it from the UI is a race. [recountFrom] does walk it, but only once the
 * run is over — replacing the running estimate with what the tree actually holds, including the tests a Stop left
 * unfinished.
 */
class TestoStatusStore(private val channels: ChannelOutputStore) {
    private class Entry(val status: TestoTestStatus, val reported: Boolean)

    private val lock = Any()
    private val byKey = HashMap<String, Entry>()
    private val suiteByKey = HashMap<String, TestoTestStatus>()
    private val counts = EnumMap<TestoTestStatus, Int>(TestoTestStatus::class.java)
    private val assertionsByKey = HashMap<String, Int>()
    private var started = 0
    private var declaredTotal = 0

    /**
     * Testo's own verdict, off the `status` attribute.
     *
     * @param name the `name` attribute of the service message the status came with.
     */
    fun note(name: String, status: TestoTestStatus) = put(name, status, reported = true, onlyIfAbsent = false)

    /**
     * The verdict read off the kind of message that closed the test, for a Testo that sends no `status`.
     *
     * @param onlyIfAbsent for the guess that a bare `testFinished` means the test passed — true only until the test
     *        has said otherwise, since `testFailed` and `testIgnored` come first and `testFinished` follows them.
     */
    fun noteInferred(name: String, status: TestoTestStatus, onlyIfAbsent: Boolean) =
        put(name, status, reported = false, onlyIfAbsent = onlyIfAbsent)

    private fun put(name: String, status: TestoTestStatus, reported: Boolean, onlyIfAbsent: Boolean) {
        synchronized(lock) {
            val key = channels.keyFor(name)
            val previous = byKey[key]
            if (previous != null && (onlyIfAbsent || (previous.reported && !reported))) return
            byKey[key] = Entry(status, reported)
            if (previous?.status == status) return
            // Drop a counter that runs out instead of leaving a zero behind — the widget reads the map as "what to show".
            previous?.let { counts.computeIfPresent(it.status) { _, n -> (n - 1).takeIf { left -> left > 0 } } }
            counts.merge(status, 1, Int::plus)
        }
    }

    /**
     * The aggregated verdict of a group node — a run suite, a case, a DataProvider batch — off the `status` of its
     * `testSuiteFinished`.
     *
     * Filed apart from the tests on purpose. [counts] tallies *tests*, and Testo announces one suite per case, per
     * data-provider batch and per suite of the run: counting those as more tests would inflate every number the
     * toolbar shows and make the progress ring divide by a total no run can reach.
     */
    fun noteSuite(name: String, status: TestoTestStatus) {
        synchronized(lock) { suiteByKey[channels.keyFor(name)] = status }
    }

    /** Known status of the node, or the platform's coarse reading of it when nothing was recorded for it. */
    fun statusOf(proxy: SMTestProxy): TestoTestStatus? {
        if (proxy.isInProgress) return null
        val key = channels.keyFor(proxy.name)
        val known = synchronized(lock) {
            if (proxy.isSuite) suiteByKey[key] else byKey[key]?.status
        }
        return known ?: TestoTestStatus.fromProxy(proxy)
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

    /**
     * The `count` of a `testCount` service message — the only number that makes the ring exact from the first tick.
     * Added, not assigned: Testo announces one `testCount` per suite, and the platform's own progress bar sums them
     * the same way.
     */
    fun noteDeclaredTotal(total: Int) {
        synchronized(lock) { declaredTotal += total }
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
            suiteByKey.clear()
            counts.clear()
            assertionsByKey.clear()
            started = 0
            declaredTotal = 0
        }
    }
}
