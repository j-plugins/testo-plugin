package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.editor.TestoCoverageEditorHighlighter
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.detectCoverageFormat
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

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
 * Returns false when the coverage module is absent (the runner is registered only by `coverage.xml`) or no report was
 * given; the format falls back to sniffing when unknown. Call on the EDT — `chooseSuitesBundle` opens UI.
 */
fun applyTestoCoverage(project: Project, reports: List<TestoCoverageReport>): Boolean {
    if (reports.isEmpty()) return false
    val runner = CoverageRunner.getInstance(TestoCoverageRunner::class.java) ?: return false
    val suites = reports.mapNotNull { report ->
        val timestamp = runCatching { Files.getLastModifiedTime(report.dataFile).toMillis() }.getOrDefault(0L)
        // The File ctor is the one present on both 252 and 262 — Path was added only on 262.
        val provider = DefaultCoverageFileProvider(report.dataFile.toFile())
        val suite = TestoCoverageEngine.INSTANCE
            .createCoverageSuite(report.name ?: "Testo coverage", project, runner, provider, timestamp) as? TestoCoverageSuite
            ?: return@mapNotNull null
        suite.format = report.format ?: detectCoverageFormat(report.dataFile) ?: CoverageFormat.CLOVER
        suite
    }
    if (suites.isEmpty()) return false
    // Before the bundle is handed over, not after: the highlighter paints on `coverageDataCalculated`, and that fires
    // from inside chooseSuitesBundle. Its other install point, the annotator's onSuiteChosen, is not reached on the
    // first bundle of a session at all — the platform calls it only when a bundle is reloaded or closed.
    TestoCoverageEditorHighlighter.getInstance(project).install()
    CoverageDataManager.getInstance(project).chooseSuitesBundle(CoverageSuitesBundle(suites.toTypedArray<CoverageSuite>()))
    return true
}

/** Closes the active Testo bundle, if any — the "no reports checked" state. */
fun closeTestoCoverage(project: Project) {
    val manager = CoverageDataManager.getInstance(project)
    manager.activeSuites().filter { it.coverageEngine is TestoCoverageEngine }.forEach { manager.closeSuitesBundle(it) }
}

/** Whether a Testo coverage bundle is currently applied. */
fun isTestoCoverageActive(project: Project): Boolean =
    CoverageDataManager.getInstance(project).activeSuites().any { it.coverageEngine is TestoCoverageEngine }
