package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.launch.TestoConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/** *Run with Mutation*: a Coverage run that goes on to mutation testing once its tests pass. */
class TestoMutationExecutor : Executor() {
    override fun getId(): String = ID
    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = TestoIcons.MUTATION
    override fun getIcon(): Icon = TestoIcons.MUTATION_RUN
    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(TestoIcons.MUTATION_RUN)
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

/** Installs the hook before the Testo process finishes; every later file operation uses this run's snapshot. */
internal fun mutateAfterArchive(env: ExecutionEnvironment, properties: TestoConsoleProperties) {
    val configuration = properties.configuration as? TestoConfiguration ?: return
    val options = env.runnerAndConfigurationSettings?.configuration as? TestoConfiguration ?: configuration
    val snapshot = checkNotNull(properties.toolEnvironment) { "The Testo run has no captured tool environment" }
    properties.afterArchive = { dir, manifest ->
        TestoMutationService.getInstance(properties.project).startAfterRun(configuration, dir, manifest, options, snapshot)
    }
}
