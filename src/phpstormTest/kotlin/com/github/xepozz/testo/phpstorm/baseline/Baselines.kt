package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.baseline.BaselineFiles
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings

/** The PhpStorm side of the baselines: the saved runner settings, field by field. See the core [BaselineFiles]. */
object Baselines {
    fun assertMatches(name: String, actual: String) = BaselineFiles.assertMatches(name, actual)

    /** Every persisted field of the runner settings, one per line, in a fixed order. */
    fun dump(settings: TestoRunnerSettings): String = buildString {
        appendLine("  scope=${settings.scope}")
        appendLine("  selectedType=${settings.selectedType}")
        appendLine("  directoryPath=${settings.directoryPath}")
        appendLine("  filePath=${settings.filePath}")
        appendLine("  methodName=${settings.methodName}")
        appendLine("  useAlternativeConfigurationFile=${settings.isUseAlternativeConfigurationFile}")
        appendLine("  configurationFilePath=${settings.configurationFilePath}")
        appendLine("  testRunnerOptions=${settings.testRunnerOptions}")
        appendLine("  command=${settings.command}")
        appendLine("  testoType=${settings.testoType}")
        appendLine("  suites=${settings.suites}")
        appendLine("  groups=${settings.groups}")
        appendLine("  excludeGroups=${settings.excludeGroups}")
        appendLine("  legacy=[${settings.legacyGroup}|${settings.legacyExcludeGroup}|${settings.legacySuite}]")
        appendLine("  rerunFilters=${settings.rerunFilters}")
        appendLine("  dataProviderIndex=${settings.dataProviderIndex}")
        appendLine("  dataSetIndex=${settings.dataSetIndex}")
        appendLine("  coverageEngine=${settings.coverageEngine}")
        appendLine("  coverage=[clover=${settings.coverageClover}, cobertura=${settings.coverageCobertura}, xml=${settings.coverageXml}]")
        appendLine("  coverageLevel=${settings.coverageLevel}")
        appendLine("  coverageOptions=${settings.coverageOptions}")
        appendLine("  parallel=${settings.parallel} enabled=${settings.parallelTestingEnabled}")
        append("  logs=[html=${settings.logHtml}, junit=${settings.logJunit}]")
    }
}
