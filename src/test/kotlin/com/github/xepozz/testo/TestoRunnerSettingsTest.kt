package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings
import junit.framework.TestCase

class TestoRunnerSettingsTest : TestCase() {

    fun testDefaults() {
        val settings = TestoRunnerSettings()
        assertEquals(-1, settings.dataProviderIndex)
        assertEquals(-1, settings.dataSetIndex)
        assertFalse(settings.parallelTestingEnabled)
        assertEquals("run", settings.command)
        assertTrue(settings.suites.isEmpty())
        assertTrue(settings.groups.isEmpty())
        assertTrue(settings.excludeGroups.isEmpty())
        assertEquals(1, settings.parallel)
        assertEquals("", settings.testoType)
        assertEquals("auto", settings.coverageLevel)
        assertEquals("--type=!bench", settings.coverageOptions)
        assertTrue("rerunFilters defaults to empty", settings.rerunFilters.isEmpty())
    }

    fun testCustomValues() {
        val settings = TestoRunnerSettings(
            dataProviderIndex = 3,
            dataSetIndex = 5,
            parallelTestingEnabled = true,
            command = "list",
            parallel = 4,
            testoType = "bench",
        ).apply {
            suites = mutableListOf("unit")
            groups = mutableListOf("fast")
            excludeGroups = mutableListOf("slow")
        }
        assertEquals(3, settings.dataProviderIndex)
        assertEquals(5, settings.dataSetIndex)
        assertTrue(settings.parallelTestingEnabled)
        assertEquals("list", settings.command)
        assertEquals(listOf("unit"), settings.suites)
        assertEquals(listOf("fast"), settings.groups)
        assertEquals(listOf("slow"), settings.excludeGroups)
        assertEquals(4, settings.parallel)
        assertEquals("bench", settings.testoType)
    }

    fun testFromPhpTestRunnerSettings_baseSettings() {
        val base = PhpTestRunnerSettings()
        base.scope = PhpTestRunnerSettings.Scope.File
        base.filePath = "/some/path/TestFile.php"
        base.methodName = "testSomething"
        base.directoryPath = "/some/dir"
        base.isUseAlternativeConfigurationFile = true
        base.configurationFilePath = "/config/testo.xml"
        base.testRunnerOptions = "--verbose"

        val result = TestoRunnerSettings.fromPhpTestRunnerSettings(base)

        assertEquals(PhpTestRunnerSettings.Scope.File, result.scope)
        assertEquals("/some/path/TestFile.php", result.filePath)
        assertEquals("testSomething", result.methodName)
        assertEquals("/some/dir", result.directoryPath)
        assertTrue(result.isUseAlternativeConfigurationFile)
        assertEquals("/config/testo.xml", result.configurationFilePath)
        assertEquals("--verbose", result.testRunnerOptions)
        // Testo-specific fields should be defaults
        assertEquals(-1, result.dataProviderIndex)
        assertEquals(-1, result.dataSetIndex)
        assertFalse(result.parallelTestingEnabled)
        assertEquals("run", result.command)
        assertTrue(result.suites.isEmpty())
        assertTrue(result.groups.isEmpty())
        assertTrue(result.excludeGroups.isEmpty())
        assertEquals(1, result.parallel)
        assertEquals("", result.testoType)
    }

    fun testFromPhpTestRunnerSettings_testoSettings() {
        val source = TestoRunnerSettings(
            dataProviderIndex = 2,
            dataSetIndex = 7,
            parallelTestingEnabled = true,
            command = "debug",
            parallel = 8,
            testoType = "inline",
            coverageLevel = "branch",
            coverageOptions = "--type=!bench",
        )
        source.suites = mutableListOf("integration")
        source.groups = mutableListOf("database")
        source.excludeGroups = mutableListOf("slow")
        source.scope = PhpTestRunnerSettings.Scope.Method
        source.filePath = "/test.php"
        source.methodName = "testIt"
        source.rerunFilters = listOf("\\Foo\\Bar::baz")

        val result = TestoRunnerSettings.fromPhpTestRunnerSettings(source)

        // Base settings
        assertEquals(PhpTestRunnerSettings.Scope.Method, result.scope)
        assertEquals("/test.php", result.filePath)
        assertEquals("testIt", result.methodName)
        // Testo-specific settings preserved
        assertEquals(2, result.dataProviderIndex)
        assertEquals(7, result.dataSetIndex)
        assertTrue(result.parallelTestingEnabled)
        assertEquals("debug", result.command)
        assertEquals(listOf("integration"), result.suites)
        assertEquals(listOf("database"), result.groups)
        assertEquals(listOf("slow"), result.excludeGroups)
        assertEquals(8, result.parallel)
        assertEquals("inline", result.testoType)
        assertEquals("branch", result.coverageLevel)
        assertEquals("--type=!bench", result.coverageOptions)
        // rerunFilters is @Transient and intentionally NOT copied — stays empty on the result.
        assertTrue("rerunFilters is not copied (transient)", result.rerunFilters.isEmpty())
    }
}
