package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope

/** The saved settings as a [TestoRunSelection]. The pre-list `group`/`exclude_group`/`suite` forms are not part of it. */
fun TestoRunnerSettings.toSelection() = TestoRunSelection(
    scope = scope.toTesto(),
    selectedType = selectedType,
    directoryPath = directoryPath,
    filePath = filePath,
    methodName = methodName,
    useAlternativeConfigurationFile = isUseAlternativeConfigurationFile,
    configurationFilePath = configurationFilePath,
    testRunnerOptions = testRunnerOptions,
    command = command,
    testoType = testoType,
    suites = suites.toList(),
    groups = groups.toList(),
    excludeGroups = excludeGroups.toList(),
    rerunFilters = rerunFilters.toList(),
    dataProviderIndex = dataProviderIndex,
    dataSetIndex = dataSetIndex,
    coverageDriver = coverageEngine.toTesto(),
    coverageClover = coverageClover,
    coverageCobertura = coverageCobertura,
    coverageXml = coverageXml,
    coverageLevel = coverageLevel,
    coverageOptions = coverageOptions,
    parallel = parallel,
    parallelTestingEnabled = parallelTestingEnabled,
    logHtml = logHtml,
    logJunit = logJunit,
    infectionScope = infectionScope,
    infectionGitDiffBase = infectionGitDiffBase,
    infectionThreads = infectionThreads,
    infectionOnlyCoveringTestCases = infectionOnlyCoveringTestCases,
    infectionWithUncovered = infectionWithUncovered,
    infectionTimeoutsAsEscaped = infectionTimeoutsAsEscaped,
    infectionMutators = infectionMutators,
    infectionStaticAnalysisTool = infectionStaticAnalysisTool,
    infectionOptions = infectionOptions,
)

/** Writes [selection] into these settings; the pre-list forms are left as they are. */
fun TestoRunnerSettings.load(selection: TestoRunSelection) {
    scope = selection.scope.toPhpStorm()
    selectedType = selection.selectedType
    directoryPath = selection.directoryPath
    filePath = selection.filePath
    methodName = selection.methodName
    isUseAlternativeConfigurationFile = selection.useAlternativeConfigurationFile
    configurationFilePath = selection.configurationFilePath
    testRunnerOptions = selection.testRunnerOptions
    command = selection.command
    testoType = selection.testoType
    suites = selection.suites.toMutableList()
    groups = selection.groups.toMutableList()
    excludeGroups = selection.excludeGroups.toMutableList()
    rerunFilters = selection.rerunFilters.toList()
    dataProviderIndex = selection.dataProviderIndex
    dataSetIndex = selection.dataSetIndex
    coverageEngine = selection.coverageDriver.toPhpStorm()
    coverageClover = selection.coverageClover
    coverageCobertura = selection.coverageCobertura
    coverageXml = selection.coverageXml
    coverageLevel = selection.coverageLevel
    coverageOptions = selection.coverageOptions
    parallel = selection.parallel
    parallelTestingEnabled = selection.parallelTestingEnabled
    logHtml = selection.logHtml
    logJunit = selection.logJunit
    infectionScope = selection.infectionScope
    infectionGitDiffBase = selection.infectionGitDiffBase
    infectionThreads = selection.infectionThreads
    infectionOnlyCoveringTestCases = selection.infectionOnlyCoveringTestCases
    infectionWithUncovered = selection.infectionWithUncovered
    infectionTimeoutsAsEscaped = selection.infectionTimeoutsAsEscaped
    infectionMutators = selection.infectionMutators
    infectionStaticAnalysisTool = selection.infectionStaticAnalysisTool
    infectionOptions = selection.infectionOptions
}

fun Scope.toTesto(): TestoScope = when (this) {
    Scope.Type -> TestoScope.TYPE
    Scope.Directory -> TestoScope.DIRECTORY
    Scope.File -> TestoScope.FILE
    Scope.Method -> TestoScope.METHOD
    Scope.ConfigurationFile -> TestoScope.CONFIGURATION_FILE
}

fun TestoScope.toPhpStorm(): Scope = when (this) {
    TestoScope.TYPE -> Scope.Type
    TestoScope.DIRECTORY -> Scope.Directory
    TestoScope.FILE -> Scope.File
    TestoScope.METHOD -> Scope.Method
    TestoScope.CONFIGURATION_FILE -> Scope.ConfigurationFile
}

fun CoverageEngine.toTesto(): TestoCoverageDriver = when (this) {
    CoverageEngine.XDEBUG -> TestoCoverageDriver.XDEBUG
    CoverageEngine.PCOV -> TestoCoverageDriver.PCOV
    CoverageEngine.PHPDBG -> TestoCoverageDriver.PHPDBG
}

fun TestoCoverageDriver.toPhpStorm(): CoverageEngine = when (this) {
    TestoCoverageDriver.XDEBUG -> CoverageEngine.XDEBUG
    TestoCoverageDriver.PCOV -> CoverageEngine.PCOV
    TestoCoverageDriver.PHPDBG -> CoverageEngine.PHPDBG
}
