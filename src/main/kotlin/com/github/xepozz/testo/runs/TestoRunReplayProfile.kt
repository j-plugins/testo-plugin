package com.github.xepozz.testo.runs

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.coverage.TestoCoverageReport
import com.github.xepozz.testo.coverage.applyTestoCoverage
import com.github.xepozz.testo.coverage.closeTestoCoverage
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoChannelHistory
import com.github.xepozz.testo.tests.console.TestoConsoleAugmenter
import com.github.xepozz.testo.tests.console.TestoReportRef
import com.github.xepozz.testo.tests.console.TestoRunTimings
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.Key
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * Replays an archived run: feeds the recorded output through a [NopProcessHandler] into a console built on the *live*
 * [TestoConsoleProperties], so the whole UI — channel tabs, statuses, node tree, report buttons — is reconstructed by
 * the same converter that built it originally. No import machinery, no metainfo.
 *
 * Three switches keep a replay from acting like a run: [TestoConsoleProperties.replayMode] stops the converter from
 * recording the replayed stream into a new archive; `getConfiguration()` answers this profile instead of the throwaway
 * configuration, so the platform's `addToHistory` (which saves only for a real `RunConfiguration`) skips it; and the
 * report store's clock is pinned to the original run, so the captured report copies pass the mtime-vs-start gate.
 */
