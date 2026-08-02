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
        assertEquals("", settings.suite)
        assertTrue(settings.groups.isEmpty())
        assertTrue(settings.excludeGroups.isEmpty())
        assertEquals(0, settings.repeat)
        assertEquals(0, settings.parallel)
        assertEquals("", settings.testoType)
        assertTrue("rerunFilters defaults to empty", settings.rerunFilters.isEmpty())
    }

    fun testCustomValues() {
        val settings = TestoRunnerSettings(
            dataProviderIndex = 3,
            dataSetIndex = 5,
            parallelTestingEnabled = true,
            command = "list",
            suite = "unit",
            repeat = 3,
            parallel = 4,
            testoType = "bench",
        ).apply {
            groups = mutableListOf("fast")
            excludeGroups = mutableListOf("slow")
        }
        assertEquals(3, settings.dataProviderIndex)
        assertEquals(5, settings.dataSetIndex)
        assertTrue(settings.parallelTestingEnabled)
        assertEquals("list", settings.command)
        assertEquals("unit", settings.suite)
        assertEquals(listOf("fast"), settings.groups)
        assertEquals(listOf("slow"), settings.excludeGroups)
        assertEquals(3, settings.repeat)
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
        assertEquals("", result.suite)
        assertTrue(result.groups.isEmpty())
        assertTrue(result.excludeGroups.isEmpty())
        assertEquals(0, result.repeat)
        assertEquals(0, result.parallel)
        assertEquals("", result.testoType)
    }

    fun testFromPhpTestRunnerSettings_testoSettings() {
        val source = TestoRunnerSettings(
            dataProviderIndex = 2,
            dataSetIndex = 7,
            parallelTestingEnabled = true,
            command = "debug",
            suite = "integration",
            repeat = 5,
            parallel = 8,
            testoType = "inline",
        )
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
        assertEquals("integration", result.suite)
        assertEquals(listOf("database"), result.groups)
        assertEquals(listOf("slow"), result.excludeGroups)
        assertEquals(5, result.repeat)
        assertEquals(8, result.parallel)
        assertEquals("inline", result.testoType)
        // rerunFilters is @Transient and intentionally NOT copied — stays empty on the result.
        assertTrue("rerunFilters is not copied (transient)", result.rerunFilters.isEmpty())
    }
}
