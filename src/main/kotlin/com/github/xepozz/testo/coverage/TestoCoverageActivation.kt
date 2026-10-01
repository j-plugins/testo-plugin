package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.editor.TestoCoverageEditorHighlighter
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.detectCoverageFormat
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyKey
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** One already-written coverage report to load: how Testo announced it plus where it landed on this machine. */
data class TestoCoverageReport(val name: String?, val format: CoverageFormat?, val dataFile: Path)

/**
 * Loads already-written Testo coverage reports into the IDE with no process launch: one suite per
 * report, all in **one** [CoverageSuitesBundle] handed to [CoverageDataManager.chooseSuitesBundle] — the platform then
 * reads each file via [TestoCoverageRunner.loadCoverageData], merges the `ProjectData`s, opens the Coverage tool window
 * and applies [TestoCoverageAnnotator]. `chooseSuitesBundle` rather than `coverageGathered`: the bundle's composition
 * is the user's checkbox choice, not something the replace/merge option dialog should renegotiate.
 *
 * The suites are **not** registered with [CoverageDataManager] (no `addCoverageSuite`/`addExternalCoverageSuite`): those
 * persist into `workspace.xml`, and the platform's reload then crashes on Windows when the saved absolute report
 * path no longer exists — `readDataFileProviderAttribute` falls back to `Path.of(systemPath, absolutePath)`, which
 * throws `InvalidPathException` on the second drive letter and breaks *all* coverage init. A chosen-but-unregistered
 * bundle shows the same annotation, updates the per-test index, and never persists — the reports are transient anyway.
 *
 * The reports are read in the background first: `chooseSuitesBundle` loads each suite's data on the EDT, and the loader
 * resolves every source through the VFS. A suite keeps what it loaded, so the platform finds it there. Of several
 * applies in flight only the latest is handed over.
 *
 * Returns false when the coverage module is absent (the runner is registered only by `coverage.xml`) or no report was
 * given; the format falls back to sniffing when unknown.
 */
fun applyTestoCoverage(project: Project, reports: List<TestoCoverageReport>, runDir: Path? = null): Boolean {
    if (reports.isEmpty()) return false
    val runner = CoverageRunner.getInstance(TestoCoverageRunner::class.java) ?: return false
    val generation = APPLIES.getValue(project).incrementAndGet()
    ApplicationManager.getApplication().executeOnPooledThread {
        val suites = createSuites(project, runner, reports, runDir)
        if (suites.isEmpty()) return@executeOnPooledThread
        val manager = CoverageDataManager.getInstance(project)
        // Held until handed over: the suite keeps its data behind a soft reference only.
        val loaded = suites.map { it.getCoverageData(manager) }
        ApplicationManager.getApplication().invokeLater({
            if (APPLIES.getValue(project).get() != generation) return@invokeLater
            // Before the bundle is handed over, not after: the highlighter paints on `coverageDataCalculated`, and that
            // fires from inside chooseSuitesBundle. Its other install point, the annotator's onSuiteChosen, is not
            // reached on the first bundle of a session at all — the platform calls it only when a bundle is reloaded or closed.
            TestoCoverageEditorHighlighter.getInstance(project).install()
            manager.chooseSuitesBundle(CoverageSuitesBundle(suites.toTypedArray<CoverageSuite>()))
            Reference.reachabilityFence(loaded)
        }, project.disposed)
    }
    return true
}

private val APPLIES = NotNullLazyKey.createLazyKey<AtomicLong, Project>("testo.coverage.applies") { AtomicLong() }

private fun createSuites(project: Project, runner: CoverageRunner, reports: List<TestoCoverageReport>, runDir: Path?): List<TestoCoverageSuite> =
    reports.mapNotNull { report ->
        val timestamp = runCatching { Files.getLastModifiedTime(report.dataFile).toMillis() }.getOrDefault(0L)
        // The File ctor is the one present on both 252 and 262 — Path was added only on 262.
        val provider = DefaultCoverageFileProvider(report.dataFile.toFile())
        val suite = TestoCoverageEngine.INSTANCE
            .createCoverageSuite(report.name ?: "Testo coverage", project, runner, provider, timestamp) as? TestoCoverageSuite
            ?: return@mapNotNull null
        suite.format = report.format ?: detectCoverageFormat(report.dataFile) ?: CoverageFormat.CLOVER
        suite.runDir = runDir
        suite
    }

/**
 * Applies the shown Testo bundle again when it is [runDir]'s coverage, so the Coverage view is built anew: its columns
 * are fixed when it is built, and its cells read their numbers only as they repaint.
 */
fun reapplyTestoCoverage(project: Project, runDir: Path) {
    val bundle = CoverageDataManager.getInstance(project).activeSuites().firstOrNull { it.coverageEngine is TestoCoverageEngine } ?: return
    val suites = bundle.suites.filterIsInstance<TestoCoverageSuite>()
    if (suites.none { it.runDir == runDir }) return
    applyTestoCoverage(project, suites.map { TestoCoverageReport(it.presentableName, it.format, Path.of(it.coverageDataFileName)) }, runDir)
}

/** Closes the active Testo bundle, if any — the "no reports checked" state. */
fun closeTestoCoverage(project: Project) {
    APPLIES.getValue(project).incrementAndGet()
    val manager = CoverageDataManager.getInstance(project)
    manager.activeSuites().filter { it.coverageEngine is TestoCoverageEngine }.forEach { manager.closeSuitesBundle(it) }
}

/** Whether a Testo coverage bundle is currently applied. */
fun isTestoCoverageActive(project: Project): Boolean =
    CoverageDataManager.getInstance(project).activeSuites().any { it.coverageEngine is TestoCoverageEngine }
