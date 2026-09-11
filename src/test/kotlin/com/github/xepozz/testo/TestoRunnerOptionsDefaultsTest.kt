package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings
import junit.framework.TestCase

class TestoRunnerOptionsDefaultsTest : TestCase() {

    fun testDefaultConstant() {
        assertEquals("-q -n --teamcity", TestoRunnerSettings.DEFAULT_TEST_RUNNER_OPTIONS)
    }

    fun testEffectiveOptions_blankUsesDefault() {
        assertEquals(
            TestoRunnerSettings.DEFAULT_TEST_RUNNER_OPTIONS,
            TestoRunnerSettings.effectiveTestRunnerOptions(null),
        )
        assertEquals(
            TestoRunnerSettings.DEFAULT_TEST_RUNNER_OPTIONS,
            TestoRunnerSettings.effectiveTestRunnerOptions(""),
        )
        assertEquals(
            TestoRunnerSettings.DEFAULT_TEST_RUNNER_OPTIONS,
            TestoRunnerSettings.effectiveTestRunnerOptions("   "),
        )
    }

    fun testEffectiveOptions_keepsExplicitValue() {
        assertEquals("-q --teamcity", TestoRunnerSettings.effectiveTestRunnerOptions("-q --teamcity"))
    }

    fun testFromPhpSettings_fillsBlankOptions() {
        val base = PhpTestRunnerSettings()
        // Platform default is empty; converting into TestoRunnerSettings must install the IDE defaults.
        val result = TestoRunnerSettings.fromPhpTestRunnerSettings(base)
        assertEquals(TestoRunnerSettings.DEFAULT_TEST_RUNNER_OPTIONS, result.testRunnerOptions)
    }
}
