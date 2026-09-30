package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.PhpToolLauncher
import com.github.xepozz.testo.runs.TestoRunManifest
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.text.DateFormatUtil
import com.jetbrains.php.run.PhpRunConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The mutation runs of a project, by the archived Testo run each one mutates. */
@Service(Service.Level.PROJECT)
class TestoMutationService(private val project: Project) {
    private val runs = ConcurrentHashMap<Path, TestoMutationRun>()

    // Testo runs whose archive has been looked into for a mutation run, so a replay reads its files once.
    private val probed = ConcurrentHashMap.newKeySet<Path>()

    init {
        // Before mutation runs moved into the run archive they lived here; nothing reads that any more.
        val legacy = Path.of(PathManager.getSystemPath(), "testo", "infection", project.locationHash)
        if (Files.isDirectory(legacy)) {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { NioFiles.deleteRecursively(legacy) } }
        }
    }

    /**
     * The latest mutation run of the Testo run archived at [sourceRunDir]. One from an earlier session is read back from
     * the archive in the background, so the first call for it answers null and a later one has it.
     */
    fun runFor(sourceRunDir: Path?): TestoMutationRun? {
        if (sourceRunDir == null) return null
        runs[sourceRunDir]?.let { return it }
        if (probed.add(sourceRunDir)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val latest = TestoMutationArchive.runs(sourceRunDir).lastOrNull() ?: return@executeOnPooledThread
                val loaded = TestoMutationArchive.load(sourceRunDir, latest) ?: return@executeOnPooledThread
                runs.putIfAbsent(sourceRunDir, loaded)
            }
        }
        return null
    }

    /** The mutation runs of the Testo run archived at [sourceRunDir], newest first. Reads the archive: not on the EDT. */
    internal fun history(sourceRunDir: Path): List<TestoMutationHistoryEntry> {
        val live = runs[sourceRunDir]
        val archived = TestoMutationArchive.runs(sourceRunDir)
            .filter { it != live?.workDir }
            .mapNotNull { dir -> TestoMutationArchive.summary(dir)?.let { TestoMutationHistoryEntry.of(dir, it) } }
        return (archived + listOfNotNull(live?.let(TestoMutationHistoryEntry::of))).sortedByDescending { it.startedAt }
    }

    /** Brings up the mutation run archived in [dir]: its tab when one is open, else a new tab read from the archive. */
    internal fun open(sourceRunDir: Path, dir: Path, restart: (() -> Unit)?) {
        if (TestoMutationToolWindow.select(project, dir)) return
        runs[sourceRunDir]?.takeIf { it.workDir == dir }?.let { return TestoMutationToolWindow.show(project, it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val run = TestoMutationArchive.load(sourceRunDir, dir) ?: return@executeOnPooledThread
            run.restart = restart
            ApplicationManager.getApplication().invokeLater({
                TestoMutationToolWindow.show(project, run, "${run.title} · ${DateFormatUtil.formatTimeWithSeconds(run.startedAt)}")
            }, project.disposed)
        }
    }

    /** Mutates the run just archived at [runDir] when its tests passed, else says why it does not. */
    internal fun startAfterRun(
        configuration: TestoRunConfiguration,
        runDir: Path,
        manifest: TestoRunManifest,
        optionsFrom: TestoRunConfiguration,
    ) {
        when (val readiness = TestoInfectionReports.readiness(runDir, manifest)) {
            is TestoMutationReadiness.Ready -> ApplicationManager.getApplication().invokeLater({
                start(configuration, runDir, readiness, optionsFrom)
            }, project.disposed)
            is TestoMutationReadiness.Missing -> if (!manifest.cancelled) {
                notify(TestoBundle.message("infection.finished", configuration.name), readiness.hint, NotificationType.INFORMATION)
            }
        }
    }

    /** Runs Infection over [ready], the reports of [configuration]'s run archived at [runDir]. Call on the EDT. */
    internal fun start(
        configuration: TestoRunConfiguration,
        runDir: Path,
        ready: TestoMutationReadiness.Ready,
        /** Where the Infection options live: the saved configuration, which the tab's may only be a copy of. */
        optionsFrom: TestoRunConfiguration,
    ) {
        runs[runDir]?.takeIf { it.isRunning }?.let { return TestoMutationToolWindow.show(project, it) }

        val sources = runCatching { TestoInfectionReports.coveredSourceFiles(ready.coverageXml) }
            .onFailure { thisLogger().warn("Could not read the covered sources of ${ready.coverageXml}", it) }
            .getOrDefault(emptyList())
        val clone = configuration.clone() as TestoRunConfiguration
        TestoMutationArchive.prune(runDir, TestoMutationArchive.KEEP - 1)
        val launch = TestoInfectionLaunch(ready, sources, TestoMutationArchive.newRunDir(runDir, System.currentTimeMillis()), TestoInfectionOptions.of(optionsFrom.testoSettings.runnerSettings))
        clone.infectionLaunch = launch
        val interpreter = clone.interpreter
        val launcher = interpreter?.let { PhpToolLauncher(project, it) }

        val run = TestoMutationRun(configuration.name, runDir, launch.workDir) { launcher?.toLocal(it) }
        run.restart = { start(configuration, runDir, ready, optionsFrom) }
        if (runs.size >= MAX_RUNS) {
            runs.entries.filter { !it.value.isRunning }.minByOrNull { it.value.startedAt }?.let {
                runs.remove(it.key)
                probed.remove(it.key)
            }
        }
        runs[runDir] = run
        TestoMutationToolWindow.add(project, run)

        object : Task.Backgroundable(project, TestoBundle.message("infection.task.title", configuration.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (interpreter == null) throw ExecutionException(TestoBundle.message("infection.error.noInterpreter"))
                    execute(clone, interpreter, run, launch, indicator)
                } catch (e: ProcessCanceledException) {
                    if (run.isRunning) run.finish(null)
                    throw e
                } catch (e: Exception) {
                    if (e !is ExecutionException) thisLogger().warn("Mutation testing failed to start", e)
                    run.appendLog(e.message.orEmpty())
                    if (run.isRunning) run.finish(null)
                    notifyFailed(run, e.message ?: e.javaClass.simpleName)
                } finally {
                    launch.shared?.release()
                    // A copy of the Testo run's own reports, which the archive already has.
                    runCatching { NioFiles.deleteRecursively(launch.coverageDir) }
                }
                if (run.exitCode != null) notifyFinished(run)
            }
        }.queue()
    }

    private fun execute(
        clone: TestoRunConfiguration,
        interpreter: com.jetbrains.php.config.interpreters.PhpInterpreter,
        run: TestoMutationRun,
        launch: TestoInfectionLaunch,
        indicator: ProgressIndicator,
    ) {
        val command = clone.createCommand(interpreter, mutableMapOf(), mutableListOf(), false)
        val commandLine = command.createGeneralCommandLine(false)
        val handler = PhpRunConfiguration.createProcessHandler(project, command, false, false, commandLine)
        TestoMutationArchive.Recorder(launch.workDir).use { recorder ->
            val stream = TestoMutationStream(run, recorder::line)
            val header = "$ ${commandLine.commandLineString}"
            recorder.line(header)
            run.appendLog(header)
            watch(handler, stream, run, launch, indicator)
            recorder.summary(run)
        }
    }

    private fun watch(
        handler: com.intellij.execution.process.ProcessHandler,
        stream: TestoMutationStream,
        run: TestoMutationRun,
        launch: TestoInfectionLaunch,
        indicator: ProgressIndicator,
    ) {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                stream.feed(event.text, ProcessOutputType.isStdout(outputType))
            }
        })
        run.stopper = { handler.destroyProcess() }
        run.startedAt = System.currentTimeMillis()
        handler.startNotify()
        if (run.stopRequested) handler.destroyProcess()

        indicator.isIndeterminate = false
        while (!handler.waitFor(POLL_MS)) {
            if (indicator.isCanceled && !run.stopRequested) run.stop()
            val done = run.finishedCount()
            val total = run.expected
            indicator.isIndeterminate = total <= 0
            if (total > 0) indicator.fraction = done.toDouble() / total
            indicator.text2 = TestoBundle.message("infection.task.progress", done.toString(), total.toString())
        }
        stream.flush()
        launch.htmlTarget?.copyToLocal(project)
        launch.textTarget?.copyToLocal(project)
        TestoMutationArchive.applyTextLog(launch.textLog, run)
        run.finish(handler.exitCode)
    }

    private fun notifyFinished(run: TestoMutationRun) {
        val score = run.score()
        val content = when {
            run.stopRequested -> TestoBundle.message("infection.finished.stopped")
            run.exitCode != 0 && run.mutants.isEmpty() -> TestoBundle.message("infection.finished.failed", run.exitCode.toString())
            else -> TestoBundle.message(
                "infection.finished.score",
                score.msi?.toString() ?: "–",
                score.escaped.toString(),
                run.mutants.size.toString(),
            )
        }
        val failed = run.exitCode != 0 && !run.stopRequested && run.mutants.isEmpty()
        notify(run, content, if (failed) NotificationType.ERROR else NotificationType.INFORMATION)
    }

    private fun notifyFailed(run: TestoMutationRun, message: String) = notify(run, message, NotificationType.ERROR)

    private fun notify(title: String, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater({
            NotificationGroupManager.getInstance().getNotificationGroup("Testo").createNotification(title, content, type).notify(project)
        }, project.disposed)
    }

    private fun notify(run: TestoMutationRun, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                .createNotification(TestoBundle.message("infection.finished", run.title), content, type)
                .addAction(object : DumbAwareAction(TestoBundle.message("infection.show")) {
                    override fun actionPerformed(e: AnActionEvent) = TestoMutationToolWindow.show(project, run)
                })
            if (Files.isRegularFile(run.htmlReport)) {
                notification.addAction(object : DumbAwareAction(TestoBundle.message("infection.report.open")) {
                    override fun actionPerformed(e: AnActionEvent) = TestoMutationToolWindow.openReport(project, run)
                })
            }
            notification.notify(project)
        }
    }

    companion object {
        private const val POLL_MS = 200L
        private const val MAX_RUNS = 10

        fun getInstance(project: Project): TestoMutationService = project.service()
    }
}
