package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.FakeRerunAction
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon

// Unwrap the "Rerun Failed Tests" WrappingRunConfiguration to the real TestoRunConfiguration: keeps our buttons
// visible and gives executor runners (notably coverage) a profile they accept, with the failed-subset filters intact.
internal fun ExecutionEnvironment.testoRunProfile(): RunProfile? =
    when (val profile = runProfile) {
        is TestoRunConfiguration -> profile
        is WrappingRunConfiguration<*> -> profile.peer as? TestoRunConfiguration
        else -> null
    }

internal fun ExecutionEnvironment.isTestoRunTab(): Boolean = testoRunProfile() != null

open class TestoRerunWithExecutorAction(
    text: String,
    icon: Icon,
    private val executorId: String,
    private val hideWhenCurrent: Boolean = false,
) : AnAction(text, null, icon), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
        val target = environment?.testoRunProfile()
        val runnable = target != null &&
            ExecutorRegistry.getInstance().getExecutorById(executorId) != null &&
            ProgramRunner.getRunner(executorId, target) != null
        if (environment == null || !runnable) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = when (TestoRerunStyleSettings.style) {
            // Hide whichever button duplicates the executor the platform "Rerun" already covers.
            TestoRerunStyle.MIRROR_AWARE -> hideWhenCurrent && environment.executor.id != executorId
            TestoRerunStyle.SPLIT_BUTTON -> !hideWhenCurrent
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT) ?: return
        val target = environment.testoRunProfile() ?: return
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return
        // Build the env from RunnerAndConfigurationSettings, the way the platform executor action does, so the
        // executor's own RunnerSettings are attached (e.g. CoverageRunnerData — without it the Coverage tool window
        // never opens).
        val settings = settingsFor(environment, target) ?: return
        val relaunch = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
            ?.dataContext(e.dataContext)
            ?.build()
            ?: return
        ExecutionManager.getInstance(relaunch.project).restartRunProfile(relaunch)
    }

    // Reuse the tab's saved settings when they describe this exact config; for the "rerun failed" clone (which lives
    // outside RunManager) wrap it in throwaway settings so the executor's RunnerSettings are still created.
    private fun settingsFor(environment: ExecutionEnvironment, target: RunProfile): RunnerAndConfigurationSettings? {
        environment.runnerAndConfigurationSettings
            ?.takeIf { it.configuration === target }
            ?.let { return it }
        val configuration = target as? RunConfiguration ?: return null
        val factory = configuration.factory ?: return null
        return RunManager.getInstance(configuration.project).createConfiguration(configuration, factory)
    }
}

class TestoRerunAction : TestoRerunWithExecutorAction(
    TestoBundle.message("action.testo.rerun.text"),
    AllIcons.Actions.Execute,
    DefaultRunExecutor.EXECUTOR_ID,
    hideWhenCurrent = true,
)

class TestoRerunWithDebuggerAction : TestoRerunWithExecutorAction(
    TestoBundle.message("action.testo.rerunWithDebugger.text"),
    AllIcons.Actions.StartDebugger,
    DefaultDebugExecutor.EXECUTOR_ID,
    hideWhenCurrent = true,
)

class TestoRerunWithCoverageAction : TestoRerunWithExecutorAction(
    TestoBundle.message("action.testo.rerunWithCoverage.text"),
    AllIcons.General.RunWithCoverage,
    "Coverage",
    hideWhenCurrent = true,
)

// Subclasses the platform rerun so the main button inherits its per-executor icon and "rerun current environment" restart.
class TestoRerunCurrentAction : FakeRerunAction()

class TestoRerunSplitButtonAction : SplitButtonAction(buildExecutorGroup()) {
    private val mainAction = TestoRerunCurrentAction()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun useDynamicSplitButton(): Boolean = false

    override fun getMainAction(e: AnActionEvent): AnAction = mainAction

    override fun update(e: AnActionEvent) {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
        if (environment?.isTestoRunTab() != true || TestoRerunStyleSettings.style != TestoRerunStyle.SPLIT_BUTTON) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        super.update(e)
    }

    companion object {
        private fun buildExecutorGroup(): DefaultActionGroup = DefaultActionGroup(
            TestoRerunWithExecutorAction(
                TestoBundle.message("action.testo.rerun.text"),
                AllIcons.Actions.Execute,
                DefaultRunExecutor.EXECUTOR_ID,
            ),
            TestoRerunWithExecutorAction(
                TestoBundle.message("action.testo.rerunWithDebugger.text"),
                AllIcons.Actions.StartDebugger,
                DefaultDebugExecutor.EXECUTOR_ID,
            ),
            TestoRerunWithExecutorAction(
                TestoBundle.message("action.testo.rerunWithCoverage.text"),
                AllIcons.General.RunWithCoverage,
                "Coverage",
            ),
        )
    }
}

// Overrides the platform "Rerun": in SPLIT_BUTTON mode it steps aside for Testo tabs (the split button takes over);
// otherwise it behaves exactly like the platform action.
class TestoAwareRerunAction : FakeRerunAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
        if (environment?.isTestoRunTab() == true && TestoRerunStyleSettings.style == TestoRerunStyle.SPLIT_BUTTON) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
