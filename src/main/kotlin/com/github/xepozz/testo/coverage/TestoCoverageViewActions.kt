package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.editor.TestoCoverageEditorHighlighter
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Testo additions to the Coverage view toolbar, plugged in through the one public seam the view offers —
 * `CoverageViewExtension.createExtraToolbarActions()` (`@ApiStatus.Experimental`, present on 252 and 262).
 *
 * Expand/Collapse are shared with the test tree's toolbar — see
 * [com.github.xepozz.testo.tests.console.TestoTreeExpandAction].
 */

/** Switches the Testo editor gutter stripes on and off without touching the suite or the view. */
internal class TestoCoverageHighlightToggleAction(private val project: Project) : ToggleAction(
    TestoBundle.message("testo.coverage.view.toggle.highlight"),
    null,
    AllIcons.Actions.Show,
), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        TestoCoverageEditorHighlighter.getInstance(project).highlightingEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) =
        TestoCoverageEditorHighlighter.getInstance(project).setHighlightingEnabled(state)
}

/** Non-clickable chips naming the report formats merged into the shown bundle — one per distinct format. */
internal class TestoCoverageFormatBadgesAction(
    private val bundle: CoverageSuitesBundle,
) : AnAction(), CustomComponentAction, DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        panel.isOpaque = false
        bundle.suites.filterIsInstance<TestoCoverageSuite>()
            .map { it.format.id }
            .distinct()
            .forEach { panel.add(badge(it)) }
        return panel
    }

    private fun badge(text: String): JComponent = JBLabel(text).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.compound(
            RoundedLineBorder(JBColor.border(), JBUI.scale(10)),
            JBUI.Borders.empty(1, 6),
        )
    }
}
