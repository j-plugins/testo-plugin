package com.github.xepozz.testo.phpstorm.coverage

import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfigurationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoMutationProgramRunnerTest : BasePlatformTestCase() {
    fun testMutatingARerunOfFailedTestsKeepsItsFilters() {
        val rerun = TestoRunConfigurationType.INSTANCE.createTemplateConfiguration(project) as TestoRunConfiguration
        rerun.testoSettings.runnerSettings.apply {
            rerunFilters = listOf("\\App\\CalcTest::sum")
            scope = PhpTestRunnerSettings.Scope.ConfigurationFile
        }

        val mutated = TestoMutationProgramRunner().prepare(rerun).testoSettings.runnerSettings

        assertEquals(listOf("\\App\\CalcTest::sum"), mutated.rerunFilters)
        assertEquals(PhpTestRunnerSettings.Scope.ConfigurationFile, mutated.scope)
        assertTrue(mutated.coverageXml)
        assertTrue(mutated.logJunit)
    }
}
