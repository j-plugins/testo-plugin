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
    }

    fun testCustomValues() {
        val settings = TestoRunnerSettings(
            dataProviderIndex = 3,
            dataSetIndex = 5,
            parallelTestingEnabled = true,
        )
        assertEquals(3, settings.dataProviderIndex)
        assertEquals(5, settings.dataSetIndex)
        assertTrue(settings.parallelTestingEnabled)
    }

    fun testFromPhpTestRunnerSettings() {
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
    }
}
