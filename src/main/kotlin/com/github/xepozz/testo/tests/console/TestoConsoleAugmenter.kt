package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.history.ImportedTestConsoleProperties
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
            when (val props = console.properties) {
                // Live run: build the channel UI and start stamping per-test channel output onto proxy metainfo.
                is TestoConsoleProperties -> installChannels(project, console, props, handler)
                // Imported history: the platform forces its own console+converter, so our converter never runs; rebuild
                // the channels from the metainfo we stored at export time.
                is ImportedTestConsoleProperties -> TestoChannelHistory.installForImport(project, console)
                else -> {}
            }
        }
    }

    private fun findDescriptor(executorId: String, handler: ProcessHandler): RunContentDescriptor? {
        val manager = RunContentManager.getInstance(project)
        ExecutorRegistry.getInstance().getExecutorById(executorId)?.let { executor ->
            manager.findContentDescriptor(executor, handler)?.let { return it }
        }
        return manager.allDescriptors.firstOrNull { it.processHandler === handler }
    }

    companion object {
        // Single entry point for wiring the channel tabs, shared by the run-path listener above and the debug runner
        // (which installs them directly because its descriptor isn't registered when processStarted fires). The
        // channelsInstalled flag keeps a second caller for the same console from installing twice.
        fun installChannels(
            project: Project,
            console: SMTRunnerConsoleView,
            props: TestoConsoleProperties,
            handler: ProcessHandler,
        ) {
            if (props.channelsInstalled) return
            props.channelsInstalled = true
            captureHeader(props, handler)
            TestoChannelsUi.install(console, props.channelStore, props.levelFilter, project, console)
            // Persist each test's channel output into proxy metainfo so an imported-history run can rebuild the tabs.
            TestoChannelHistory.subscribeMetainfoWriter(project, console, props.channelStore)
        }

        // Stored on the channel store rather than printed: SM rewrites the platform console per test selection,
        // so the channel UI renders this as the first line of the "All" tab instead.
        private fun captureHeader(props: TestoConsoleProperties, handler: ProcessHandler) {
            val commandLine = (handler as? OSProcessHandler)?.commandLine ?: return
            // DateFormatUtil emits a narrow no-break space (U+202F) before AM/PM on modern JDKs, which renders as a
            // tofu box in the channel editor; normalize it (and NBSP) to a plain space.
            val startedAt = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
                .replace(' ', ' ').replace(' ', ' ')
            props.channelStore.setHeader(
                listOf(ChannelOutputStore.Chunk("$commandLine\nTesting started at $startedAt\n\n", null))
            )
        }
    }
}
