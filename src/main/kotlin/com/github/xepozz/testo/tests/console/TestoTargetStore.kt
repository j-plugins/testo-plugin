package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * The [TestoRunTarget] of every node of the current run, recorded as its `testStarted` / `testSuiteStarted` arrives.
 *
 * Keyed by `nodeId` — see [TestoNodeIndex]. A hint will not do here in particular: one method announced under two
 * types shares it, and the type is what goes into `--type`.
 */
class TestoTargetStore(private val nodes: TestoNodeIndex) {
    private val lock = Any()
    private val byNode = HashMap<String, TestoRunTarget>()

    /** A target that narrows nothing is dropped: it adds nothing to what the producer reads off the PSI. */
    fun note(nodeId: String?, target: TestoRunTarget) {
        nodeId ?: return
        if (target.isEmpty) return
        synchronized(lock) { byNode[nodeId] = target }
    }

    fun targetFor(proxy: SMTestProxy?): TestoRunTarget? = nodes.nodeIdOf(proxy)?.let { targetForNode(it) }

    internal fun targetForNode(nodeId: String): TestoRunTarget? = synchronized(lock) { byNode[nodeId] }

    fun clear() {
        synchronized(lock) { byNode.clear() }
    }
}
