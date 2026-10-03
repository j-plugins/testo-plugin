package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.execution.RunManagerEx
import com.intellij.execution.executors.DefaultRunExecutor
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
        val configurationFactory = TestoPhp.getInstance().configurationFactory()

        val runConfiguration = configurationFactory.createTemplateConfiguration(project)

        val configuration = runManager.createConfiguration(runConfiguration, configurationFactory)

        runManager.setTemporaryConfiguration(configuration)
        ExecutionUtil.runConfiguration(configuration, DefaultRunExecutor.getRunExecutorInstance())
    }
}