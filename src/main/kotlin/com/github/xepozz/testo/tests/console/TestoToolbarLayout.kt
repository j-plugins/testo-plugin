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
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.UIUtil
import java.awt.Container
import javax.swing.JComponent

/**
 * Rearranges the test toolbar the platform built: the sort popup moves into the overflow ("burger") group, and the
 * platform's own expand/collapse leave it — ours sit on the visible row now
 * ([com.github.xepozz.testo.tests.TestoConsoleProperties.createImportActions]).
 *
 * There is no seam for this. `ToolbarPanel` creates both groups inline, with no action ids, no extension point and no
 * `CustomActionsSchema` entry, and hands a snapshot of the visible one to `RunTab` — so the only handle on the group
 * the user actually sees is from *inside* it. Hence this: an invisible action that rides the same toolbar and, the
 * first few times it is asked to update, walks the toolbars around it and moves the two things.
 *
 * Deliberately best-effort, and matched structurally rather than by name — a popup group holding
 * `SortByDurationAction`, a group whose class is `MoreActionGroup`, actions wearing the expand/collapse icons — so it
 * survives translation and renaming, and does nothing at all (rather than breaking the toolbar) once the platform's
 * layout changes shape.
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
                    if (candidate is ActionToolbar && rearrange(candidate)) done = true
                }
            }
            ancestor = ancestor.parent
            hops++
        }
    }

    override fun actionPerformed(e: AnActionEvent) = Unit

    /** True once this toolbar has been rearranged — i.e. it was the one holding the platform's groups. */
    private fun rearrange(toolbar: ActionToolbar): Boolean = runCatching {
        val group = actionGroupOf(toolbar) ?: return false
        val more = find(group, 0) { it.javaClass.simpleName == MORE_GROUP } as? DefaultActionGroup ?: return false
        moveSortIntoMoreGroup(group, more)
        dropExpandCollapse(more)
        true
    }.getOrDefault(false)

    /** The toolbar's group, read by name: the accessor lives on the implementation, which is not ours to reference. */
    private fun actionGroupOf(toolbar: ActionToolbar): ActionGroup? =
        runCatching { toolbar.javaClass.getMethod("getActionGroup").invoke(toolbar) as? ActionGroup }.getOrNull()

    private fun moveSortIntoMoreGroup(root: ActionGroup, more: DefaultActionGroup) {
        val sort = find(root, 0) { it is ActionGroup && it.isPopup && holds(it, SORT_MARKER) } ?: return
        val owner = parentOf(root, sort, 0) as? DefaultActionGroup ?: return
        if (owner === more) return
        owner.remove(sort)
        more.add(sort, Constraints.FIRST)
    }

    private fun dropExpandCollapse(more: DefaultActionGroup) {
        more.getChildActionsOrStubs()
            .filter { it.templatePresentation.icon.let { icon -> icon === EXPAND_ICON || icon === COLLAPSE_ICON } }
            .forEach { more.remove(it) }
    }

    private fun holds(group: ActionGroup, markerClassName: String): Boolean =
        children(group).any { it.javaClass.simpleName == markerClassName }

    private fun find(group: ActionGroup, depth: Int, predicate: (AnAction) -> Boolean): AnAction? {
        if (depth > DEPTH_LIMIT) return null
        for (child in children(group)) {
            if (predicate(child)) return child
            if (child is ActionGroup) find(child, depth + 1, predicate)?.let { return it }
        }
        return null
    }

    private fun parentOf(group: ActionGroup, child: AnAction, depth: Int): ActionGroup? {
        if (depth > DEPTH_LIMIT) return null
        for (candidate in children(group)) {
            if (candidate === child) return group
            if (candidate is ActionGroup) parentOf(candidate, child, depth + 1)?.let { return it }
        }
        return null
    }

    // Only the groups we can read without asking: `ActionGroup.getChildren` is @OverrideOnly, so calling it is out —
    // and every group on this path is a DefaultActionGroup anyway (`RunTab.ToolbarActionGroup` copies its delegate's
    // children into itself). Stubs are fine: everything matched here is a real instance the toolbar was built with.
    private fun children(group: ActionGroup): Array<AnAction> =
        (group as? DefaultActionGroup)?.getChildActionsOrStubs() ?: AnAction.EMPTY_ARRAY

    private companion object {
        private const val MORE_GROUP = "MoreActionGroup"
        private const val SORT_MARKER = "SortByDurationAction"
        private const val DEPTH_LIMIT = 3
        private const val ANCESTOR_LIMIT = 12
        private const val MAX_ATTEMPTS = 20

        private val EXPAND_ICON = AllIcons.Actions.Expandall
        private val COLLAPSE_ICON = AllIcons.Actions.Collapseall
    }
}
