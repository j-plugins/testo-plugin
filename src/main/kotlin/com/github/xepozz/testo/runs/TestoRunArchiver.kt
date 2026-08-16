package com.github.xepozz.testo.runs

import com.github.xepozz.testo.coverage.dedupeCoverageByFormat
import com.github.xepozz.testo.coverage.perTest.TestoCoverageKeys
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoHistoryIndex
import com.github.xepozz.testo.tests.console.TestoReportRef
import com.github.xepozz.testo.tests.console.TestoRunTimings
import com.github.xepozz.testo.tests.console.resolveCoverageDataFile
import com.github.xepozz.testo.tests.console.resolveReport
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Finalizes a recorded run once its process ends: closes `output.log`, captures the coverage report files into the
 * run's `reports/` (a report path is reused by the next run, the captured copy is not), and writes the manifest —
 * whose arrival is what marks the archive complete. Idempotent across the several termination hooks that may fire
 * (the run-path ExecutionListener, the debug runner); the first caller wins.
 *
 * Reports are captured at process termination: Testo writes them before exiting (announced at session start, written
 * by the suite-finished listener), the same contract the platform's own coverage loading relies on.
 */
internal object TestoRunArchiver {
    private val LOG = Logger.getInstance(TestoRunArchiver::class.java)

    fun finalizeRun(project: Project, props: TestoConsoleProperties) {
        if (props.replayMode) return
        val recording = props.recording ?: return
        if (!recording.tryBeginFinish()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                recording.closeOutput()
                val mapToLocal: (String) -> String? = { runCatching { props.pathMapper.getLocalPath(it) }.getOrNull() }
                val writtenAfter = props.reportStore.runStartedAt
                // Every report the run announced is kept — the archive is what a replay reads instead of the log, and
                // an HTML report is as much part of a run as its coverage. The one exception is a coverage report that
                // lost the one-per-format dedup: it is the same run's data under a second path, and it is the loser
                // the applied bundle already ignored.
                val resolved = props.reportStore.coverage().mapNotNull { ref ->
                    resolveCoverageDataFile(ref, project, mapToLocal, writtenAfter)?.let { ref to it }
                }
                val flagKeys = props.coverageFlagPaths.map { TestoCoverageKeys.normalize(it.toString()) }.toSet()
                val winners = dedupeCoverageByFormat(resolved, flagKeys).toMap()
                val usedNames = HashSet<String>()
                val reports = props.reportStore.all().map { ref ->
                    val local = when {
                        ref.isCoverage -> winners[ref]
                        else -> resolveReport(ref, project, mapToLocal, writtenAfter)
                    }
                    val stored = local?.let { capture(recording, ref, it, usedNames) }
                    StoredReport(ref.format, ref.name, ref.path, ref.relativePath, stored)
                }
                recording.writeLocations()
                val finishedAt = System.currentTimeMillis()
                recording.writeManifest(
                    TestoRunManifest(
                        configurationName = recording.configurationName,
                        executorId = recording.executorId,
                        commandLine = props.commandLine.orEmpty(),
                        configuration = serializeConfiguration(props),
                        startedAt = recording.startedAt,
                        finishedAt = finishedAt,
                        timings = runMarks(props, recording, finishedAt),
                        retention = recording.retention,
                        statuses = props.statusStore.counts().entries.associate { it.key.wireName to it.value },
                        reports = reports,
                    )
                )
                TestoRunStore.getInstance(project).prune()
                // The archive is the lens's whole source of truth, and it only just became complete — so the lens is
                // told here rather than on a timer after the process ends.
                TestoHistoryIndex.invalidate()
                TestoHistoryIndex.refreshLens(project)
            } catch (e: Exception) {
                LOG.warn("Failed to archive Testo run ${recording.dir}", e)
            }
        }
    }

    /**
     * The toolbar clock's marks, filled in where the run left them open: the process may exit before the clock is
     * even wired (a run shorter than the console's own setup), and the archive must still describe a finished run.
     */
    private fun runMarks(props: TestoConsoleProperties, recording: TestoRunRecording, finishedAt: Long): TestoRunTimings.Marks {
        val marks = props.runTimings.marks()
        return marks.copy(
            startedAt = marks.startedAt.takeIf { it > 0 } ?: recording.startedAt,
            finishedAt = marks.finishedAt.takeIf { it > 0 } ?: finishedAt,
        )
    }

    /**
     * The run configuration as XML — the same form the IDE persists it in, so a replay can restore it and rerun the
     * real thing. Empty when this console is not backed by a Testo configuration (nothing to rerun then).
     */
    private fun serializeConfiguration(props: TestoConsoleProperties): String =
        (props.configuration as? TestoRunConfiguration)?.let { configuration ->
            runCatching {
                val element = Element("configuration")
                configuration.writeExternal(element)
                JDOMUtil.write(element)
            }.onFailure { LOG.warn("Failed to serialize the Testo run configuration", it) }.getOrNull()
        }.orEmpty()

    /**
     * Copies one report into the run's `reports/`, and returns the run-dir-relative path of its **entry** (what the
     * announcement pointed at) — or null when the copy failed. A report laid out as a directory travels whole; the
     * entry inside it is what the manifest names, so a replay can hand that straight to the report button.
     */
    private fun capture(recording: TestoRunRecording, ref: TestoReportRef, local: Path, usedNames: MutableSet<String>): String? =
        runCatching {
            Files.createDirectories(recording.reportsDir)
            val name = uniqueName(capturedReportName(reportStem(ref), local), usedNames)
            if (isDirectoryReport(local)) {
                copyDirectory(local.parent, recording.reportsDir.resolve(name))
                "${TestoRunRecording.REPORTS_DIR}/$name/${local.fileName}"
            } else {
                Files.copy(local, recording.reportsDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                "${TestoRunRecording.REPORTS_DIR}/$name"
            }
        }.onFailure { LOG.warn("Failed to capture report ${ref.path} of ${ref.format}", it) }.getOrNull()

    private fun reportStem(ref: TestoReportRef): String =
        (ref.coverageFormat?.id ?: ref.format).replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "report" }

    // Two reports can want one name (a CLI flag beside a testo.php writer, or two of an unknown format).
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = name
        var index = 2
        while (!used.add(candidate)) candidate = "$stem-${index++}$extension"
        return candidate
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
