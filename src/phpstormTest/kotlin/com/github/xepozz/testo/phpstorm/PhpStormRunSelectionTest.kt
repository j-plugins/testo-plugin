package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.phpstorm.baseline.Baselines
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope
import junit.framework.TestCase

/** The selection and the saved settings translate into each other with nothing lost either way. */
class PhpStormRunSelectionTest : TestCase() {

    private val everyField = TestoRunSelection(
        scope = TestoScope.METHOD,
        selectedType = "Unit",
        directoryPath = "/work/app/tests",
        filePath = "/work/app/tests/CalcTest.php",
        methodName = "\\App\\CalcTest::med:3:0",
        useAlternativeConfigurationFile = true,
        configurationFilePath = "/work/app/testo.php",
        testRunnerOptions = "-q --stop-on-failure",
        command = "list",
        testoType = "bench",
        suites = listOf("Unit", "My, Suite"),
        groups = listOf("db", "a,b"),
        excludeGroups = listOf("slow"),
        rerunFilters = listOf("\\App\\CalcTest::adds"),
        dataProviderIndex = 3,
        dataSetIndex = 0,
        coverageDriver = TestoCoverageDriver.PCOV,
        coverageClover = true,
        coverageCobertura = false,
        coverageXml = false,
        coverageLevel = "path",
        coverageOptions = "--type=test",
        parallel = 4,
        parallelTestingEnabled = true,
        logHtml = false,
        logJunit = true,
    )

    fun testEveryFieldSurvivesTheRoundTrip() {
        val settings = TestoRunnerSettings().apply { load(everyField) }

        assertEquals(everyField, settings.toSelection())
    }

    fun testSavedSettingsSurviveTheRoundTrip() {
        val settings = TestoRunnerSettings().apply { load(everyField) }
        val copy = TestoRunnerSettings().apply { load(settings.toSelection()) }

        assertEquals(Baselines.dump(settings), Baselines.dump(copy))
    }

    fun testDefaultsAgree() {
        assertEquals(TestoRunSelection().copy(scope = TestoRunnerSettings().scope.toTesto(), testRunnerOptions = TestoRunnerSettings().testRunnerOptions), TestoRunnerSettings().toSelection())
    }

    fun testLoadingLeavesThePreListFormsAlone() {
        val settings = TestoRunnerSettings().apply { legacyGroup = "db,slow" }
        settings.load(everyField)

        assertEquals("db,slow", settings.legacyGroup)
    }

    fun testEveryScopeAndDriverMapsBothWays() {
        for (scope in Scope.entries) assertEquals(scope, scope.toTesto().toPhpStorm())
        for (engine in CoverageEngine.entries) assertEquals(engine, engine.toTesto().toPhpStorm())
        assertEquals(Scope.entries.size, TestoScope.entries.size)
        assertEquals(CoverageEngine.entries.size, TestoCoverageDriver.entries.size)
    }
}
