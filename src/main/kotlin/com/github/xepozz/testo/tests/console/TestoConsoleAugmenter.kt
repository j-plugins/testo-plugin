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
import com.intellij.util.concurrency.EdtScheduledExecutorService
import com.intellij.util.text.DateFormatUtil

// The console is built by the PHP test framework, so processStarted is the first point we can reach it.
class TestoConsoleAugmenter(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = findDescriptor(executorId, handler) ?: return@invokeLater
            val console = descriptor.executionConsole as? SMTRunnerConsoleView ?: return@invokeLater
            val props = console.properties
            val importProfile = env.runProfile as? TestoImportRunProfile
            when {
                // Live run: build the channel UI and start stamping per-test channel output onto proxy metainfo.
                props is TestoConsoleProperties -> installChannels(project, console, props, handler)
                // Our "Show history" lens import: detected by our own run profile (no dependency on the internal
                // ImportedTestConsoleProperties), carrying the clicked test's url so its node gets pre-selected.
                importProfile != null -> TestoChannelHistory.installForImport(project, console, importProfile.targetUrl)
                // Platform "Import Test Results" (the history clock dropdown) of a Testo run: an imported console with no
                // Testo run profile. Recognized by class name so we keep no compile-time tie to the internal
                // ImportedTestConsoleProperties; rebuild channels from metainfo just the same (no clicked test to select).
                isImportedConsole(props) -> TestoChannelHistory.installForImport(project, console, null)
            }
        }
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = findDescriptor(executorId, handler) ?: return@invokeLater
            val console = descriptor.executionConsole as? SMTRunnerConsoleView ?: return@invokeLater
            if (console.properties !is TestoConsoleProperties) return@invokeLater
            // The run's history XML is written on a background task after the process ends, so nudge the lens a couple
            // of times across the save window. Once the index sees the new file it re-invalidates the lens itself; the
            // first nudge that lands after the save is what makes the just-run test's lens appear (no IDE restart).
            val refresh = Runnable { TestoHistoryIndex.refreshLens(project) }
            EdtScheduledExecutorService.getInstance().schedule(refresh, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            EdtScheduledExecutorService.getInstance().schedule(refresh, 4000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    // True when the console's properties are (a subclass of) the platform's imported-history properties, matched by FQN
    // so this carries no bytecode reference to the @ApiStatus.Internal class.
    private fun isImportedConsole(props: Any?): Boolean {
        var c: Class<*>? = props?.javaClass
        while (c != null) {
            if (c.name == IMPORTED_CONSOLE_PROPERTIES_FQN) return true
            c = c.superclass
        }
        return false
    }

    private fun findDescriptor(executorId: String, handler: ProcessHandler): RunContentDescriptor? {
        val manager = RunContentManager.getInstance(project)
        ExecutorRegistry.getInstance().getExecutorById(executorId)?.let { executor ->
            manager.findContentDescriptor(executor, handler)?.let { return it }
        }
        return manager.allDescriptors.firstOrNull { it.processHandler === handler }
    }

    companion object {
        private const val IMPORTED_CONSOLE_PROPERTIES_FQN =
            "com.intellij.execution.testframework.sm.runner.history.ImportedTestConsoleProperties"

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
            // Wire the compact progress widget and hide the platform's horizontal progress bar.
            props.progressAction.attachTo(console)
            props.progressAction.installClickFilters(console)
            hideStatusLine(console)
        }

        // The platform's TestStatusLine (the horizontal red/green progress bar with "N tests passed") lives in
        // TestResultsPanel.myStatusLine. Hide it so the channel tabs can take its space, and the compact progress
        // widget in the toolbar replaces its function.
        private fun hideStatusLine(console: SMTRunnerConsoleView) {
            runCatching {
                val field = Class.forName("com.intellij.execution.testframework.ui.TestResultsPanel")
                    .getDeclaredField("myStatusLine")
                    .apply { isAccessible = true }
                val statusLine = field.get(console.resultsViewer) as? javax.swing.JComponent ?: return
                // Hide the status line and its SameHeightPanel wrapper so the space is reclaimed.
                statusLine.isVisible = false
                statusLine.parent?.let { wrapper ->
                    wrapper.isVisible = false
                    wrapper.parent?.revalidate()
                }
            }
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
