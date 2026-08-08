package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * The [TestoRunTarget] of every node of the current run, recorded as its `testStarted` / `testSuiteStarted` arrives.
 *
 * Keyed by `nodeId`, the only thing that identifies a node outright — see [TestoNodeIndex]. The location hint does
 * not: it names code, and one method announced under two types answers with the same hint twice, which is exactly
 * the case where the target differs, since the type is what goes into `--type`.
 *
 * A node that narrows nothing is not stored — it has nothing to add to what the producer works out of the PSI.
 */
class TestoTargetStore(private val nodes: TestoNodeIndex) {
    private val lock = Any()
    private val byNode = HashMap<String, TestoRunTarget>()

    /** @param nodeId the `nodeId` of the message that opened the node. */
    fun note(nodeId: String?, target: TestoRunTarget) {
        nodeId ?: return
        if (target.isEmpty) return
        synchronized(lock) { byNode[nodeId] = target }
    }

    /** The recipe for rerunning the node a tree selection stands for, or `null` when this run announced none. */
    fun targetFor(proxy: SMTestProxy?): TestoRunTarget? = nodes.nodeIdOf(proxy)?.let { targetForNode(it) }

    /** The same lookup, by the id itself — resolving a tree node to one is [TestoNodeIndex]'s half of the job. */
    internal fun targetForNode(nodeId: String): TestoRunTarget? = synchronized(lock) { byNode[nodeId] }

    fun clear() {
        synchronized(lock) { byNode.clear() }
    }
}
