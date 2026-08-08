package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * Which node of Testo's protocol each node of the tree is: the `nodeId` its messages carry, by [SMTestProxy].
 *
 * `nodeId` is the only thing in the protocol that identifies a node outright. Nothing else does: a data set's name is
 * built out of its coordinates alone (`Dataset #0:0 [0]`), so every list-shaped provider in a run opens one of those;
 * and a location hint names code rather than a node, so one method announced under two types — `#[TestInline]` and
 * `#[Bench]` on the same method, say — answers with the same hint twice, since `TestIdentity` keeps the type beside
 * the fqn rather than in it.
 *
 * The reading side cannot see that id: `SMTestProxy` does not carry it, and the convertor's own id → node map is
 * private. But the platform hands both out together — `SMTRunnerEventsListener.onTestStarted(proxy, nodeId, parent)`
 * fires once per node as it starts — so the pairing is recorded here as it goes by, and every store keyed by node id
 * can be read back from a tree node.
 *
 * Keyed by the proxy itself, which neither [SMTestProxy] nor `AbstractTestProxy` gives an `equals` of its own — so
 * this is identity, exactly as intended.
 */
class TestoNodeIndex {
    private val lock = Any()
    private val idByProxy = HashMap<SMTestProxy, String>()

    /** The protocol id of a tree node, or `null` for one this run never announced — an imported history node. */
    fun nodeIdOf(proxy: SMTestProxy?): String? {
        proxy ?: return null
        synchronized(lock) { return idByProxy[proxy] }
    }

    /**
     * Starts recording the pairing.
     *
     * Called when the platform hands the converter its processor, which is before any output is read — so no node can
     * open unrecorded.
     */
    fun attachTo(processor: GeneralTestEventsProcessor) {
        processor.addEventsListener(object : SMTRunnerEventsAdapter() {
            override fun onTestStarted(test: SMTestProxy, nodeId: String?, parentNodeId: String?) = bind(test, nodeId)
            override fun onSuiteStarted(suite: SMTestProxy, nodeId: String?, parentNodeId: String?) = bind(suite, nodeId)
        })
    }

    private fun bind(proxy: SMTestProxy, nodeId: String?) {
        nodeId ?: return
        synchronized(lock) { idByProxy[proxy] = nodeId }
    }

    fun clear() {
        synchronized(lock) { idByProxy.clear() }
    }
}
