package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.reapplyTestoCoverage
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.runs.TestoRunManifest
import com.github.xepozz.testo.runs.TestoRunStore
import com.intellij.execution.ExecutionException
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
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.text.DateFormatUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The mutation runs of a project, by the archived Testo run each one mutates. */
@Service(Service.Level.PROJECT)
class TestoMutationService(private val project: Project) {
    private val runs = ConcurrentHashMap<Path, TestoMutationRun>()

    // Testo runs whose archive has been looked into for a mutation run, so a replay reads its files once.
    private val probed = ConcurrentHashMap.newKeySet<Path>()

    private val scoreCache = ConcurrentHashMap<Path, Map<String, MutationScore>>()

    private val coveringIndexes = ConcurrentHashMap<Path, TestoCoveringTestIndex>()

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
                TestoMutationEditorMarks.getInstance(project).refresh()
            }
        }
        return null
    }

    /** The *Mutations* window's selected tab, kept here because only the EDT may read the window. */
    @Volatile
    internal var selected: TestoMutationRun? = null

    /** The run the editor marks show: the *Mutations* window's selected tab, else the latest started. */
    fun current(): TestoMutationRun? = selected ?: runs.values.maxByOrNull { it.startedAt }

    /** The mutation runs of the Testo run archived at [sourceRunDir], newest first. Reads the archive: not on the EDT. */
    internal fun history(sourceRunDir: Path): List<TestoMutationHistoryEntry> {
        val live = runs[sourceRunDir]
        val archived = TestoMutationArchive.runs(sourceRunDir)
            .filter { it != live?.workDir }
            .mapNotNull { dir -> TestoMutationArchive.summary(dir)?.let { TestoMutationHistoryEntry.of(dir, it) } }
        return (archived + listOfNotNull(live?.let(TestoMutationHistoryEntry::of))).sortedByDescending { it.startedAt }
    }

    /** Brings up the mutation run archived in [dir]: its tab when one is open, else a new tab read from the archive. */
    internal fun open(sourceRunDir: Path, dir: Path, recipe: TestoMutationRecipe?) {
        if (TestoMutationToolWindow.select(project, dir)) return
        runs[sourceRunDir]?.takeIf { it.workDir == dir }?.let { return TestoMutationToolWindow.show(project, it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val run = TestoMutationArchive.load(sourceRunDir, dir) ?: return@executeOnPooledThread
            run.recipe = recipe
            ApplicationManager.getApplication().invokeLater({
                TestoMutationToolWindow.show(project, run, "${run.title} · ${DateFormatUtil.formatTimeWithSeconds(run.startedAt)}")
            }, project.disposed)
        }
    }

    /** Mutates the run just archived at [runDir] when its tests passed, else says why it does not. */
    internal fun startAfterRun(
        configuration: TestoConfiguration,
        runDir: Path,
        manifest: TestoRunManifest,
        optionsFrom: TestoConfiguration,
        environment: TestoToolEnvironment?,
    ) {
        when (val readiness = TestoInfectionReports.readiness(runDir, manifest)) {
            is TestoMutationReadiness.Ready -> ApplicationManager.getApplication().invokeLater({
                start(TestoMutationRecipe(configuration, runDir, readiness, optionsFrom, capturedEnvironment = environment))
            }, project.disposed)
            is TestoMutationReadiness.Missing -> if (!manifest.cancelled) {
                notify(TestoBundle.message("infection.finished", configuration.name), readiness.hint, NotificationType.INFORMATION)
            }
        }
    }

    /** Runs Infection over the reports of [recipe]'s Testo run. Call on the EDT. */
    internal fun start(recipe: TestoMutationRecipe) {
        val runDir = recipe.runDir
        runs[runDir]?.takeIf { it.isBusy }?.let { return TestoMutationToolWindow.show(project, it) }
        TestoMutationArchive.unconfirmedRun(runDir)?.let { dir ->
            TestoMutationArchive.load(runDir, dir)?.let { restored ->
                restored.recipe = recipe
                track(restored)
                TestoMutationToolWindow.show(project, restored)
            }
            return
        }

        val sources = coveredSources(recipe)
        TestoMutationArchive.prune(runDir, TestoMutationArchive.KEEP - 1)
        val launch = TestoInfectionLaunch(recipe.ready, sources, TestoMutationArchive.newRunDir(runDir, System.currentTimeMillis()), recipe.options())
        val run = TestoMutationRun(recipe.title, runDir, launch.workDir) { recipe.environment.toLocal(it) }
        run.recipe = recipe
        track(run)
        TestoMutationToolWindow.add(project, run)

        object : Task.Backgroundable(project, TestoBundle.message("infection.task.title", recipe.configuration.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    TestoMutationArchive.Recorder(launch.workDir).use { recorder ->
                        run.startedAt = System.currentTimeMillis()
                        val stream = TestoMutationStream(run, recorder::line, onFile = run::fingerprint)
                        val exitCode = execute(recipe.environment, run, launch, stream, recorder, indicator) { run.stopRequested }
                        TestoInfectionHtmlReport.clean(launch.htmlReport)
                        run.finish(exitCode)
                        recorder.summary(run)
                    }
                } catch (e: ProcessCanceledException) {
                    run.stop()
                    if (run.isRunning) run.finish(null)
                    throw e
                } catch (e: Exception) {
                    if (e !is ExecutionException) thisLogger().warn("Mutation testing failed to start", e)
                    run.appendLog(e.message.orEmpty())
                    if (run.isRunning) run.finish(null)
                    notifyFailed(run, e.message ?: e.javaClass.simpleName)
                } finally {
                    runCatching { TestoMutationArchive.writeSummary(launch.workDir, run) }
                        .onFailure { thisLogger().warn("Could not save mutation run", it) }
                    release(launch)
                }
                scored(runDir)
                if (run.exitCode != null) notifyFinished(run)
            }
        }.queue()
    }

    /**
     * Every file the mutation runs of the Testo run at [sourceRunDir] have judged, by host path, each by the run that did
     * so last. Read from their summaries once, then again after each mutation run or rerun of it.
     */
    fun scores(sourceRunDir: Path): Map<String, MutationScore> =
        scoreCache.getOrPut(sourceRunDir) { runCatching { TestoMutationArchive.scores(sourceRunDir) }.getOrDefault(emptyMap()) }

    // The Coverage view builds its columns once: a view on this run is built again for the MSI column to appear.
    private fun scored(sourceRunDir: Path) {
        scoreCache.remove(sourceRunDir)
        ApplicationManager.getApplication().invokeLater({ reapplyTestoCoverage(project, sourceRunDir) }, project.disposed)
    }

    /** The tests that ran over [mutant]'s code and the ones that failed on it. Reads the run's coverage: not on the EDT. */
    internal fun testsOf(run: TestoMutationRun, mutant: Mutant): TestoMutantTests {
        val covering = mutant.lines?.let { lines -> coveringIndex(run)?.covering(mutant.file.path, lines) }.orEmpty()
        return TestoMutantTests(covering, TestoMutantTests.killing(mutant.output))
    }

    private fun coveringIndex(run: TestoMutationRun): TestoCoveringTestIndex? {
        val coverage = run.recipe?.ready?.coverageXml
            ?: (TestoInfectionReports.readiness(run.sourceRunDir, TestoRunStore.getInstance(project).readManifest(run.sourceRunDir))
                as? TestoMutationReadiness.Ready)?.coverageXml
            ?: return null
        if (coveringIndexes.size >= MAX_RUNS) coveringIndexes.clear()
        return coveringIndexes.getOrPut(coverage) { TestoCoveringTestIndex(coverage) }
    }

    /** Makes [run] the latest of its Testo run, the one [runFor] and [current] answer. */
    internal fun track(run: TestoMutationRun) {
        if (runs.size >= MAX_RUNS) {
            runs.entries.filter { !it.value.isBusy && it.key != run.sourceRunDir }.minByOrNull { it.value.startedAt }?.let {
                runs.remove(it.key)
                probed.remove(it.key)
            }
        }
        runs[run.sourceRunDir] = run
        TestoMutationEditorMarks.getInstance(project).refresh()
    }

    fun restart(run: TestoMutationRun) {
        run.recipe?.let(::start)
    }

    /**
     * Runs Infection again on [mutants] and updates them in place: a sub-run of [run], kept in its archive beside it,
     * one process per [rerunUnits] entry. Call on the EDT.
     */
    fun rerun(run: TestoMutationRun, mutants: List<Mutant>) {
        val recipe = run.recipe ?: return
        val targets = mutants.distinct().filter { it.finished }
        if (run.isBusy || targets.isEmpty()) return
        val before = targets.associateWith { it.status to it.previousStatus }
        targets.forEach { mutant ->
            mutant.previousStatus = mutant.status
            mutant.status = null
            mutant.finished = false
            mutant.rerunning = true
        }
        run.rerunStopRequested = false
        run.rerunning = true
        run.changed()

        object : Task.Backgroundable(project, TestoBundle.message("infection.rerun.task", targets.size.toString(), run.title), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val sources = coveredSources(recipe)
                    val options = recipe.options()
                    val wholeFiles = options.filter != null || options.scope != TestoRunSelection.INFECTION_SCOPE_GIT_LINES
                    val units = rerunUnits(targets, wholeFiles)
                    indicator.isIndeterminate = false
                    for ((index, unit) in units.withIndex()) {
                        if (indicator.isCanceled) run.stop()
                        if (run.rerunStopRequested) break
                        indicator.fraction = index.toDouble() / units.size
                        val unitOptions = when (unit) {
                            is RerunUnit.One -> {
                                indicator.text2 = "${unit.mutant.mutator} · ${unit.mutant.file.name}"
                                options.copy(mutantId = unit.mutant.hash, mutantFile = sourceOf(unit.mutant.file.path, sources))
                            }
                            is RerunUnit.File -> {
                                indicator.text2 = unit.file.name
                                options.copy(filter = TestoInfectionArguments.pathFilter(unit.file.path))
                            }
                        }
                        rerunOne(recipe, run, unitOptions, sources, indicator)
                    }
                } catch (e: ProcessCanceledException) {
                    run.stop()
                    throw e
                } catch (e: Exception) {
                    if (e !is ExecutionException) thisLogger().warn("Mutant rerun failed to start", e)
                    run.appendLog(e.message.orEmpty())
                } finally {
                    // A mutant no process reported keeps what it had: stopped before its turn, or its code changed and
                    // Infection gave it another ID.
                    val missing = targets.filter { !it.finished }
                    missing.forEach { mutant ->
                        val (status, previous) = before.getValue(mutant)
                        mutant.status = status
                        mutant.previousStatus = previous
                        mutant.finished = true
                    }
                    targets.forEach { it.rerunning = false }
                    run.rerunning = false
                    if (run.pendingTool == null) run.stopper = null
                    val rescored = (targets - missing.toSet()).mapTo(HashSet()) { it.file.path }
                    runCatching { TestoMutationArchive.writeSummary(run.workDir, run, rescored) }
                        .onFailure { thisLogger().warn("Could not update the summary of ${run.workDir}", it) }
                    run.changed()
                    scored(run.sourceRunDir)
                    notifyRerun(run, targets - missing.toSet(), missing.size)
                }
            }
        }.queue()
    }

    private fun rerunOne(
        recipe: TestoMutationRecipe,
        run: TestoMutationRun,
        options: TestoInfectionOptions,
        sources: List<String>,
        indicator: ProgressIndicator,
    ) {
        val dir = TestoMutationArchive.newRerunDir(run.workDir, System.currentTimeMillis())
        val launch = TestoInfectionLaunch(recipe.ready, sources, dir, options, withHtml = false)
        try {
            TestoMutationArchive.Recorder(dir).use { recorder ->
                val stream = TestoMutationStream(run, recorder::line, rerun = true)
                execute(recipe.environment, run, launch, stream, recorder, indicator) { run.rerunStopRequested }
            }
        } finally {
            release(launch)
        }
    }

    private fun coveredSources(recipe: TestoMutationRecipe): List<String> =
        runCatching { TestoInfectionReports.coveredSourceFiles(recipe.ready.coverageXml) }
            .onFailure { thisLogger().warn("Could not read the covered sources of ${recipe.ready.coverageXml}", it) }
            .getOrDefault(emptyList())

    private fun release(launch: TestoInfectionLaunch) {
        // A copy of the Testo run's own reports, which the archive already has.
        val cleanup: () -> Unit = {
            try {
                runCatching { NioFiles.deleteRecursively(launch.coverageDir) }
                    .onFailure { thisLogger().warn("Could not release mutation inputs", it) }
            } finally {
                launch.inputProtection?.close()
                launch.inputProtection = null
            }
        }
        val prepared = launch.prepared
        if (prepared == null) cleanup()
        else {
            prepared.afterRelease(cleanup)
            prepared.close()
        }
    }

    /** Runs one Infection process to its end, feeding [stream]. Returns its exit code. */
    private fun execute(
        environment: TestoToolEnvironment,
        run: TestoMutationRun,
        launch: TestoInfectionLaunch,
        stream: TestoMutationStream,
        recorder: TestoMutationArchive.Recorder,
        indicator: ProgressIndicator,
        stopped: () -> Boolean,
    ): Int {
        launch.inputProtection = TestoMutationArchive.protectInputs(launch.workDir)
        val prepared = TestoInfectionCommand.create(environment, launch)
        val exitCode = executeToolProcess(prepared, run, stream, indicator, stopped, recorder::line)
        TestoMutationArchive.applyTextLog(launch.textLog, run)
        return exitCode
    }

    private fun notifyRerun(run: TestoMutationRun, rerun: List<Mutant>, missing: Int) {
        val content = buildList {
            if (rerun.isNotEmpty()) {
                val killed = rerun.count { it.status?.isDefeated == true }
                val escaped = rerun.count { it.status == MutantStatus.ESCAPED }
                add(TestoBundle.message("infection.rerun.finished", killed.toString(), escaped.toString(), rerun.size.toString()))
            }
            when {
                run.rerunStopRequested -> add(TestoBundle.message("infection.finished.stopped"))
                missing > 0 -> add(TestoBundle.message("infection.rerun.notFound", missing.toString()))
            }
        }.joinToString("\n")
        val type = if (missing > 0 && !run.rerunStopRequested) NotificationType.WARNING else NotificationType.INFORMATION
        notify(run, content, type)
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
        private const val MAX_RUNS = 10

        fun getInstance(project: Project): TestoMutationService = project.service()
    }
}

