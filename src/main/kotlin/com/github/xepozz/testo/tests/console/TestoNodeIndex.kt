package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy

/**
 * `nodeId` by tree node, which is the only way to key anything per node.
 *
 * Nothing else identifies one. A data set's name is built out of its coordinates alone (`Dataset #0:0 [0]`), so every
 * list-shaped provider in a run opens one; and a location hint names code rather than a node, so one method announced
 * as both an inline test and a bench answers with the same hint twice — `TestIdentity` keeps the type beside the fqn.
 *
 * `SMTestProxy` does not carry the id and the convertor's own map is private, but
 * `SMTRunnerEventsListener.onTestStarted(proxy, nodeId, parentNodeId)` hands both out together, once per node.
 *
 * Keyed by proxy identity: neither `SMTestProxy` nor `AbstractTestProxy` overrides `equals`.
 */
class TestoNodeIndex {
    private val lock = Any()
    private val idByProxy = HashMap<SMTestProxy, String>()

    /** `null` for a node this run never announced — an imported history one. */
    fun nodeIdOf(proxy: SMTestProxy?): String? {
        proxy ?: return null
        synchronized(lock) { return idByProxy[proxy] }
    }

    /** Called when the platform hands over the processor, which is before any output is read. */
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
