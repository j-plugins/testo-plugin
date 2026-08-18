package com.github.xepozz.testo.coverage.format

/**
 * The three coverage report shapes Testo's `plugin/codecov` writes, identified by the `format=` attribute of the
 * `##teamcity[testoReport …]` announce (and by the CLI flag that produced them).
 */
enum class CoverageFormat(val id: String) {
    CLOVER("clover"),
    COBERTURA("cobertura"),
    COVERAGE_XML("coverage-xml");

    companion object {
        fun fromId(id: String?): CoverageFormat? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** A covering test: a `\`-qualified class plus a method, as spelled in coverage-xml `<covered by="FQCN::method">`. */
data class TestId(val fqcn: String, val method: String) {
    companion object {
        /** Splits on the last `::`; the namespace separator `\` in [fqcn] is kept verbatim. `null` if malformed. */
        fun parse(reference: String): TestId? {
            val sep = reference.lastIndexOf("::")
            if (sep < 0) return null
            val fqcn = reference.substring(0, sep)
            val method = reference.substring(sep + 2)
            if (fqcn.isEmpty() || method.isEmpty()) return null
            return TestId(fqcn, method)
        }
    }
}

/** A source location as it comes off a report: the file path is resolved+forward-slashed, not yet the platform key. */
data class SourceLine(val filePath: String, val line: Int)

/** `condition-coverage="P% (covered/total)"` from Cobertura. Identity of *which* branches is unknown. */
data class BranchCoverage(val covered: Int, val total: Int)

data class LineCoverage(val line: Int, val hits: Int, val branch: BranchCoverage? = null)

/**
 * A file's own line tally, reported by coverage-xml even for files it lists no covered line for. It is the only place
 * that format states how many executable lines a file has: [LineCoverage] covers the executed ones alone.
 */
data class LineTotals(val total: Int, val executed: Int)

/** One source file's coverage. [filePath] is the resolved absolute path, forward-slashed, ready to normalize. */
data class FileCoverage(val filePath: String, val lines: List<LineCoverage>, val totals: LineTotals? = null)

/**
 * The per-test overlay carried only by coverage-xml: which tests touched which source lines, both directions. Keyed by
 * the same resolved path as [FileCoverage.filePath].
 */
data class PerTestCoverage(
    val byTest: Map<TestId, Set<SourceLine>>,
    val byLine: Map<SourceLine, Set<TestId>>,
) {
    companion object {
        val EMPTY = PerTestCoverage(emptyMap(), emptyMap())
    }
}

/** The parsed report, format-neutral. Turned into a platform `ProjectData` by the coverage runner. */
data class ParsedReport(
    val format: CoverageFormat,
    val files: List<FileCoverage>,
    val hasBranches: Boolean,
    val perTest: PerTestCoverage?,
)

class CoverageParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
