package com.github.xepozz.testo.launch

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.intellij.execution.configurations.ParametersList

/** What a Coverage run adds to the Testo command beyond an ordinary run. */
object TestoCoverageArguments {

    /** The enabled formats with the local file/directory each one writes, derived from the IDE-managed base path. */
    fun flagLocalPaths(selection: TestoRunSelection, localCoverage: String?): List<Pair<CoverageFormat, String>> {
        if (localCoverage.isNullOrEmpty()) return emptyList()
        val stem = localCoverage.removeSuffix(".xml")
        return buildList {
            if (selection.coverageClover) add(CoverageFormat.CLOVER to "$stem-clover.xml")
            if (selection.coverageCobertura) add(CoverageFormat.COBERTURA to "$stem-cobertura.xml")
            if (selection.coverageXml) add(CoverageFormat.COVERAGE_XML to "$stem-coverage-xml")
        }
    }

    /**
     * One flag per format, pointed at [targetPaths] (the same reports as the interpreter sees them). No base path or
     * every report unchecked: a bare `--coverage` still makes any testo.php-configured writer collect, and the announce
     * path picks the reports up.
     */
    fun reportArguments(flags: List<Pair<CoverageFormat, String>>, targetPaths: List<String>): List<String> = when {
        flags.isEmpty() -> listOf("--coverage")
        else -> flags.zip(targetPaths) { (format, _), target -> flagFor(format, target) }
    }

    /**
     * The analysis level and the configuration's coverage-only options — everything a Coverage run adds beyond the
     * report flags.
     */
    fun extraArguments(selection: TestoRunSelection): List<String> = buildList {
        level(selection)?.let { add("--coverage-level=$it") }
        addAll(ParametersList.parse(selection.coverageOptions))
    }

    /**
     * The `--coverage-level` to send, or null for none. An explicit choice wins. On *auto* the level is normally left
     * to testo.php — except when branch coverage is both achievable and carried: the Xdebug engine (PCOV collects
     * lines only) together with a Cobertura or Clover report (the formats that store branch data). Then auto means
     * branch.
     */
    fun level(selection: TestoRunSelection): String? {
        val level = selection.coverageLevel.trim()
        if (level.isNotEmpty() && level != TestoRunSelection.COVERAGE_LEVEL_AUTO) return level
        val carriesBranches = selection.coverageCobertura || selection.coverageClover
        if (selection.coverageDriver == TestoCoverageDriver.XDEBUG && carriesBranches) return "branch"
        return null
    }

    fun flagFor(format: CoverageFormat, targetCoverage: String): String = when (format) {
        CoverageFormat.CLOVER -> "--coverage-clover=$targetCoverage"
        CoverageFormat.COBERTURA -> "--coverage-cobertura=$targetCoverage"
        CoverageFormat.COVERAGE_XML -> "--coverage-xml=$targetCoverage"
    }
}
