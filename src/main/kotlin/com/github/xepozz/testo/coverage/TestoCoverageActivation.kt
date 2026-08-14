package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.detectCoverageFormat
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads an already-written Testo coverage report into the IDE with no process launch (architecture §10): build a suite
 * bound to the report through our runner, tell it the format, and hand it to the platform, which reads the file via
 * [TestoCoverageRunner.loadCoverageData], opens the Coverage tool window and applies [TestoCoverageAnnotator].
 *
 * Returns false when the coverage module is absent (the runner is registered only by `coverage.xml`); the format falls
 * back to sniffing when unknown. Call on the EDT — `coverageGathered` opens UI.
 */
fun applyTestoCoverage(project: Project, name: String?, format: CoverageFormat?, dataFile: Path): Boolean {
    val runner = CoverageRunner.getInstance(TestoCoverageRunner::class.java) ?: return false
    val manager = CoverageDataManager.getInstance(project)
    val timestamp = runCatching { Files.getLastModifiedTime(dataFile).toMillis() }.getOrDefault(0L)
    val suite = manager.addCoverageSuite(
        name ?: "Testo coverage",
        // The File ctor is the one present on both 252 and 262 — Path was added only on 262.
        DefaultCoverageFileProvider(dataFile.toFile()),
        null,
        timestamp,
        null,
        runner,
        false,
        false,
    ) ?: return false
    // A TestoCoverageSuite carries the format the runner reads; if the platform handed back something else the runner
    // sniffs the file instead, so either way the load succeeds.
    (suite as? TestoCoverageSuite)?.format = format ?: detectCoverageFormat(dataFile) ?: CoverageFormat.CLOVER
    manager.coverageGathered(suite)
    return true
}
