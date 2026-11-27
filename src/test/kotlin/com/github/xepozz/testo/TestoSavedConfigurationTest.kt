package com.github.xepozz.testo

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoLaunchSettings
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoSavedConfiguration
import com.github.xepozz.testo.launch.TestoScope
import com.intellij.openapi.util.JDOMUtil
import junit.framework.TestCase
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/** The saved XML, checked against what the serializer of `TestoRunnerSettings` wrote into the persistence baselines. */
class TestoSavedConfigurationTest : TestCase() {

    /** The XML of the section after the `== [title]` line of a baseline. */
    private fun baselineXml(name: String, title: String): String {
        val lines = Files.readAllLines(Path.of("src/test/testData/baseline/$name.txt"))
        val start = lines.indexOf("== $title") + 1
        assertTrue("No section '$title' in $name", start > 0)
        val end = lines.subList(start, lines.size).indexOfFirst { it == "</configuration>" } + start
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun rewrite(saved: TestoSavedConfiguration): String =
        JDOMUtil.write(Element("configuration").also(saved::writeTo))

    fun testTheBaselineXmlIsReadAndWrittenBackUnchanged() {
        val xml = baselineXml("persistence", "every field")
        val saved = TestoSavedConfiguration.readFrom(JDOMUtil.load(xml))

        assertEquals(
            TestoRunSelection(
                scope = TestoScope.METHOD,
                selectedType = "Unit",
                directoryPath = "/work/app/tests",
                filePath = "/work/app/tests/CalcTest.php",
                methodName = "\\App\\CalcTest::med:3:0",
                useAlternativeConfigurationFile = true,
                configurationFilePath = "/work/app/testo.php",
                testRunnerOptions = "-q --stop-on-failure",
                command = "list",
                testoTypes = listOf("bench"),
                suites = listOf("Unit", "My, Suite"),
                groups = listOf("db", "a,b"),
                excludeGroups = listOf("slow"),
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
                infectionScope = "git-lines",
                infectionGitDiffBase = "main",
                infectionThreads = "4",
                infectionOnlyCoveringTestCases = true,
                infectionWithUncovered = true,
                infectionTimeoutsAsEscaped = true,
                infectionMutators = "@default",
                infectionStaticAnalysisTool = "phpstan",
                infectionOptions = "--debug",
            ),
            saved.selection,
        )
        assertEquals(
            TestoLaunchSettings(
                binaryPath = "/opt/testo/bin/testo",
                workingDirectory = "/work/app",
                environmentVariables = mapOf("APP_ENV" to "test", "XDEBUG_MODE" to "off"),
                passParentEnvironment = false,
                interpreterId = "compose-id",
                interpreterName = "PHP 8.4 (Docker)",
                interpreterOptions = "-d memory_limit=1G",
            ),
            saved.launch,
        )
        assertEquals(xml, rewrite(saved))
    }

    fun testTheSameValuesWrittenFromScratchGiveTheSameXml() {
        val xml = baselineXml("persistence", "every field")
        val read = TestoSavedConfiguration.readFrom(JDOMUtil.load(xml))

        assertEquals(xml, rewrite(TestoSavedConfiguration(read.selection, read.launch)))
    }

    fun testAnotherBuildsInterpreterIsKeptButNotTakenWithoutAnInterpreterChosenHere() {
        val xml = baselineXml("persistence", "every field").replace(""" interpreter_id="compose-id"""", "")
        val read = TestoSavedConfiguration.readFrom(JDOMUtil.load(xml))
        assertNull(read.launch.interpreterName)
        assertEquals("-d memory_limit=1G", read.launch.interpreterOptions)

        assertEquals(xml, rewrite(read))
        val chosen = rewrite(read.with(read.selection, read.launch.copy(interpreterId = "local-id", interpreterName = "Local")))
        assertTrue(chosen, chosen.contains("""<option name="interpreterName" value="Local" />"""))
        assertTrue(chosen, chosen.contains("""interpreter_id="local-id""""))
    }

    fun testTheCommaSeparatedNamesOfOlderBuildsAreReadAsLists() {
        val saved = TestoSavedConfiguration.readFrom(JDOMUtil.load(baselineXml("persistence-legacy", "legacy input")))

        assertEquals(listOf("db", "slow", "x"), saved.selection.groups)
        assertEquals(listOf("flaky"), saved.selection.excludeGroups)
        assertEquals(listOf("Unit, slow"), saved.selection.suites)
        assertEquals(listOf("bench"), saved.selection.testoTypes)
        assertEquals(baselineXml("persistence-legacy", "written back"), rewrite(saved))
    }

    fun testNoRunnerElementIsTheDefaultConfiguration() {
        val saved = TestoSavedConfiguration.readFrom(Element("configuration"))

        assertEquals(TestoScope.DIRECTORY, saved.selection.scope)
        assertEquals(TestoSavedConfiguration.DEFAULT_RUNNER_OPTIONS, saved.selection.testRunnerOptions)
        assertEquals(TestoLaunchSettings(), saved.launch)
        assertEquals("<configuration />", rewrite(saved))
    }

    fun testARunnerElementOfDefaultsIsLeftOutAsTheSerializerDoes() {
        val defaults = """<configuration><TestoRunnerSettings scope="Directory" options="-q -n --teamcity" /></configuration>"""
        assertEquals("<configuration />", rewrite(TestoSavedConfiguration.readFrom(JDOMUtil.load(defaults))))

        val foreign = """<configuration><TestoRunnerSettings options="-q -n --teamcity" future="1" /></configuration>"""
        assertTrue(rewrite(TestoSavedConfiguration.readFrom(JDOMUtil.load(foreign))).contains("""future="1""""))
    }

    fun testARunOfTheWholeConfigurationNamesItsScope() {
        val selection = TestoRunSelection(testRunnerOptions = TestoSavedConfiguration.DEFAULT_RUNNER_OPTIONS, suites = listOf("Unit"))

        val xml = rewrite(TestoSavedConfiguration(selection, TestoLaunchSettings()))

        assertTrue(xml, xml.contains("""scope="ConfigurationFile""""))
        assertEquals(selection, TestoSavedConfiguration.readFrom(JDOMUtil.load(xml)).selection)
    }

    fun testWhatItDoesNotReadIsWrittenBack() {
        val xml = """
            <configuration>
              <CommandLine envFiles="x" workingDirectory="/w">
                <future />
              </CommandLine>
              <TestoRunnerSettings directory="/w" future="1">
                <option name="future" value="2" />
              </TestoRunnerSettings>
            </configuration>
        """.trimIndent()
        val saved = TestoSavedConfiguration.readFrom(JDOMUtil.load(xml))

        val written = rewrite(saved.with(saved.selection.copy(directoryPath = "/other"), saved.launch.copy(workingDirectory = null)))

        assertTrue(written, written.contains("""<CommandLine envFiles="x">"""))
        assertTrue(written, written.contains("<future />"))
        assertTrue(written, written.contains("""directory="/other" future="1""""))
        assertTrue(written, written.contains("""<option name="future" value="2" />"""))
    }

    fun testACoverageEngineOfANewerBuildIsKeptUnlessChangedHere() {
        val saved = TestoSavedConfiguration.readFrom(
            JDOMUtil.load("""<configuration><TestoRunnerSettings><option name="coverageEngine" value="Xdebug4" /></TestoRunnerSettings></configuration>""")
        )
        assertEquals(TestoCoverageDriver.XDEBUG, saved.selection.coverageDriver)

        assertTrue(rewrite(saved).contains("""<option name="coverageEngine" value="Xdebug4" />"""))
        val changed = rewrite(saved.with(saved.selection.copy(coverageDriver = TestoCoverageDriver.PCOV), saved.launch))
        assertTrue(changed, changed.contains("""<option name="coverageEngine" value="PCov" />"""))
    }

    fun testCommandLineAttributesAreWrittenInTheSerializersOrder() {
        val saved = TestoSavedConfiguration.readFrom(
            JDOMUtil.load("""<configuration><CommandLine future="1" workingDirectory="/w" parameters="-d x=1" /></configuration>""")
        )

        val written = rewrite(saved.with(saved.selection, saved.launch.copy(passParentEnvironment = false)))

        assertTrue(written, written.contains("""<CommandLine parameters="-d x=1" pass_parent_envs="false" workingDirectory="/w" future="1" />"""))
    }

    fun testAScopeOfANewerBuildIsKeptUnlessChangedHere() {
        val saved = TestoSavedConfiguration.readFrom(JDOMUtil.load("""<configuration><TestoRunnerSettings scope="Pattern" /></configuration>"""))
        assertEquals(TestoScope.DIRECTORY, saved.selection.scope)

        assertTrue(rewrite(saved).contains("""scope="Pattern""""))
        val changed = rewrite(saved.with(saved.selection.copy(scope = TestoScope.FILE), saved.launch))
        assertTrue(changed, changed.contains("""scope="File""""))
    }
}
