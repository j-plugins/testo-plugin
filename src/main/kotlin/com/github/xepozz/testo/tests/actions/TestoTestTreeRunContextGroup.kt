package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.coverage.TestoCoverageProgramRunner
import com.intellij.execution.ExecutorRegistry
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * The two run-context actions the test tree's popup buries: *Run with Coverage* and *Modify Run Configuration*.
 *
 * The platform hides every executor other than Run and Debug behind the `RunContextGroupMore` submenu — a rule that
 * lives in `ExecutorRegistryImpl` and is switched by a global registry key, so it cannot be relaxed for one popup.
 * This group borrows the very same actions by id and offers them one level up, where a test tree needs them. They stay
 * in the submenu too: the actions are shared instances, and removing them from the platform's group would empty it
 * everywhere else as well.
 */
class TestoTestTreeRunContextGroup : ActionGroup(), DumbAware {

    init {
        isPopup = false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val coverageActionId = ExecutorRegistry.getInstance()
            .getExecutorById(TestoCoverageProgramRunner.EXECUTOR_ID)
            ?.contextActionId
        return listOfNotNull(
            coverageActionId?.let { manager.getAction(it) },
            manager.getAction(MODIFY_RUN_CONFIGURATION),
        ).toTypedArray()
    }

    private companion object {
        // "Modify Run Configuration…" — the platform's own id, as PlatformExecutionActions.xml spells it.
        private const val MODIFY_RUN_CONFIGURATION = "CreateRunConfiguration"
    }
}
