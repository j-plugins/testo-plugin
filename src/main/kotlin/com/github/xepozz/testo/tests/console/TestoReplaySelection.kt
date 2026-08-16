package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.util.Alarm

/**
 * Selects a test's node in a replayed run — what the *Show history* lens clicks through to
 * ([com.github.xepozz.testo.runs.TestoRunReplayProfile]).
 */
internal object TestoReplaySelection {

    /** Select the node of [url] once the tree has finished building; the recorded output is still streaming in. */
    fun selectWhenReady(console: SMTRunnerConsoleView, url: String) {
        whenTreeStable(console) { root -> root?.let { select(console, it, url) } }
    }

    /**
     * Run [action] with the results tree once it has stopped growing (stable and non-empty), or after ~10s with
     * whatever is there. We poll rather than subscribe to `SMTRunnerEventsListener`: a short run can finish replaying
     * before we are handed the console, and its events are then already fired and missed.
     */
    private fun whenTreeStable(console: SMTRunnerConsoleView, action: (SMTestProxy?) -> Unit) {
        // Called from a pooled thread once the feed ends; the tab may have been closed while the log streamed, and
        // registering an Alarm on a disposed console throws.
        val alarm = runCatching { Alarm(Alarm.ThreadToUse.SWING_THREAD, console) }.getOrNull() ?: return
        var lastCount = -1
        fun poll(attempt: Int) {
            val root = (console.resultsViewer as? SMTestRunnerResultsForm)?.testsRootNode
            val count = root?.let { countDescendants(it) } ?: 0
            if ((count > 0 && count == lastCount) || attempt >= 200) {
                action(root)
                return
            }
            lastCount = count
            alarm.addRequest({ poll(attempt + 1) }, 50)
        }
        alarm.addRequest({ poll(0) }, 0)
    }

    private fun select(console: SMTRunnerConsoleView, root: SMTestProxy, url: String) {
        val form = console.resultsViewer as? SMTestRunnerResultsForm ?: return
        val match = findByLocationUrl(root, url) ?: return
        form.selectAndNotify(match)
    }

    private fun countDescendants(node: SMTestProxy): Int {
        var n = 0
        for (child in node.children) n += 1 + countDescendants(child)
        return n
    }

    private fun forEachDescendant(node: SMTestProxy, action: (SMTestProxy) -> Unit) {
        for (child in node.children) {
            action(child)
            forEachDescendant(child, action)
        }
    }

    // Find the node for a clicked test. Prefer an exact locationUrl match; fall back to a node continuing the target
    // with a delimiter (`#idx`, ` with data set #N`, `::member`) — never with a name character, or `::testPay` would
    // select `::testPayment`.
    private fun findByLocationUrl(root: SMTestProxy, url: String): SMTestProxy? {
        var prefixMatch: SMTestProxy? = null
        var result: SMTestProxy? = null
        forEachDescendant(root) { proxy ->
            val loc = proxy.locationUrl
            if (loc == url) result = result ?: proxy
            else if (prefixMatch == null && loc != null && loc.length > url.length &&
                loc.startsWith(url) && loc[url.length] in ":# "
            ) prefixMatch = proxy
        }
        return result ?: prefixMatch
    }
}
