package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.console.ChannelOutputStore
import com.github.xepozz.testo.tests.console.LogLevelFilter
import com.github.xepozz.testo.tests.console.TestoNodeIndex
import com.github.xepozz.testo.tests.console.TestoOutputToGeneralEventsConverter
import com.github.xepozz.testo.tests.console.TestoProgressAction
import com.github.xepozz.testo.tests.console.TestoReportStore
import com.github.xepozz.testo.tests.console.TestoReportsAction
import com.github.xepozz.testo.tests.console.TestoRunTimings
import com.github.xepozz.testo.tests.console.TestoStatusStore
import com.github.xepozz.testo.tests.console.TestoTargetStore
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.phpunit.PhpPsiLocationWithDataSet
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import one.util.streamex.StreamEx

class TestoConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
    val pathMapper: PhpPathMapper,
) : SMTRunnerConsoleProperties(config, TestoBundle.message("testo.local.run.display.name"), executor),
    SMCustomMessagesParsing {
    val myTestLocator = TestoTestLocator(pathMapper)

    val channelStore = ChannelOutputStore()

    val levelFilter = LogLevelFilter()

    // What ties a tree node back to the protocol node it came from; every store below is keyed by that node's id.
    val nodeIndex = TestoNodeIndex()

    val statusStore = TestoStatusStore(nodeIndex)

    val runTimings = TestoRunTimings()

    val targetStore = TestoTargetStore(nodeIndex)

    val reportStore = TestoReportStore()

    val progressAction = TestoProgressAction()

    // The run being archived (runs.TestoRunStore) — created lazily by the converter on the first output chunk,
    // finalized by TestoRunArchiver on process termination. Null on replays and before any output.
    @Volatile
    var recording: com.github.xepozz.testo.runs.TestoRunRecording? = null

    /** True on a replayed archive: the converter must not re-record the stream, the archiver must not re-archive it. */
    var replayMode = false

    // The process command line, as the console header shows it. Captured when the channel tabs are installed (the one
    // place holding the ProcessHandler) and archived, so a replay can reprint the header of the run it replays.
    @Volatile
    var commandLine: String? = null

    // A replay's console answers this profile as its "configuration". The platform's addToHistory saves a run into its
    // own history only for a real RunConfiguration — the throwaway configuration a replay is built on must stay hidden,
    // or every replay would spawn a new platform-history entry (and re-write per-test states).
    var replayProfile: com.intellij.execution.configurations.RunProfile? = null

    // The coverage report files each `--coverage-*` flag of this run points at, set by the Coverage runner. They win
    // the one-per-format dedup — over a report a testo.php writer put somewhere the IDE does not control.
    @Volatile
    var coverageFlagPaths: List<java.nio.file.Path> = emptyList()

    // getLocalPath, not getLocalFile: the report was written moments ago and the VFS may not know the file yet.
    val reportsAction = TestoReportsAction(reportStore, project) { path -> pathMapper.getLocalPath(path) }

    // Guards the channel-tab install: set once whoever wires the tabs first (the run-path ExecutionListener or the
    // debug runner, which installs them directly), so the other side is a no-op instead of a double install.
    var channelsInstalled = false

    override fun getConfiguration(): com.intellij.execution.configurations.RunProfile =
        replayProfile ?: super.getConfiguration()

    /** The archive this tab stands for: the one a history tab replays, or the one a live run is recorded into. */
    fun currentRunDir(): java.nio.file.Path? =
        (replayProfile as? com.github.xepozz.testo.runs.TestoRunReplayProfile)?.runDir ?: recording?.dir

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter =
        TestoOutputToGeneralEventsConverter(
            testFrameworkName,
            consoleProperties,
            channelStore,
            levelFilter,
            statusStore,
            runTimings,
            targetStore,
            nodeIndex,
            reportStore,
        )

    override fun getTestStackTraceParser(url: String, proxy: SMTestProxy, project: Project) =
        TestoStackTraceParser.parse(url, proxy.stacktrace, proxy.errorMessage, testLocator, project)

    override fun getTestLocator() = this.myTestLocator

    override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? {
        if (location is PhpPsiLocationWithDataSet<*> && location.getPsiElement() !is Method) {
            return location.navigatable
        } else {
            val reversedStackTrace = StringUtil.splitByLinesKeepSeparators(stacktrace)
                .reversed()
                .filter { it.isNotEmpty() }
                .joinToString("")
            return super.getErrorNavigatable(location, reversedStackTrace)
        }
    }

    override fun isPrintTestingStartedTime() = true

    override fun isIdBasedTestTree() = true

    // Our own actions on the test results toolbar's visible row. Added here (rather than via appendAdditionalActions,
    // which the platform routes into the gear submenu) they land among the primary actions at construction time — so
    // they survive the snapshot that RunTab merges into the run tab's toolbar, and show in the standalone debug
    // console toolbar too.
    public override fun createImportActions(): Array<com.intellij.openapi.actionSystem.AnAction> =
        arrayOf(
            // Laid out from the right edge inwards: listed first = furthest right. So this array reads right to left —
            // the replay group sits at the right end, expand/collapse at its left, next to the separator that follows
            // Show Passed / Show Ignored.
            // This run's own archive: export it, decide what retention may do with it, load an exported one.
            com.github.xepozz.testo.runs.TestoReplayGroup(project, this),
            // Deliberately not super's: that array is where the platform's own "Test History" comes from, and its
            // entries open a saved XML through the import machinery — a console that is none of ours.
            com.github.xepozz.testo.runs.TestoRunHistoryGroup(project, this),
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            // The platform keeps its own expand/collapse in the toolbar's overflow group; on a test tree they are used
            // constantly, so ours sit on the visible row. The platform's stay where they are: `ToolbarPanel` copies
            // both of its groups into arrays before `RunTab` rebuilds the toolbar from them, so nothing the platform
            // put there can be moved or removed afterwards.
            com.github.xepozz.testo.tests.console.TestoTreeCollapseAction(),
            com.github.xepozz.testo.tests.console.TestoTreeExpandAction(),
            reportsAction,
            progressAction,
        )
}
