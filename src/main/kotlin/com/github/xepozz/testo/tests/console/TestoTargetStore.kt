package com.github.xepozz.testo.tests.console

/**
 * The [TestoRunTarget] of every node of the current run, recorded as its `testStarted` / `testSuiteStarted` arrives.
 *
 * Keyed exactly like [ChannelOutputStore] and [TestoStatusStore] — through [ChannelOutputStore.keyFor] — so the
 * converter (which writes) and the run-configuration producer (which reads a selected tree node) agree on the same
 * node without ever touching `SMTestProxy.locationUrl`, which the platform resolves lazily.
 */
class TestoTargetStore(private val channels: ChannelOutputStore) {
    private val lock = Any()
    private val byKey = HashMap<String, TestoRunTarget>()

    /** @param name the `name` attribute of the message that opened the node. */
    fun note(name: String, target: TestoRunTarget) {
        if (target.isEmpty) return
        synchronized(lock) { byKey[channels.keyFor(name)] = target }
    }

    /** @param name the name of the tree node, which is the same `name` the message that opened it carried. */
    fun targetFor(name: String): TestoRunTarget? = synchronized(lock) { byKey[channels.keyFor(name)] }

    fun clear() {
        synchronized(lock) { byKey.clear() }
    }
}
