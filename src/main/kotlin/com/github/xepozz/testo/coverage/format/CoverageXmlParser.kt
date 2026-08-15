package com.github.xepozz.testo.coverage.format

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * coverage-xml: a directory — an `index.xml` overview plus one XML per source file, in PHPUnit's coverage schema (the
 * root element is still `<phpunit>`). Only executed lines that have covering tests are emitted (a per-test overlay, no
 * uncovered lines), so the per-file `<totals>` is what states a file's line count. Source file for a `<file href>` entry
 * is `<project source>/<href without .xml>`; the per-file XML sits at `<indexDir>/<href>`. See report-formats §3.
 */
object CoverageXmlParser : TestoCoverageParser {
    override val format = CoverageFormat.COVERAGE_XML

    override fun parse(reportPath: Path): ParsedReport {
        val indexPath = if (Files.isDirectory(reportPath)) reportPath.resolve("index.xml") else reportPath
        val indexDir = indexPath.parent ?: indexPath
        val indexRoot = readXmlRoot(indexPath)
        val source = indexRoot.descendants("project").firstOrNull()?.getAttribute("source")
            ?.trim()?.trimEnd('/').orEmpty()

        val files = mutableListOf<FileCoverage>()
        val byTest = LinkedHashMap<TestId, MutableSet<SourceLine>>()
        val byLine = LinkedHashMap<SourceLine, MutableSet<TestId>>()

        for (entry in indexRoot.descendants("file")) {
            val href = entry.getAttribute("href").ifBlank { continue }
            val perFilePath = indexDir.resolve(href)
            if (!Files.exists(perFilePath)) continue
            val path = "$source/${href.removeSuffix(".xml")}"
            val fileRoot = readXmlRoot(perFilePath)
            val coverage = fileRoot.descendants("coverage").firstOrNull()

            val lines = mutableListOf<LineCoverage>()
            for (lineEl in coverage?.childElements("line").orEmpty()) {
                val nr = lineEl.getAttribute("nr").toIntOrNull() ?: continue
                lines += LineCoverage(nr, hits = 1)
                val ref = SourceLine(path, nr)
                for (coveredEl in lineEl.childElements("covered")) {
                    val testId = TestId.parse(coveredEl.getAttribute("by")) ?: continue
                    byTest.getOrPut(testId) { linkedSetOf() } += ref
                    byLine.getOrPut(ref) { linkedSetOf() } += testId
                }
            }
            files += FileCoverage(path, lines, readTotals(fileRoot))
        }

        val perTest = PerTestCoverage(byTest.mapValues { it.value.toSet() }, byLine.mapValues { it.value.toSet() })
        return ParsedReport(format, files, hasBranches = false, perTest = perTest)
    }

    private fun readTotals(fileRoot: Element): LineTotals? {
        val lines = fileRoot.descendants("totals").firstOrNull()?.childElements("lines")?.firstOrNull() ?: return null
        val total = lines.getAttribute("total").toIntOrNull() ?: return null
        val executed = lines.getAttribute("executed").toIntOrNull() ?: return null
        return LineTotals(total, executed.coerceAtMost(total))
    }
}
