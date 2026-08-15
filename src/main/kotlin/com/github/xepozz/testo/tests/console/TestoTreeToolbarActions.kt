package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Expand/Collapse for whichever tree the toolbar belongs to — the test results tree and the Coverage view both.
 *
 * The platform puts its own pair inside the test toolbar's overflow ("burger") group, which is two clicks away from a
 * thing used constantly; these sit on the visible row. They are added through
 * [com.github.xepozz.testo.tests.TestoConsoleProperties.createImportActions] (the toolbar's one open seam) and through
 * `CoverageViewExtension.createExtraToolbarActions()`.
 *
 * Neither action can hold its tree — both toolbars are built before the view is — so it is found at click time: up the
 * component chain from the button, searching each ancestor's subtree. The first hit is the tree the toolbar sits above.
 */
internal fun findToolbarTree(e: AnActionEvent): JTree? {
    var ancestor: Container? = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? Container
    while (ancestor != null) {
        (ancestor as? JComponent)?.let { UIUtil.findComponentOfType(it, JTree::class.java) }?.let { return it }
        ancestor = ancestor.parent
    }
    return null
}

/** Expands the selected subtrees, or everything when nothing is selected. */
internal class TestoTreeExpandAction : AnAction(
    TestoBundle.message("testo.tree.expand"),
    null,
    AllIcons.Actions.Expandall,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val tree = findToolbarTree(e) ?: return
        val selection = tree.selectionPaths
        if (selection.isNullOrEmpty()) {
            TreeUtil.expandAll(tree)
        } else {
            selection.forEach { expandSubtree(tree, it) }
        }
    }

    // Breadth-first with a cap: the model builds children on demand, and a runaway expand must not freeze the EDT.
    private fun expandSubtree(tree: JTree, root: TreePath) {
        val queue = ArrayDeque<TreePath>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited < NODE_CAP) {
            val path = queue.removeFirst()
            visited++
            val node = path.lastPathComponent
            val count = tree.model.getChildCount(node)
            if (count == 0) continue
            tree.expandPath(path)
            for (i in 0 until count) {
                queue += path.pathByAddingChild(tree.model.getChild(node, i))
            }
        }
    }

    private companion object {
        private const val NODE_CAP = 10_000
    }
}

/** Collapses the selected subtrees, or everything when nothing is selected. */
internal class TestoTreeCollapseAction : AnAction(
    TestoBundle.message("testo.tree.collapse"),
    null,
    AllIcons.Actions.Collapseall,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val tree = findToolbarTree(e) ?: return
        val selection = tree.selectionPaths
        if (selection.isNullOrEmpty()) {
            TreeUtil.collapseAll(tree, 1)
        } else {
            selection.forEach { tree.collapsePath(it) }
        }
    }
}
