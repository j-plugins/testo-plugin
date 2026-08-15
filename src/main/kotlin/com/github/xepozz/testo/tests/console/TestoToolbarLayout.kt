package com.github.xepozz.testo.tests.console

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.UIUtil
import java.awt.Container
import javax.swing.JComponent

/**
 * Rearranges the test toolbar the platform built: the sort popup moves into the overflow ("burger") group along with
 * the separator that used to precede it, and the platform's own expand/collapse leave that group — ours sit on the
 * visible row now ([com.github.xepozz.testo.tests.TestoConsoleProperties.createImportActions]).
 *
 * There is no seam for this. `ToolbarPanel` creates both groups inline, with no action ids, no extension point and no
 * `CustomActionsSchema` entry, and hands a snapshot of the visible one to `RunTab` — so the only handle on the group
 * the user actually sees is from *inside* it. Hence this: an invisible action that rides the same toolbar and, the
 * first few times it is asked to update, walks the toolbars around it looking for the one the test tree owns.
 *
 * Deliberately best-effort. The toolbar is found by its place, its contents matched structurally — a popup group
 * holding `SortByDurationAction`, a group whose class is `MoreActionGroup`, actions wearing the expand/collapse icons
 * — so this survives translation and renaming, and does nothing at all (rather than breaking the toolbar) once the
 * platform's layout changes shape.
 */
internal class TestoToolbarLayoutAction : AnAction(), DumbAware {

    private var done = false
    private var attempts = 0

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
        // The toolbar updates twice a second; this walks components, so it runs only until it lands (the toolbar may
        // not be assembled on the first pass) and then gives up for good.
        if (done || attempts >= MAX_ATTEMPTS) return
        attempts++
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) as? Container ?: return
        var ancestor: Container? = component
        var hops = 0
        while (ancestor != null && hops < ANCESTOR_LIMIT) {
            (ancestor as? JComponent)?.let { root ->
                UIUtil.uiTraverser(root).traverse().forEach { candidate ->
                    if (candidate is ActionToolbar && candidate.place == TEST_TREE_TOOLBAR_PLACE) {
                        if (rearrange(candidate.actionGroup)) done = true
                    }
                }
            }
            ancestor = ancestor.parent
            hops++
        }
    }

    override fun actionPerformed(e: AnActionEvent) = Unit

    /** True once this toolbar's group has been rearranged — i.e. it held the platform's groups and now does not. */
    private fun rearrange(group: ActionGroup): Boolean = runCatching {
        val root = group as? DefaultActionGroup ?: return false
        val more = root.getChildActionsOrStubs()
            .filterIsInstance<DefaultActionGroup>()
            .firstOrNull { it.javaClass.simpleName == MORE_GROUP }
            ?: return false
        val moved = moveSortIntoMoreGroup(root, more)
        dropExpandCollapse(more)
        moved
    }.getOrDefault(false)

    /**
     * Moves the sort popup into [more], taking the separator in front of it along: that separator was there to part
     * the two toggles from the sort button, and with the button gone it would only fence off our own actions.
     */
    private fun moveSortIntoMoreGroup(root: DefaultActionGroup, more: DefaultActionGroup): Boolean {
        val children = root.getChildActionsOrStubs()
        val index = children.indexOfFirst { it is ActionGroup && it.isPopup && holds(it, SORT_MARKER) }
        if (index < 0) return false
        children.getOrNull(index - 1)?.takeIf { it is Separator }?.let { root.remove(it) }
        val sort = children[index]
        root.remove(sort)
        more.add(sort, Constraints.FIRST)
        return true
    }

    private fun dropExpandCollapse(more: DefaultActionGroup) {
        more.getChildActionsOrStubs()
            .filter { it.templatePresentation.icon.let { icon -> icon === EXPAND_ICON || icon === COLLAPSE_ICON } }
            .forEach { more.remove(it) }
    }

    // Only the groups we can read without asking: `ActionGroup.getChildren` is @OverrideOnly, so calling it is out —
    // and every group on this path is a DefaultActionGroup anyway (`RunTab.ToolbarActionGroup` copies its delegate's
    // children into itself). Stubs are fine: everything matched here is a real instance the toolbar was built with.
    private fun holds(group: ActionGroup, markerClassName: String): Boolean =
        (group as? DefaultActionGroup)?.getChildActionsOrStubs()
            ?.any { it.javaClass.simpleName == markerClassName } == true

    private companion object {
        // The place ToolbarPanel creates its toolbar under. A literal there too — the platform exposes no constant.
        private const val TEST_TREE_TOOLBAR_PLACE = "TestTreeViewToolbar"
        private const val MORE_GROUP = "MoreActionGroup"
        private const val SORT_MARKER = "SortByDurationAction"
        private const val ANCESTOR_LIMIT = 12
        private const val MAX_ATTEMPTS = 20

        private val EXPAND_ICON = AllIcons.Actions.Expandall
        private val COLLAPSE_ICON = AllIcons.Actions.Collapseall
    }
}