internal class TestoRunReplayProfile(
    private val project: Project,
    private val runDir: Path,
    private val manifest: TestoRunManifest,
    /** The test the "Show history" lens was clicked on: its node is selected once the replayed tree is built. */
    private val targetUrl: String? = null,
) : RunProfile {

    /** The executor the archived run used — what the tab's rerun button offers, whatever executor opened the replay. */
    val executorId: String get() = manifest.executorId

    /**
     * The archived run's own configuration, restored from the manifest — what the rerun buttons on a replayed tab
     * run. Falls back to a bare template for an archive that predates the recording (nothing to rerun there, but the
     * console still needs a configuration to be built from).
     */
    val testoConfiguration: TestoRunConfiguration by lazy {
        val configuration = TestoRunConfigurationType.INSTANCE
            .createTemplateConfiguration(project) as TestoRunConfiguration
        manifest.configuration.takeIf { it.isNotBlank() }?.let { xml ->
            runCatching { configuration.readExternal(JDOMUtil.load(xml)) }
                .onFailure { LOG.warn("Failed to restore the run configuration of $runDir", it) }
        }
        configuration.name = manifest.configurationName.ifEmpty { runDir.fileName.toString() }
        configuration
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        RunProfileState { _, _ ->
            val configuration = testoConfiguration

            val props = configuration.createTestConsoleProperties(executor) as TestoConsoleProperties
            props.replayMode = true
            props.replayProfile = this
            props.reportStore.startedAtOverride = manifest.startedAt
            // The toolbar clock shows the archived run, frozen: the replayed stream would otherwise restamp every mark
            // with today's time, and a replay that outruns the toolbar's own wiring would leave it counting forever.
            // An archive from before the marks were recorded still has the two the recording itself brackets it with.
            val marks = manifest.timings.takeIf { !it.isEmpty }
                ?: TestoRunTimings.Marks(startedAt = manifest.startedAt, finishedAt = manifest.finishedAt)
            if (!marks.isEmpty) props.runTimings.restore(marks)
            seedReports(props)
            // The command line is not part of the recorded stream (the live run puts it on the channel store, not the
            // process output), so the header is reprinted from the manifest — with the original run's clock.
            manifest.commandLine.takeIf { it.isNotBlank() }?.let { commandLine ->
                props.commandLine = commandLine
                props.channelStore.setHeader(TestoConsoleAugmenter.runHeader(commandLine, manifest.startedAt))
            }

            val handler = NopProcessHandler()
            val console = SMTestRunnerConnectionUtil.createAndAttachConsole("Testo", handler, props)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun startNotified(event: ProcessEvent) {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        feed(handler)
                        applyArchivedCoverage()
                        val url = targetUrl ?: return@executeOnPooledThread
                        (console as? SMTRunnerConsoleView)?.let { TestoChannelHistory.selectWhenReady(it, url) }
                    }
                }
            })
            DefaultExecutionResult(console, handler)
        }

    private fun feed(handler: ProcessHandler) {
        try {
            TestoRunStore.getInstance(project).readChunks(runDir) { stream, text ->
                handler.notifyTextAvailable(text, keyOf(stream))
            }
        } catch (e: Exception) {
            LOG.warn("Testo run replay failed for $runDir", e)
        } finally {
            handler.destroyProcess()
        }
    }

    /**
     * Fills the report buttons from the archive rather than from the replayed announcements: the recorded log names
     * paths a later run has since overwritten, while the archive holds this run's own copies. A coverage report with
     * no copy lost the dedup to one that has it, so it is not offered at all.
     */
    private fun seedReports(props: TestoConsoleProperties) {
        manifest.reports.forEach { report ->
            val captured = report.stored?.let { runDir.resolve(it).toAbsolutePath().toString() }
            if (captured == null && CoverageFormat.fromId(report.format) != null) return@forEach
            props.reportStore.note(
                TestoReportRef(
                    format = report.format,
                    path = captured ?: report.path,
                    // A captured copy is already a local absolute path; nothing is left to resolve it from.
                    relativePath = if (captured != null) null else report.relativePath,
                    name = report.name,
                    schemaVersion = null,
                )
            )
        }
    }

    /**
     * Brings the Coverage tool window in line with the run being opened: its own captured reports, or — for a run that
     * produced none — nothing at all. Leaving whatever the previous session applied would attribute one run's coverage
     * to another; a replay is a whole state, not an addition to the current one.
     */
    private fun applyArchivedCoverage() {
        val reports = manifest.reports
            .mapNotNull { report ->
                val stored = report.stored ?: return@mapNotNull null
                val format = CoverageFormat.fromId(report.format) ?: return@mapNotNull null
                val captured = runDir.resolve(stored)
                // coverage-xml is a directory; the loader consumes its index.
                val dataFile = if (format == CoverageFormat.COVERAGE_XML) captured.resolve("index.xml") else captured
                if (!Files.exists(dataFile)) null else format to TestoCoverageReport(report.name, format, dataFile)
            }
            // One per format: a CLI-flag report and a testo.php-configured one of the same format hold the same run's
            // data, and merging both would count every hit twice.
            .associate { it }
            .values.toList()
        ApplicationManager.getApplication().invokeLater(
            {
                if (reports.isEmpty()) closeTestoCoverage(project) else applyTestoCoverage(project, reports)
            },
            project.disposed,
        )
    }

    private fun keyOf(stream: Int): Key<*> = when (stream) {
        TestoRunRecording.STDERR -> ProcessOutputTypes.STDERR
        TestoRunRecording.SYSTEM -> ProcessOutputTypes.SYSTEM
        else -> ProcessOutputTypes.STDOUT
    }

    override fun getName(): String =
        TestoBundle.message("testo.runs.replay.name", manifest.configurationName.ifEmpty { runDir.fileName.toString() })

    override fun getIcon(): Icon = TestoIcons.TESTO

    companion object {
        private val LOG = Logger.getInstance(TestoRunReplayProfile::class.java)

        /** Opens the archived run in a run tab. Call on the EDT. */
        fun replay(project: Project, runDir: Path, manifest: TestoRunManifest, targetUrl: String? = null) {
            try {
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                ExecutionEnvironmentBuilder
                    .create(project, executor, TestoRunReplayProfile(project, runDir, manifest, targetUrl))
                    .buildAndExecute()
            } catch (e: Exception) {
                LOG.warn("Failed to start Testo run replay for $runDir", e)
            }
        }
    }
}
