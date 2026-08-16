package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.runs.TestoRunReplayProfile
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
        // A replayed archive: rerun runs the configuration the archived run was started with, restored from its
        // manifest. (An archive that predates that recording restores a bare template — it reruns nothing useful,
        // but nothing destructive either.)
        is TestoRunReplayProfile -> profile.testoConfiguration
        else -> null
    }

internal fun ExecutionEnvironment.isTestoRunTab(): Boolean = testoRunProfile() != null

/**
 * The executor a rerun of this tab should use: the tab's own, except on a replayed archive — that tab is opened by the
 * Run executor whatever it holds, so a rerun there follows the *archived* run instead (a coverage archive reruns with
 * coverage). Null when the environment names no executor we can run.
 */
internal fun ExecutionEnvironment.testoRerunExecutorId(): String? {
    val archived = (runProfile as? TestoRunReplayProfile)?.executorId
        ?.takeIf { ExecutorRegistry.getInstance().getExecutorById(it) != null }
    return archived ?: executor.id
}

internal fun ExecutionEnvironment.isTestoReplay(): Boolean = runProfile is TestoRunReplayProfile

/**
 * Launches [target] under [executorId] the way the platform's own executor action does — through
 * `RunnerAndConfigurationSettings`, so the executor's `RunnerSettings` are attached (e.g. `CoverageRunnerData`, without
 * which the Coverage tool window never opens).
 */
internal fun relaunchTesto(e: AnActionEvent, environment: ExecutionEnvironment, target: RunProfile, executorId: String) {
    val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return
    val settings = settingsFor(environment, target) ?: return
    val relaunch = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
        ?.dataContext(e.dataContext)
        ?.build()
        ?: return
    ExecutionManager.getInstance(relaunch.project).restartRunProfile(relaunch)
}

// Reuse the tab's saved settings when they describe this exact config; for the "rerun failed" clone and a replay's
// restored configuration (neither lives in RunManager) wrap it in throwaway settings so the RunnerSettings are created.
private fun settingsFor(environment: ExecutionEnvironment, target: RunProfile): RunnerAndConfigurationSettings? {
    environment.runnerAndConfigurationSettings
        ?.takeIf { it.configuration === target }
        ?.let { return it }
    val configuration = target as? RunConfiguration ?: return null
    val factory = configuration.factory ?: return null
    return RunManager.getInstance(configuration.project).createConfiguration(configuration, factory)
}

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
        relaunchTesto(e, environment, target, executorId)
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

// The split button's main action: reruns the current tab's environment with its own executor. Mirrors the platform
// "Rerun" (per-executor icon + restart-current) through public API, so it needs no internal FakeRerunAction.
class TestoRerunCurrentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
        e.presentation.isEnabledAndVisible = environment != null
        if (environment != null) e.presentation.icon = rerunIcon(environment)
    }

    override fun actionPerformed(e: AnActionEvent) = rerunCurrent(e)
}

/** The icon of the executor a rerun would use — the archived one on a replayed tab, this tab's otherwise. */
internal fun rerunIcon(environment: ExecutionEnvironment): Icon {
    val executorId = environment.testoRerunExecutorId()
    return executorId?.let { ExecutorRegistry.getInstance().getExecutorById(it)?.icon }
        ?: environment.executor.icon
        ?: AllIcons.Actions.Restart
}

/**
 * Restarts what the tab shows. A replayed archive is restarted as the *run* it holds — replaying the recorded log
 * again would be a no-op the user cannot tell from a rerun that did nothing.
 */
internal fun rerunCurrent(e: AnActionEvent) {
    val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT) ?: return
    val target = environment.testoRunProfile()
    val executorId = environment.testoRerunExecutorId()
    if (environment.isTestoReplay() && target != null && executorId != null) {
        relaunchTesto(e, environment, target, executorId)
        return
    }
    ExecutionManager.getInstance(environment.project).restartRunProfile(environment)
}

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
// otherwise it reruns the current environment, the same restart the platform action performs — done through public
// API (restartRunProfile) so it carries no dependency on the internal FakeRerunAction.
class TestoAwareRerunAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
        if (environment == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // Step aside for the split button on Testo tabs in split-button mode.
        if (environment.isTestoRunTab() && TestoRerunStyleSettings.style == TestoRerunStyle.SPLIT_BUTTON) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = true
        e.presentation.icon = rerunIcon(environment)
    }

    override fun actionPerformed(e: AnActionEvent) = rerunCurrent(e)
}
