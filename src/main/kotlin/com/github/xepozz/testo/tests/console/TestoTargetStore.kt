package com.github.xepozz.testo.tests.console

/**
 * The [TestoRunTarget] of every node of the current run, recorded as its `testStarted` / `testSuiteStarted` arrives.
 *
 * Keyed by the location hint itself, unlike [ChannelOutputStore] and [TestoStatusStore], which key by name because
 * the messages they follow (`testStdOut`, `testFinished`) carry nothing else. Names are not unique: every data
 * provider in a run opens a `Dataset #0 [0]`, and a name-keyed target sends a right-click on one of them off to
 * rerun another class's data set. The hint is unique per node, both sides have it — the converter off the message,
 * the producer off `SMTestProxy.getLocationUrl()`, which the id-based convertor sets when it builds the proxy — so
 * nothing has to be translated.
 *
 * A node announced without a hint is not stored. It could not be rerun anyway: with no location the platform runs no
 * run-configuration producer at all, so there is nothing to hand a target to.
 */
class TestoTargetStore {
    private val lock = Any()
    private val byLocation = HashMap<String, TestoRunTarget>()

    fun note(target: TestoRunTarget) {
        val hint = target.locationHint?.takeIf { it.isNotBlank() } ?: return
        if (target.isEmpty) return
        synchronized(lock) { byLocation[hint] = target }
    }

    /** @param locationUrl the `locationUrl` of the tree node, i.e. the `locationHint` its message carried. */
    fun targetFor(locationUrl: String?): TestoRunTarget? {
        if (locationUrl == null) return null
        synchronized(lock) { return byLocation[locationUrl] }
    }

    fun clear() {
        synchronized(lock) { byLocation.clear() }
    }
}
