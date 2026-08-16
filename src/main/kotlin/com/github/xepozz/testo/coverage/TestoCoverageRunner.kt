package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageParseException
import com.github.xepozz.testo.coverage.format.parseCoverageReport
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Loads a Testo coverage report into the platform model. Replaces the internal `com.intellij.php.coverage`
 * `PhpUnitCoverageRunner`; parsing is delegated to the format-neutral parsers ([parseCoverageReport]) and the model
 * built by [toProjectData]. The report's format is taken from the suite when known, else sniffed off the file.
 */
class TestoCoverageRunner : CoverageRunner() {
    override fun getId(): String = ID

    override fun getPresentableName(): String = "Testo"

    override fun getDataFileExtension(): String = "xml"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is TestoCoverageEngine

    // The File overload is overridable on both platforms; 262's Path overload delegates to it, 252 has only this one.
    override fun loadCoverageData(
        sessionDataFile: File,
        baseCoverageSuite: CoverageSuite?,
        reporter: CoverageLoadErrorReporter,
    ): CoverageLoadingResult {
        val suite = baseCoverageSuite as? TestoCoverageSuite
        return try {
            val report = parseCoverageReport(sessionDataFile.toPath(), suite?.format)
            suite?.project?.let { TestoCoverageByTestIndex.getInstance(it).update(report.perTest) }
            // Key each ClassData by the resolved VirtualFile path so it matches how TestoCoverageAnnotator looks files up.
            val lfs = LocalFileSystem.getInstance()
            val resolvePath = { path: String -> lfs.findFileByPath(path)?.path ?: path }
            val projectData = report.toProjectData(resolvePath)
            val lineTotals = report.files.mapNotNull { file -> file.totals?.let { resolvePath(file.filePath) to it } }
            suite?.applyParsed(report.hasBranches, lineTotals.toMap())
            LOG.info("Testo coverage loaded: ${report.format} ${projectData.classes.size} files from $sessionDataFile")
            SuccessCoverageLoadingResult(projectData)
        } catch (e: Exception) {
            LOG.warn("Failed to load Testo coverage from $sessionDataFile", e)
            reporter.reportError(e)
            FailedCoverageLoadingResult(e, true)
        }
    }

    companion object {
        const val ID: String = "TestoCoverageRunner"
        private val LOG = Logger.getInstance(TestoCoverageRunner::class.java)
    }
}
