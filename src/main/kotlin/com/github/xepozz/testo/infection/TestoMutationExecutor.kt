package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/** *Run with Mutation*: a Coverage run that goes on to mutation testing once its tests pass. */
class TestoMutationExecutor : Executor() {
    override fun getId(): String = ID
    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = TestoIcons.INFECTION
    override fun getIcon(): Icon = TestoIcons.INFECTION
    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(TestoIcons.INFECTION)
    override fun getDescription(): String = TestoBundle.message("infection.executor.description")
    override fun getActionName(): String = TestoBundle.message("infection.executor.action")
    override fun getStartActionText(): String = TestoBundle.message("infection.executor.start")
    override fun getStartActionText(configurationName: String): String =
        TestoBundle.message("infection.executor.start.named", shortenNameIfNeeded(configurationName))
    override fun getContextActionId(): String = "RunTestoMutation"
    override fun getHelpId(): String? = null

    companion object {
        const val ID = "TestoMutation"
    }
}

/**
 * Runs the configuration as the Coverage executor does, with the coverage-xml and JUnit reports Infection reads forced
 * on, and starts Infection over them once the run is archived.
 */
class TestoMutationProgramRunner : TestoCoverageProgramRunner() {
    override fun getRunnerId(): String = "TestoMutationRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == TestoMutationExecutor.ID && profile is TestoRunConfiguration

    override fun prepare(configuration: TestoRunConfiguration): TestoRunConfiguration =
        (configuration.clone() as TestoRunConfiguration).apply {
            testoSettings.runnerSettings.coverageXml = true
            testoSettings.runnerSettings.logJunit = true
        }

    override fun started(env: ExecutionEnvironment, properties: TestoConsoleProperties) {
        val configuration = env.runProfile as? TestoRunConfiguration ?: return
        val options = env.runnerAndConfigurationSettings?.configuration as? TestoRunConfiguration ?: configuration
        properties.afterArchive = { runDir, manifest ->
            TestoMutationService.getInstance(properties.project).startAfterRun(configuration, runDir, manifest, options)
        }
    }
}
