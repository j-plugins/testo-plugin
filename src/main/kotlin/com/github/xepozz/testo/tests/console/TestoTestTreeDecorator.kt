package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Puts Testo's own status icons, the run's verdict and per-node descriptions on the results tree.
 *
 * The platform picks icons in `TestsPresentationUtil.getIcon` (`private static`, limited to passed / failed /
 * ignored / running), and its hook for supplying a tree of one's own, `SMTRunnerTestTreeViewProvider`, is
 * `@ApiStatus.Internal` — which `verifyPlugin` fails on. So the renderer the console already installed is wrapped
 * through plain `JTree.getCellRenderer` / `setCellRenderer` instead. Safe: `attachToModel` is its only installer and
 * runs at form construction, and nothing in the test-framework packages reads the renderer back.
 */
object TestoTestTreeDecorator {
    private val LOG = logger<TestoTestTreeDecorator>()

    /**
     * @param describe a node's description, by the key the converter filed it under.
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

        // JTree.getToolTipText asks the renderer for the tooltip, but only for a registered component — and the
        // platform's tree has no tooltip anywhere, so it never registers itself.
        ToolTipManager.sharedInstance().registerComponent(tree)
    }
}

/**
 * The platform renderer with its icon and tooltip overwritten — wrapped, not subclassed.
 *
 * `TestTreeRenderer` also paints the duration text, carries the accessible status and routes the root through any
 * `SMRootTestProxyFormatter`; subclassing would mean re-supplying all of it. The component handed back here is the
 * platform's own, with two fields set.
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
            val icon = testoNodeIcon(
                status = if (isRoot) null else statuses.statusOf(proxy),
                isRoot = isRoot,
                verdict = if (isRoot) verdict() else null,
            )
            when {
                icon != null -> component.icon = icon
                proxy.isInProgress -> zoomProofSpinner(component.icon)?.let { component.icon = it }
            }
        }
        // Assigned even when absent: one component is reused for every node, so a stale tooltip would follow along.
        if (component is JComponent) {
            // By location, not by name: two data sets of different tests share a name (`Dataset #0:0 [0]`).
            val tip = proxy?.let { describe(it.locationUrl ?: it.name) }?.takeIf { it.isNotBlank() }
            if (component.toolTipText != tip) component.toolTipText = tip
        }
        return component
    }

    /** What `SMTRunnerTestTreeView.getTestProxyFor` does, against the public [NodeDescriptor] rather than its own. */
    private fun testProxyOf(value: Any): SMTestProxy? {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        return (userObject as? NodeDescriptor<*>)?.element as? SMTestProxy
    }

    /**
     * A frame-by-frame spinner for a running node, or `null` to keep the platform's own.
     *
     * `SpinningProgressIcon` caches its rasterized frames under the icon's colour alone, so a zoom never rebuilds
     * them and a 16-pixel spinner is stranded among 32-pixel icons. Ordinary SVG icons are rendered at the size in
     * force when painted; used only where the two sizes disagree, since the platform's is smoother.
     *
     * Workaround for IJPL-252440 (JetBrains/intellij-community#3605); drop it once that ships in the oldest platform
     * this plugin builds against.
     */
    private fun zoomProofSpinner(current: Icon?): Icon? {
        if (current == null || current.iconHeight == JBUI.scale(SPINNER_SIZE)) return null
        return spinner
    }

    // One per tree: AnimatedIcon remembers which components it has asked to repaint.
    private val spinner = AnimatedIcon(
        AnimatedIcon.Default.DELAY,
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4,
        AllIcons.Process.Step_5,
        AllIcons.Process.Step_6,
        AllIcons.Process.Step_7,
        AllIcons.Process.Step_8,
    )
}

/** The side a test-tree status icon is drawn at, before the IDE's scale. */
private const val SPINNER_SIZE = 16

/**
 * The icon a node should carry, or `null` to keep the platform's — which is what a still-running node gets.
 *
 * Group nodes get one too, off the verdict `testSuiteFinished` rolls up; the root stands for the run itself and
 * wears the summary's own verdict rather than one derived a second time.
 */
fun testoNodeIcon(status: TestoTestStatus?, isRoot: Boolean, verdict: Icon?): Icon? =
    if (isRoot) verdict else status?.icon
