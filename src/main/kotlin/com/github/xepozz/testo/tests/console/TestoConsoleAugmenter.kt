package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Installs the in-console channel tabs ([TestoChannelsUi]) for a freshly started Testo run.
 *
 * The console (an `SMTRunnerConsoleView`) is constructed by the PHP test framework, not by us, so the only point
 * at which we can reach the live console is after the process starts. Registered as a project-level
 * [ExecutionListener] in plugin.xml.
 */
class TestoConsoleAugmenter(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = findDescriptor(executorId, handler) ?: return@invokeLater
            val console = descriptor.executionConsole as? SMTRunnerConsoleView ?: return@invokeLater
            val props = console.properties as? TestoConsoleProperties ?: return@invokeLater
            TestoChannelsUi.install(console, props.channelStore, project, console)
        }
    }

    private fun findDescriptor(executorId: String, handler: ProcessHandler): RunContentDescriptor? {
        val manager = RunContentManager.getInstance(project)
        ExecutorRegistry.getInstance().getExecutorById(executorId)?.let { executor ->
            manager.findContentDescriptor(executor, handler)?.let { return it }
        }
        return manager.allDescriptors.firstOrNull { it.processHandler === handler }
    }
}
