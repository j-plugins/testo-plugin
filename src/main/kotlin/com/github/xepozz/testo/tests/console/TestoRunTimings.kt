package com.github.xepozz.testo.tests.console

/**
 * The clock of one run, split into the phases the plugin can tell apart.
 *
 * Four marks — process start, first `testStarted`, last test event, run over — and the three spans between them add
 * up to the total exactly, so the hover accounts for every millisecond the toolbar shows.
 *
 * The sum of the tests' own `duration` attributes is kept beside them but is not a phase: tests run concurrently, so
 * two five-second tests on two fibers sum to ten inside a five-second window. Against [Snapshot.testsMs] the sum
 * says how much overlapped. Held per test, so a test reporting twice replaces its own figure.
 */
class TestoRunTimings {

    data class Snapshot(
        /** Process start to the end (or to now, while it runs) — the figure the toolbar renders. */
        val totalMs: Long,
        /** Process start to the first `testStarted`: interpreter boot, autoload, config, test discovery. */
        val startupMs: Long,
        /** First `testStarted` to the last test event: the wall clock the tests occupied. */
        val testsMs: Long,
        /** Sum of every test's own reported duration; above [testsMs] by however much ran in parallel. */
        val summedTestsMs: Long,
        /**
         * Last test event to the end: merging reports and shutting down. Not "teardown" — fixture teardown runs
         * inside a test and is part of [testsMs].
         */
        val postProcessingMs: Long,
        val finished: Boolean,
    ) {
        /** How many tests' worth of time fitted into the testing window, or `null` when there is nothing to compare. */
        val parallelism: Double?
            get() = if (testsMs > 0 && summedTestsMs > 0) summedTestsMs.toDouble() / testsMs else null
    }

    private val lock = Any()
    private val durationByKey = HashMap<String, Long>()

    // Nullable rather than a zero sentinel: zero is a perfectly good timestamp once a caller supplies its own clock.
    private var startedAt: Long? = null
    private var firstTestAt: Long? = null
    private var lastTestAt: Long? = null
    private var finishedAt: Long? = null

    fun noteStart(at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            startedAt = at
            finishedAt = null
        }
    }

    fun noteTestStarted(at: Long = System.currentTimeMillis()) {
        synchronized(lock) { if (firstTestAt == null) firstTestAt = at }
    }

    /**
     * @param key the test's storage key, resolved by the caller the same way [ChannelOutputStore.keyFor] does.
     * @param durationMs the `duration` attribute, absent on a test Testo never timed (an ignored one).
     */
    fun noteTestFinished(key: String, durationMs: Long?, at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            lastTestAt = at
            durationMs?.let { durationByKey[key] = it }
        }
    }

    /** The end of the run. The first caller wins: testing finishing and the process exiting are the same moment. */
    fun noteFinish(at: Long = System.currentTimeMillis()) {
        synchronized(lock) { if (finishedAt == null) finishedAt = at }
    }

    fun isFinished(): Boolean = synchronized(lock) { finishedAt != null }

    fun snapshot(now: Long = System.currentTimeMillis()): Snapshot = synchronized(lock) {
        val start = startedAt ?: return Snapshot(0, 0, 0, 0, 0, false)
        val end = finishedAt ?: now
        val firstTest = firstTestAt
        val lastTest = lastTestAt
        // While tests are still coming the window runs up to now; once the run is over it stops at the last event.
        val testsEnd = if (finishedAt != null) lastTest else now
        return Snapshot(
            totalMs = (end - start).coerceAtLeast(0),
            startupMs = if (firstTest == null) 0 else (firstTest - start).coerceAtLeast(0),
            testsMs = if (firstTest == null || testsEnd == null) 0 else (testsEnd - firstTest).coerceAtLeast(0),
            summedTestsMs = durationByKey.values.sum(),
            postProcessingMs = if (finishedAt == null || lastTest == null) 0 else (end - lastTest).coerceAtLeast(0),
            finished = finishedAt != null,
        )
    }

    fun clear() {
        synchronized(lock) {
            durationByKey.clear()
            startedAt = null
            firstTestAt = null
            lastTestAt = null
            finishedAt = null
        }
    }
}
