package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope
import org.jdom.Element

/**
 * The XML a Testo run configuration is saved as — every runner field set, the IDE's own settings set — and how a
 * configuration saved in the pre-list shape (`group`, `exclude_group`, `suite` attributes) is read and written back.
 */
class PersistenceBaselineTest : BasePlatformTestCase() {

    private fun write(configuration: TestoRunConfiguration): String {
        val element = Element("configuration")
        configuration.writeExternal(element)
        return JDOMUtil.write(element)
    }

    private fun full(): TestoRunConfiguration = BaselineSettings.newConfiguration(project, "Full").also { configuration ->
        configuration.settings.commandLineSettings.apply {
            workingDirectory = "/work/app"
            envs.putAll(linkedMapOf("APP_ENV" to "test", "XDEBUG_MODE" to "off"))
            isPassParentEnvs = false
            setParameters("-d memory_limit=1G")
            interpreterSettings.interpreterName = "PHP 8.4 (Docker)"
        }
        configuration.testoSettings.getTestoRunnerSettings().apply {
            scope = Scope.Method
            selectedType = "Unit"
            directoryPath = "/work/app/tests"
            filePath = "/work/app/tests/CalcTest.php"
            methodName = "\\App\\CalcTest::med:3:0"
            isUseAlternativeConfigurationFile = true
            configurationFilePath = "/work/app/testo.php"
            testRunnerOptions = "-q --stop-on-failure"
            command = "list"
            testoType = "bench"
            suites = mutableListOf("Unit", "My, Suite")
            groups = mutableListOf("db", "a,b")
            excludeGroups = mutableListOf("slow")
            dataProviderIndex = 3
            dataSetIndex = 0
            coverageEngine = CoverageEngine.PCOV
            coverageClover = true
            coverageCobertura = false
            coverageXml = false
            coverageLevel = "path"
            coverageOptions = "--type=test"
            parallel = 4
            parallelTestingEnabled = true
            logHtml = false
            logJunit = true
        }
    }

    fun testSavedXml() {
        val out = StringBuilder()
        out.appendLine("== defaults")
        out.appendLine(write(BaselineSettings.newConfiguration(project, "Defaults")))
        out.appendLine("== every field")
        val full = full()
        out.appendLine(write(full))
        out.appendLine("== every field, read back and written again")
        val restored = BaselineSettings.newConfiguration(project, "Restored")
        restored.readExternal(JDOMUtil.load(write(full)))
        out.appendLine(write(restored))
        out.appendLine(Baselines.dump(restored.testoSettings.getTestoRunnerSettings()))
        Baselines.assertMatches("persistence", out.toString())
    }

    private fun find(element: Element, name: String): Element? =
        if (element.name == name) element else element.children.firstNotNullOfOrNull { find(it, name) }

    fun testLegacyShape() {
        val saved = JDOMUtil.load(write(full()))
        val runner = checkNotNull(find(saved, "TestoRunnerSettings")) { "No TestoRunnerSettings in the saved XML" }
        runner.children.filter { it.name in setOf("groups", "exclude_groups", "suites") }.forEach { runner.removeContent(it) }
        runner.setAttribute("group", "db, slow ,,x")
        runner.setAttribute("exclude_group", "flaky")
        runner.setAttribute("suite", "Unit, slow")
        val legacy = JDOMUtil.write(saved)

        val restored = BaselineSettings.newConfiguration(project, "Legacy")
        restored.readExternal(JDOMUtil.load(legacy))
        val out = StringBuilder()
        out.appendLine("== legacy input")
        out.appendLine(legacy)
        out.appendLine("== read")
        out.appendLine(Baselines.dump(restored.testoSettings.getTestoRunnerSettings()))
        out.appendLine("== written back")
        out.appendLine(write(restored))
        Baselines.assertMatches("persistence-legacy", out.toString())
    }
}
