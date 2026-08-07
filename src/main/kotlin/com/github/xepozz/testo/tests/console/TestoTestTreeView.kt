package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView
import java.awt.event.MouseEvent
import javax.swing.ToolTipManager

/**
 * The results tree, with the description Testo attached to a node shown on hover.
 *
 * A test's `metainfo` (its PHPDoc summary) has nowhere else to go: the platform does put it on `SMTestProxy.metainfo`,
 * but [TestoChannelHistory] overwrites that field with the encoded channel output — it is the only per-test datum the
 * history XML round-trips — so the description is read back out of [ChannelOutputStore], where the converter also
 * files it.
 *
 * Installed through `SMTRunnerTestTreeViewProvider`, which the results form consults on the console properties; the
 * platform's own tree has no tooltip at all, so registering with the [ToolTipManager] is on us.
 */
class TestoTestTreeView(private val describe: (String) -> String?) : SMTRunnerTestTreeView() {
    init {
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val path = getPathForLocation(event.x, event.y) ?: return null
        val proxy = getSelectedTest(path) ?: return null
        return describe(proxy.name)?.takeIf { it.isNotBlank() }
    }
}
