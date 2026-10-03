package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope

/**
 * How a configuration names itself for the shapes the producer and the editor make. Its validation is left out: the
 * platform checks the machine's PHP interpreter first, so the answer depends on the machine running the test.
 */
class NamesBaselineTest : BasePlatformTestCase() {

    private fun StringBuilder.case(label: String, setup: TestoRunnerSettings.() -> Unit) {
        val configuration = BaselineSettings.newConfiguration(project, "Baseline")
        configuration.testoSettings.getTestoRunnerSettings().setup()
        appendLine("== $label")
        appendLine("  suggestedName=${configuration.suggestedName()}")
        appendLine("  actionName=${configuration.actionName}")
    }

    fun testNames() {
        val out = StringBuilder()
        out.case("one group") { scope = Scope.ConfigurationFile; groups = mutableListOf("db") }
        out.case("two groups") { scope = Scope.ConfigurationFile; groups = mutableListOf("db", "slow") }
        out.case("one suite") { scope = Scope.ConfigurationFile; suites = mutableListOf("Unit") }
        out.case("two suites") { scope = Scope.ConfigurationFile; suites = mutableListOf("Unit", "My Suite") }
        out.case("type only") { scope = Scope.ConfigurationFile; testoType = "bench" }
        out.case("groups win over suites and type") {
            scope = Scope.ConfigurationFile; groups = mutableListOf("db"); suites = mutableListOf("Unit"); testoType = "test"
        }
        out.case("excluded groups only") { scope = Scope.ConfigurationFile; excludeGroups = mutableListOf("slow") }
        out.case("rerun filters only") { scope = Scope.ConfigurationFile; rerunFilters = listOf("\\App\\CalcTest::adds") }
        out.case("explicit configuration file with a group") {
            scope = Scope.ConfigurationFile
            isUseAlternativeConfigurationFile = true
            configurationFilePath = "/work/app/testo.php"
            groups = mutableListOf("db")
        }
        out.case("configuration scope, nothing selected") { scope = Scope.ConfigurationFile }
        out.case("qualified selector") {
            scope = Scope.Method; filePath = "/work/app/tests/Calculator.php"; methodName = "\\Ns\\Calculator::med:3:0"
        }
        out.case("plain method") { scope = Scope.Method; filePath = "/work/app/tests/CalcTest.php"; methodName = "adds" }
        out.case("file") { scope = Scope.File; filePath = "/work/app/tests/CalcTest.php" }
        out.case("directory") { scope = Scope.Directory; directoryPath = "/work/app/tests" }
        out.case("type scope") { scope = Scope.Type; selectedType = "Unit" }
        Baselines.assertMatches("names", out.toString())
    }
}
