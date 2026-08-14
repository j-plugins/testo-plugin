package com.github.xepozz.testo.coverage.format

import java.nio.file.Files
import java.nio.file.Path

/** Parses one report path (a file, or a directory for coverage-xml) into a format-neutral [ParsedReport]. */
interface TestoCoverageParser {
    val format: CoverageFormat
    fun parse(reportPath: Path): ParsedReport
}

/**
 * The report format, from the known `format` (announce / CLI flag) when given, else sniffed off the path shape and root
 * element. `null` only when the path is unreadable or nothing matches.
 */
fun detectCoverageFormat(reportPath: Path): CoverageFormat? {
    if (Files.isDirectory(reportPath)) return CoverageFormat.PHPUNIT_XML
    if (reportPath.fileName?.toString().equals("index.xml", ignoreCase = true)) return CoverageFormat.PHPUNIT_XML
    val root = try {
        readXmlRoot(reportPath)
    } catch (_: Exception) {
        return null
    }
    return when {
        root.tagName == "phpunit" -> CoverageFormat.PHPUNIT_XML
        root.tagName == "coverage" && (root.hasAttribute("line-rate") || root.childElements("packages").isNotEmpty()) ->
            CoverageFormat.COBERTURA
        root.tagName == "coverage" -> CoverageFormat.CLOVER
        else -> null
    }
}

/** Parses a report, using [format] when known and falling back to [detectCoverageFormat]. */
fun parseCoverageReport(reportPath: Path, format: CoverageFormat? = null): ParsedReport {
    val resolved = format ?: detectCoverageFormat(reportPath)
        ?: throw CoverageParseException("Cannot determine coverage format of $reportPath")
    val parser = when (resolved) {
        CoverageFormat.CLOVER -> CloverCoverageParser
        CoverageFormat.COBERTURA -> CoberturaCoverageParser
        CoverageFormat.PHPUNIT_XML -> PhpUnitXmlCoverageParser
    }
    return parser.parse(reportPath)
}
