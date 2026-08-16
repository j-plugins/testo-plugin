package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * Dropdown that toggles which log levels the channel consoles show. "All" flips every seen level on/off at once;
 * each level below has its own checkbox. The menu lists exactly the levels encountered in the current run (ordered by
 * PSR severity), so it grows as new levels arrive. Toggling rebuilds the tabs via [LogLevelFilter.fireChange] — channel
 * tabs left empty by the filter disappear, and re-enabling a level brings them back.
 *
 * Lives on the console's own vertical toolbar, beside the output it filters (installed by [TestoChannelsUi]).
 */
class TestoLogLevelFilterAction(
    private val filter: LogLevelFilter,
) : ActionGroup(), DumbAware {
    init {
        isPopup = true
        templatePresentation.icon = AllIcons.General.Filter
        templatePresentation.text = TestoBundle.message("testo.console.loglevel.filter.title")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val levels = filter.seenLevels().sortedWith(LEVEL_ORDER)
        val children = mutableListOf<AnAction>(AllToggle())
        if (levels.isNotEmpty()) {
            children += Separator.getInstance()
            levels.mapTo(children) { LevelToggle(it) }
        }
        return children.toTypedArray()
    }

    private inner class AllToggle : ToggleAction(TestoBundle.message("testo.console.loglevel.filter.all")), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = filter.isAllEnabled()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) filter.enableAll() else filter.disableAll()
            filter.fireChange()
        }
    }

    private inner class LevelToggle(private val level: String) : ToggleAction(humanize(level)), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = !filter.isHidden(level)
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            filter.setHidden(level, !state)
            filter.fireChange()
        }
    }

    companion object {
        // PSR-3 severities, most severe first; levels outside this list sort after, alphabetically.
        private val PSR_ORDER = listOf(
            "emergency", "alert", "critical", "error", "warning", "notice", "info", "debug",
        )

        private val LEVEL_ORDER = Comparator<String> { a, b ->
            val ia = PSR_ORDER.indexOf(a.lowercase())
            val ib = PSR_ORDER.indexOf(b.lowercase())
            when {
                ia >= 0 && ib >= 0 -> ia - ib
                ia >= 0 -> -1
                ib >= 0 -> 1
                else -> a.compareTo(b)
            }
        }

        private fun humanize(level: String): String =
            level.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
