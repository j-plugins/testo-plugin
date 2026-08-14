package com.github.xepozz.testo.coverage.format

import java.nio.file.Path

/**
 * Clover: a single file, line coverage only. `<file name>` is a host-absolute path with backslashes; both covered
 * (`count>=1`) and uncovered (`count=0`) executable lines are emitted. No branch data. See report-formats §1.
 */
object CloverCoverageParser : TestoCoverageParser {
    override val format = CoverageFormat.CLOVER

    override fun parse(reportPath: Path): ParsedReport {
        val root = readXmlRoot(reportPath)
        val files = root.descendants("file").mapNotNull { fileEl ->
            val name = fileEl.getAttribute("name").ifBlank { return@mapNotNull null }
            val path = name.replace('\\', '/')
            val lines = fileEl.childElements("line").mapNotNull { lineEl ->
                val num = lineEl.getAttribute("num").toIntOrNull() ?: return@mapNotNull null
                val hits = lineEl.getAttribute("count").toIntOrNull() ?: 0
                LineCoverage(num, hits)
            }
            FileCoverage(path, lines)
        }
        return ParsedReport(format, files, hasBranches = false, perTest = null)
    }
}
