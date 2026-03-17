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
        assertEquals("", settings.group)
        assertEquals("", settings.excludeGroup)
        assertEquals(0, settings.repeat)
        assertEquals(0, settings.parallel)
    }

    fun testCustomValues() {
        val settings = TestoRunnerSettings(
            dataProviderIndex = 3,
            dataSetIndex = 5,
            parallelTestingEnabled = true,
            command = "list",
            suite = "unit",
            group = "fast",
            excludeGroup = "slow",
            repeat = 3,
            parallel = 4,
        )
        assertEquals(3, settings.dataProviderIndex)
        assertEquals(5, settings.dataSetIndex)
        assertTrue(settings.parallelTestingEnabled)
        assertEquals("list", settings.command)
        assertEquals("unit", settings.suite)
        assertEquals("fast", settings.group)
        assertEquals("slow", settings.excludeGroup)
        assertEquals(3, settings.repeat)
        assertEquals(4, settings.parallel)
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
        assertEquals("", result.group)
        assertEquals("", result.excludeGroup)
        assertEquals(0, result.repeat)
        assertEquals(0, result.parallel)
    }

    fun testFromPhpTestRunnerSettings_testoSettings() {
        val source = TestoRunnerSettings(
            dataProviderIndex = 2,
            dataSetIndex = 7,
            parallelTestingEnabled = true,
            command = "debug",
            suite = "integration",
            group = "database",
            excludeGroup = "slow",
            repeat = 5,
            parallel = 8,
        )
        source.scope = PhpTestRunnerSettings.Scope.Method
        source.filePath = "/test.php"
        source.methodName = "testIt"

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
        assertEquals("database", result.group)
        assertEquals("slow", result.excludeGroup)
        assertEquals(5, result.repeat)
        assertEquals(8, result.parallel)
    }
}
