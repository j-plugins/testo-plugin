package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.PhpToolLauncher
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
import com.jetbrains.php.run.PhpRunConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The mutation runs of a project, by the archived Testo run each one mutates. */
@Service(Service.Level.PROJECT)
class TestoMutationService(private val project: Project) {
    private val runs = ConcurrentHashMap<Path, TestoMutationRun>()

    init {
        // No tab outlives the IDE, so whatever the last session left is garbage. Listed now, deleted later: a run started
        // meanwhile writes into a directory this listing does not hold.
        val stale = children(root).flatMap(::children)
        if (stale.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                stale.forEach { runCatching { NioFiles.deleteRecursively(it) } }
            }
        }
    }

    fun runFor(sourceRunDir: Path?): TestoMutationRun? = sourceRunDir?.let(runs::get)

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
        val launch = TestoInfectionLaunch(ready, sources, workDir(runDir), TestoInfectionOptions.of(optionsFrom.testoSettings.runnerSettings))
        clone.infectionLaunch = launch
        val interpreter = clone.interpreter
        val launcher = interpreter?.let { PhpToolLauncher(project, it) }

        val run = TestoMutationRun(configuration.name, runDir, launch.workDir) { launcher?.toLocal(it) }
        run.restart = { start(configuration, runDir, ready, optionsFrom) }
        if (runs.size >= MAX_RUNS) {
            runs.entries.filter { !it.value.isRunning }.minByOrNull { it.value.startedAt }?.let { runs.remove(it.key) }
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
                    if (run.discarded) run.deleteFiles()
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
        run.appendLog("$ ${commandLine.commandLineString}")
        val handler = PhpRunConfiguration.createProcessHandler(project, command, false, false, commandLine)
        val stream = TestoMutationStream(run)
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
        readTextLog(launch.textLog, run)
        run.finish(handler.exitCode)
    }

    private fun readTextLog(file: Path, run: TestoMutationRun) {
        if (!Files.isRegularFile(file)) return
        val entries = runCatching { TestoMutationTextLog.parse(Files.readString(file)) }
            .onFailure { thisLogger().warn("Could not read the mutation log $file", it) }
            .getOrNull() ?: return
        run.mutants.forEach { mutant ->
            val entry = entries[mutant.hash] ?: return@forEach
            if (mutant.original == null) {
                mutant.original = entry.original
                mutant.mutated = entry.mutated
            }
            mutant.output = entry.output
        }
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

    // One per mutation run, not per Testo run: a pinned tab keeps its reports while the next run of the same Testo run
    // writes its own.
    private fun workDir(runDir: Path): Path =
        root.resolve(FileUtil.sanitizeFileName(runDir.fileName.toString())).resolve(System.currentTimeMillis().toString())

    private fun children(dir: Path): List<Path> = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())

    private val root: Path get() = Path.of(PathManager.getSystemPath(), "testo", "infection", project.locationHash)

    companion object {
        private const val POLL_MS = 200L
        private const val MAX_RUNS = 10

        fun getInstance(project: Project): TestoMutationService = project.service()
    }
}
