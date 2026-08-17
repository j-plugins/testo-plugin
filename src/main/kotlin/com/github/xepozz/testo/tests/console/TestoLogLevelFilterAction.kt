package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import javax.swing.JComponent

/**
 * Labeled dropdown that picks the *minimum* log level the channel consoles show. The button reads `info +` / `debug +`
 * (the chosen level, and everything above it); the popup lists all eight PSR levels as a radio group. Picking one
 * rebuilds the tabs via [LogLevelFilter.fireChange] — channels left empty by the filter disappear, and lowering the
 * minimum brings them back.
 *
 * Right-aligned on the channel tabs row (installed by [TestoChannelsUi] as the tabs' entry-point action group).
 */
class TestoLogLevelFilterAction(
    private val filter: LogLevelFilter,
) : ComboBoxAction(), DumbAware {
    init {
        templatePresentation.description = TestoBundle.message("testo.console.loglevel.filter.title")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.text = filter.label()
    }

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        LogLevelFilter.LEVELS.forEach { group.add(LevelItem(it)) }
        return group
    }

    private inner class LevelItem(private val level: String) : ToggleAction(humanize(level)), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = filter.minLevel == level
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            filter.setMinLevel(level)
            filter.fireChange()
        }
    }

    private fun humanize(level: String): String =
        level.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
