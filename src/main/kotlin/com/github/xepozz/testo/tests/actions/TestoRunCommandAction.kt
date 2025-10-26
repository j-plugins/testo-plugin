package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationProducer
import com.intellij.execution.Executor
import com.intellij.execution.RunManagerEx
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class TestoRunCommandAction(val commandName: String) : AnAction() {
    init {
        templatePresentation.setText(TestoBundle.message("action.run.target.text", commandName), false)
        templatePresentation.description = TestoBundle.message("action.run.target.description", commandName)
        templatePresentation.icon = TestoIcons.TESTO
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val runManager = RunManagerEx.getInstanceEx(project)
        val producer = TestoRunConfigurationProducer()
        val configurationFactory = producer.configurationFactory

        val runConfiguration = TestoRunConfiguration(
            project,
            configurationFactory,
//            TestoBundle.message("action.run.target.command", commandName),
        )
//            .apply { settings.commandName = commandName }

        val configuration = runManager.createConfiguration(runConfiguration, configurationFactory)

        runManager.setTemporaryConfiguration(configuration)
        ExecutionUtil.runConfiguration(configuration, Executor.EXECUTOR_EXTENSION_NAME.extensionList.first())
    }
}