/** What a mutation run is started from, so it can be started again or have its mutants rerun. */
internal class TestoMutationRecipe(
    val configuration: TestoConfiguration,
    /** The archived Testo run whose reports are mutated. */
    val runDir: Path,
    val ready: TestoMutationReadiness.Ready,
    /** Where the Infection options live: the saved configuration, which the tab's may only be a copy of. */
    val optionsFrom: TestoConfiguration,
    /** The file or directory the run is narrowed to, as [TestoInfectionArguments.pathFilter] spells it for Infection. */
    val filter: String? = null,
    /** What [filter] names, for the tab. */
    val scopeName: String? = null,
    capturedEnvironment: TestoToolEnvironment? = null,
) {
    val environment: TestoToolEnvironment by lazy {
        capturedEnvironment ?: TestoPhp.getInstance().toolEnvironment(configuration)
    }
    val title: String get() = scopeName?.let { "${configuration.name} · $it" } ?: configuration.name

    /** Read at each start, so an option changed since the first run applies to the next. */
    fun options(): TestoInfectionOptions = TestoInfectionOptions.of(optionsFrom.selection).copy(filter = filter)
}

internal sealed interface RerunUnit {
    class One(val mutant: Mutant) : RerunUnit

    class File(val file: MutatedFile) : RerunUnit
}

