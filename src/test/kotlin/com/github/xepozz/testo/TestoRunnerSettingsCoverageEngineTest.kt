package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Complements [TestoRunnerSettingsTest], which never asserts the coverageEngine field. fromPhpTestRunnerSettings copies
 * coverageEngine only when the source is a TestoRunnerSettings (TestoRunnerSettings.kt:58); a regression there would
 * otherwise pass CI. Pure logic, no IDE fixture.
 */
class TestoRunnerSettingsCoverageEngineTest {

    @Test
    fun defaultCoverageEngineIsXdebug() {
        assertEquals(CoverageEngine.XDEBUG, TestoRunnerSettings().coverageEngine)
    }

    @Test
    fun coverageEngineIsCopiedFromTestoSource() {
        val source = TestoRunnerSettings(coverageEngine = CoverageEngine.PCOV)
        val result = TestoRunnerSettings.fromPhpTestRunnerSettings(source)
        assertEquals(CoverageEngine.PCOV, result.coverageEngine)
    }

    @Test
    fun coverageEngineDefaultsWhenSourceIsPlainPhpSettings() {
        // A non-Testo source has no coverageEngine concept; the clone must fall back to the Testo default.
        val base = PhpTestRunnerSettings()
        val result = TestoRunnerSettings.fromPhpTestRunnerSettings(base)
        assertEquals(CoverageEngine.XDEBUG, result.coverageEngine)
    }
}
