package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * Toolbar dropdown that toggles which log levels the channel consoles show. "All" flips every seen level on/off at once;
 * each level below has its own checkbox. The menu lists exactly the levels encountered in the current run (ordered by
 * PSR severity), so it grows as new levels arrive. Toggling rebuilds the tabs via [LogLevelFilter.fireChange] — channel
 * tabs left empty by the filter disappear, and re-enabling a level brings them back.
 *
 * Registered statically on the run-tab toolbar (`RunTab.TopToolbar`), so a single shared instance is shown on every run
 * tab; it resolves the current tab's [LogLevelFilter] from the action context and hides itself on non-Testo tabs.
 */
// explicitFilter is used by the debug runner, whose toolbar context carries no RUN_CONTENT_DESCRIPTOR to resolve from;
// the statically-registered run-tab instance leaves it null and resolves the filter from the action context instead.
class TestoLogLevelFilterAction(
    private val explicitFilter: LogLevelFilter? = null,
) : ActionGroup(), DumbAware {
    init {
        isPopup = true
        templatePresentation.icon = AllIcons.Actions.Show
        templatePresentation.text = TestoBundle.message("testo.console.loglevel.filter.title")
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolveFilter(e) != null
    }

    private fun resolveFilter(e: AnActionEvent?): LogLevelFilter? = explicitFilter ?: resolveContextFilter(e)

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val filter = resolveFilter(e) ?: return AnAction.EMPTY_ARRAY
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
        override fun isSelected(e: AnActionEvent) = resolveFilter(e)?.isAllEnabled() ?: true
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val filter = resolveFilter(e) ?: return
            if (state) filter.enableAll() else filter.disableAll()
            filter.fireChange()
        }
    }

    private inner class LevelToggle(private val level: String) : ToggleAction(humanize(level)), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = resolveFilter(e)?.isHidden(level) == false
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val filter = resolveFilter(e) ?: return
            filter.setHidden(level, !state)
            filter.fireChange()
        }
    }

    companion object {
        // Resolve the current run tab's Testo filter from context; null on any non-Testo run tab (hides the button).
        private fun resolveContextFilter(e: AnActionEvent?): LogLevelFilter? {
            val descriptor = e?.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR) ?: return null
            val console = descriptor.executionConsole as? SMTRunnerConsoleView ?: return null
            return (console.properties as? TestoConsoleProperties)?.levelFilter
        }

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
