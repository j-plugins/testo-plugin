package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.text.DateFormatUtil

// The console is built by the PHP test framework, so processStarted is the first point we can reach it.
class TestoConsoleAugmenter(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = findDescriptor(executorId, handler) ?: return@invokeLater
            val console = descriptor.executionConsole as? SMTRunnerConsoleView ?: return@invokeLater
            val props = console.properties as? TestoConsoleProperties ?: return@invokeLater
            captureHeader(props, handler)
            TestoChannelsUi.install(console, props.channelStore, props.levelFilter, project, console)
        }
    }

    // Stored on the channel store rather than printed: SM rewrites the platform console per test selection,
    // so the channel UI renders this as the first line of the "All" tab instead.
    private fun captureHeader(props: TestoConsoleProperties, handler: ProcessHandler) {
        val commandLine = (handler as? OSProcessHandler)?.commandLine ?: return
        val startedAt = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
        props.channelStore.setHeader(
            listOf(ChannelOutputStore.Chunk("$commandLine\nTesting started at $startedAt\n\n", null))
        )
    }

    private fun findDescriptor(executorId: String, handler: ProcessHandler): RunContentDescriptor? {
        val manager = RunContentManager.getInstance(project)
        ExecutorRegistry.getInstance().getExecutorById(executorId)?.let { executor ->
            manager.findContentDescriptor(executor, handler)?.let { return it }
        }
        return manager.allDescriptors.firstOrNull { it.processHandler === handler }
    }
}
