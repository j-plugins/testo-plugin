package com.github.xepozz.testo.coverage.format

import org.w3c.dom.Element
import java.nio.file.Path

/**
 * Clover: a single file. `<file name>` is a host-absolute path with backslashes; both covered (`count>=1`) and
 * uncovered (`count=0`) executable lines are emitted. At branch level a decision line is `type="cond"` instead, with
 * `truecount`/`falsecount` holding its covered/uncovered edge counts and no `count` of its own.
 */
object CloverCoverageParser : TestoCoverageParser {
    override val format = CoverageFormat.CLOVER

    override fun parse(reportPath: Path): ParsedReport {
        val root = readXmlRoot(reportPath)
        var hasBranches = false
        val files = root.descendants("file").mapNotNull { fileEl ->
            val name = fileEl.getAttribute("name").ifBlank { return@mapNotNull null }
            val path = name.replace('\\', '/')
            val lines = fileEl.childElements("line").mapNotNull { lineEl ->
                val num = lineEl.getAttribute("num").toIntOrNull() ?: return@mapNotNull null
                val branch = if (lineEl.getAttribute("type") == "cond") conditionOf(lineEl) else null
                if (branch != null) hasBranches = true
                // Testo's counts are 0/1 anyway, so a taken edge says as much as a `count` would.
                val hits = lineEl.getAttribute("count").toIntOrNull() ?: branch?.let { if (it.covered > 0) 1 else 0 } ?: 0
                LineCoverage(num, hits, branch)
            }
            FileCoverage(path, lines)
        }
        return ParsedReport(format, files, hasBranches, perTest = null)
    }

    private fun conditionOf(lineEl: Element): BranchCoverage? {
        val covered = lineEl.getAttribute("truecount").toIntOrNull()?.coerceAtLeast(0) ?: return null
        val uncovered = lineEl.getAttribute("falsecount").toIntOrNull()?.coerceAtLeast(0) ?: return null
        return BranchCoverage(covered, covered + uncovered).takeIf { it.total > 0 }
    }
}