/**
 * The processes that rerun [targets]. `--id` takes a single ID, so each mutant is one of its own, unless it is one of
 * several targets that make up a whole file: those are one process over the file. Not with [wholeFiles] off, for
 * `--git-diff-lines` replaces the filter with the changed files and would mutate every line of them.
 */
internal fun rerunUnits(targets: List<Mutant>, wholeFiles: Boolean): List<RerunUnit> {
    val wanted = targets.toSet()
    return targets.groupBy { it.file }.flatMap { (file, mutants) ->
        if (wholeFiles && mutants.size > 1 && file.mutants.all { it in wanted }) listOf(RerunUnit.File(file))
        else mutants.map(RerunUnit::One)
    }
}

/** The coverage's spelling of [path], a mutated file as the interpreter sees it: the source it ends with. */
internal fun sourceOf(path: String, sources: List<String>): String? {
    val normalized = path.replace('\\', '/')
    return sources.filter { normalized == it || normalized.endsWith("/$it") }.maxByOrNull { it.length }
}

/**
 * A file or directory picked on the host as the coverage spells it: a file's source, a directory with a trailing
 * slash, found through a covered file under it. Empty when the directory holds the whole
 * coverage, null when nothing under it is covered.
 */
internal fun mutationFilterFor(selected: String, directory: Boolean, coveredFiles: Collection<String>, sources: List<String>): String? {
    val path = selected.replace('\\', '/').trimEnd('/')
    if (!directory) return sourceOf(path, sources)
    val prefix = "$path/"
    for (covered in coveredFiles) {
        val file = covered.replace('\\', '/')
        if (!file.startsWith(prefix)) continue
        val source = sourceOf(file, sources) ?: continue
        val root = file.removeSuffix(source)
        return if (prefix.length > root.length) prefix.removePrefix(root) else ""
    }
    return null
}
