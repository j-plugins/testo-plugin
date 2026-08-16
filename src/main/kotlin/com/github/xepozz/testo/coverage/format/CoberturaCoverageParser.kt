package com.github.xepozz.testo.coverage.format

import java.nio.file.Path

/**
 * Cobertura (`coverage-04`): a single file, line coverage plus branch coverage on decision lines. `filename` is
 * relative to a `<sources>/<source>` root (forward slashes); a branch line carries `condition-coverage="P% (a/b)"`.
 */
object CoberturaCoverageParser : TestoCoverageParser {
    override val format = CoverageFormat.COBERTURA

    private val CONDITION = Regex("""\((\d+)/(\d+)\)""")

    override fun parse(reportPath: Path): ParsedReport {
        val root = readXmlRoot(reportPath)
        val source = root.descendants("source").firstOrNull()?.textContent?.trim()?.trimEnd('/').orEmpty()
        var hasBranches = false
        // Distinct classes may name the same file; merge their lines under one resolved path.
        val byFile = LinkedHashMap<String, MutableList<LineCoverage>>()
        for (classEl in root.descendants("class")) {
            val filename = classEl.getAttribute("filename").ifBlank { continue }
            val path = if (source.isEmpty()) filename else "$source/$filename"
            val lines = byFile.getOrPut(path) { mutableListOf() }
            for (lineEl in classEl.descendants("line")) {
                val num = lineEl.getAttribute("number").toIntOrNull() ?: continue
                val hits = lineEl.getAttribute("hits").toIntOrNull() ?: 0
                val branch = if (lineEl.getAttribute("branch") == "true") {
                    CONDITION.find(lineEl.getAttribute("condition-coverage"))?.let {
                        hasBranches = true
                        BranchCoverage(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                    }
                } else null
                lines += LineCoverage(num, hits, branch)
            }
        }
        val files = byFile.map { (path, lines) -> FileCoverage(path, lines) }
        return ParsedReport(format, files, hasBranches, perTest = null)
    }
}
