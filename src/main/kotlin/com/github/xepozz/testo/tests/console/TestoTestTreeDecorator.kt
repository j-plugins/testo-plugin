package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Teaches the results tree what Testo reported about a node: one icon per case of `Testo\Core\Value\Status` on the
 * nodes, the run's own verdict on the root, and the node's description on hover.
 *
 * The platform picks the icon in `TestsPresentationUtil.getIcon`, which is `private static` and limited to what
 * `SMTestProxy` state can express — passed, failed, ignored, running. Everything finer Testo sends (risky, flaky,
 * cancelled, aborted, error) has no way in, so the toolbar summary counts statuses the tree cannot show.
 *
 * The renderer that draws all this is installed once, in `TestTreeView.attachToModel`, and the only hook the platform
 * offers for supplying a different one — `SMTRunnerTestTreeViewProvider` — is `@ApiStatus.Internal`. So the tree is
 * decorated after the fact instead, through plain Swing: `getCellRenderer` / `setCellRenderer` on the `JTree` the
 * console already built. Nothing in `intellij.platform.smRunner` or `intellij.platform.testRunner` reads the renderer
 * back (checked across all 216 classes of the package), and `attachToModel` runs inside
 * `SMTestRunnerResultsForm.createTestTreeView()` — long before the console reaches us — so no one overwrites this.
 */
object TestoTestTreeDecorator {
    private val LOG = logger<TestoTestTreeDecorator>()

    /**
     * @param describe the description of a node, by node name — see [ChannelOutputStore.description]. Not read off
     *        `SMTestProxy.metainfo`, where the platform does put it: [TestoChannelHistory] overwrites that field with
     *        the encoded channel output, the only per-test datum the history XML round-trips.
     */
    fun install(
        console: SMTRunnerConsoleView,
        statuses: TestoStatusStore,
        verdict: () -> Icon?,
        describe: (String) -> String?,
    ) {
        val root = console.component as? JComponent ?: return
        val tree = UIUtil.findComponentOfType(root, TestTreeView::class.java)
        if (tree == null) {
            LOG.warn("No test tree under the Testo console: its nodes keep the platform icons and show no description.")
            return
        }

        val platform = tree.cellRenderer
        if (platform == null || platform is TestoNodeRenderer) return
        tree.cellRenderer = TestoNodeRenderer(platform, statuses, verdict, describe)

        // JTree.getToolTipText(MouseEvent) asks the renderer for the hovered node's tooltip, but only once the tree is
        // registered — and the platform's own has no tooltip anywhere, so it never registers itself.
        ToolTipManager.sharedInstance().registerComponent(tree)
    }
}

/**
 * The platform renderer with two of its properties overwritten — wrapped, not subclassed.
 *
 * `TestTreeRenderer` does more than pick an icon: it paints the duration text beside the node out of its own
 * `paintComponent`/`getPreferredSize`, carries the accessible status the view supplies, and routes the root through
 * any registered `SMRootTestProxyFormatter`. Subclassing would mean re-supplying all of that (and it is
 * `@ApiStatus.Internal` besides). Wrapping keeps every bit of it: the component handed back *is* the platform's own,
 * with two fields of it set.
 */
private class TestoNodeRenderer(
    private val platform: TreeCellRenderer,
    private val statuses: TestoStatusStore,
    private val verdict: () -> Icon?,
    private val describe: (String) -> String?,
) : TreeCellRenderer {

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component = platform.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val proxy = testProxyOf(value)

        if (component is SimpleColoredComponent && proxy != null) {
            val isRoot = proxy.parent == null
            testoNodeIcon(
                status = if (isRoot) null else statuses.statusOf(proxy),
                isRoot = isRoot,
                verdict = if (isRoot) verdict() else null,
            )?.let { component.icon = it }
        }
        // Always assigned, never only when there is one: a renderer is a single component reused for every node, so a
        // description left behind would go on showing over the nodes that have none.
        if (component is JComponent) {
            val tip = proxy?.name?.let(describe)?.takeIf { it.isNotBlank() }
            if (component.toolTipText != tip) component.toolTipText = tip
        }
        return component
    }

    /**
     * The test a tree node stands for. This is what `SMTRunnerTestTreeView.getTestProxyFor` does, spelled out against
     * the public [NodeDescriptor] rather than the `SMTRunnerNodeDescriptor` it names — the same object either way.
     */
    private fun testProxyOf(value: Any): SMTestProxy? {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        return (userObject as? NodeDescriptor<*>)?.element as? SMTestProxy
    }
}

/**
 * The icon a results-tree node should carry, or `null` to leave the platform's own in place.
 *
 * Group nodes get one too — a case, a DataProvider batch and a suite of the run all close with `testSuiteFinished`
 * carrying their children's outcome rolled up, so they have a Testo verdict of their own to show, and a tree where
 * only the leaves changed would read as two icon families side by side.
 *
 * The root is not a node of the run but the run itself, so it wears the run's verdict — the very icon the toolbar
 * summary's ring turns into, taken from there rather than derived again: a check or a cross, greyed out when the run
 * was stopped before it could reach a verdict of its own. `null` while the run is still going, which leaves the
 * platform's animated icon in place — the same reason a test still running keeps its own.
 */
fun testoNodeIcon(status: TestoTestStatus?, isRoot: Boolean, verdict: Icon?): Icon? =
    if (isRoot) verdict else status?.icon
