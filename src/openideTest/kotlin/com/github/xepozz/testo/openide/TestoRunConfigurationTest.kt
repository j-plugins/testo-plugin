package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.openide.tests.run.BinaryCheck
import com.github.xepozz.testo.openide.tests.run.TestoLaunch
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationOptions
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import ru.openide.openphp.run.testing.settings.PhpTestFrameworkSettings
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

/** What an OpenIDE Testo configuration saves, and what it checks before a run. */
class TestoRunConfigurationTest : BasePlatformTestCase() {
    private lateinit var defaultEnvironment: (com.intellij.openapi.project.Project) -> PhpLaunchEnvironment

    override fun setUp() {
        super.setUp()
        defaultEnvironment = TestoRunConfiguration.environmentProvider
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
            PhpTestFrameworkSettings.getInstance(project).loadState(PhpTestFrameworkSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(): TestoRunConfiguration =
        TestoRunConfigurationType.instance().createTemplateConfiguration(project).also { it.name = "Checked" }

    private fun runIn(environment: PhpLaunchEnvironment) {
        TestoRunConfiguration.environmentProvider = { environment }
    }

    private fun declareTestoInComposer() =
        myFixture.addFileToProject("composer.json", """{"require-dev": {"testo/testo": "^0.10"}}""")

    private fun warningOf(configuration: TestoRunConfiguration): String? =
        try {
            configuration.checkConfiguration()
            null
        } catch (e: RuntimeConfigurationWarning) {
            e.message
        }

    fun testTheTypeIdIsTheOneSavedConfigurationsCarry() {
        assertEquals("TestoRunConfiguration", TestoRunConfigurationType.instance().id)
        assertEquals("TestoRunConfiguration", configuration().type.id)
    }

    fun testWritingAndReadingBackKeepsTheModel() {
        val saved = configuration()
        saved.selection = saved.selection.apply {
            scope = TestoScope.METHOD
            filePath = "/work/app/tests/CalcTest.php"
            methodName = "\\App\\CalcTest::median:0:1"
            useAlternativeConfigurationFile = true
            configurationFilePath = "/work/app/testo.php"
            testRunnerOptions = "--no-ansi"
            command = "list"
            testoTypes = listOf("test", "!bench")
            suites = listOf("Unit", "My Suite")
            groups = listOf("db")
            excludeGroups = listOf("slow")
            dataProviderIndex = 0
            dataSetIndex = 1
            coverageDriver = TestoCoverageDriver.PCOV
            coverageClover = true
            coverageXml = false
            coverageLevel = "line"
            coverageOptions = ""
            logJunit = true
            infectionScope = "git-lines"
            infectionGitDiffBase = "main"
            infectionThreads = "4"
            infectionOnlyCoveringTestCases = true
            infectionWithUncovered = true
            infectionTimeoutsAsEscaped = true
            infectionMutators = "@default"
            infectionStaticAnalysisTool = "phpstan"
            infectionOptions = "--debug"
        }
        with(saved.options) {
            binaryPath = "/opt/testo/bin/testo"
            workingDirectory = "/work/app"
            environmentVariables = mutableMapOf("APP_ENV" to "test")
            passParentEnvironment = false
            interpreterId = "compose-id"
            interpreterName = "PHP 8.4 (Docker)"
            interpreterOptions = "-d memory_limit=1G"
        }
        val element = Element("configuration")
        saved.writeExternal(element)

        val read = configuration()
        read.readExternal(element)

        assertEquals(saved.selection, read.selection)
        assertEquals("/opt/testo/bin/testo", read.options.binaryPath)
        assertEquals("/work/app", read.options.workingDirectory)
        assertEquals("compose-id", read.options.interpreterId)
        assertEquals("PHP 8.4 (Docker)", read.options.interpreterName)
        assertEquals("-d memory_limit=1G", read.options.interpreterOptions)
        assertEquals(mapOf("APP_ENV" to "test"), read.options.environmentVariables)
        assertFalse(read.options.passParentEnvironment)
        val clone = saved.clone() as TestoRunConfiguration
        assertEquals(saved.selection, clone.selection)
        clone.selection = clone.selection.copy(infectionThreads = "8")
        assertEquals("4", saved.selection.infectionThreads)
    }

    fun testASingleTypeSavedByAnEarlierBuildIsReadAsAList() {
        val element = Element("configuration")
        configuration().writeExternal(element)
        element.addContent(Element("option").setAttribute("name", "testoType").setAttribute("value", "bench"))

        val read = configuration()
        read.readExternal(element)
        assertEquals(listOf("bench"), read.selection.testoTypes)

        read.selection = read.selection.copy(testoTypes = listOf("test", "inline"))
        val written = Element("configuration")
        read.writeExternal(written)
        val xml = org.jdom.output.XMLOutputter().outputString(written)
        assertFalse(xml, xml.contains("\"testoType\" value="))
        val reread = configuration()
        reread.readExternal(written)
        assertEquals(listOf("test", "inline"), reread.selection.testoTypes)

        reread.selection = reread.selection.copy(testoTypes = emptyList())
        assertEquals(emptyList<String>(), reread.selection.testoTypes)
    }

    private fun xml(element: Element): String = org.jdom.output.XMLOutputter().outputString(element)

    private fun savedXml(element: Element, name: String): String = JDOMUtil.write(checkNotNull(element.getChild(name)) { name })

    fun testTheBaselineConfigurationIsReadAndWrittenBackAsItWas() {
        val lines = File("src/test/testData/baseline/persistence.txt").readLines()
        val start = lines.indexOf("== every field") + 1
        val end = start + lines.subList(start, lines.size).indexOf("</configuration>")
        val source = JDOMUtil.load(lines.subList(start, end + 1).joinToString("\n"))

        val read = configuration()
        read.readExternal(source)

        with(read.selection) {
            assertEquals(TestoScope.METHOD, scope)
            assertEquals("\\App\\CalcTest::med:3:0", methodName)
            assertEquals("-q --stop-on-failure", testRunnerOptions)
            assertEquals(listOf("Unit", "My, Suite"), suites)
            assertEquals(TestoCoverageDriver.PCOV, coverageDriver)
            assertEquals("--debug", infectionOptions)
        }
        assertEquals("/opt/testo/bin/testo", read.options.binaryPath)
        assertEquals("/work/app", read.options.workingDirectory)
        assertEquals(mapOf("APP_ENV" to "test", "XDEBUG_MODE" to "off"), read.options.environmentVariables)
        assertEquals("compose-id", read.options.interpreterId)
        assertEquals("PHP 8.4 (Docker)", read.options.interpreterName)
        assertEquals("-d memory_limit=1G", read.options.interpreterOptions)
        assertFalse(read.options.passParentEnvironment)

        val written = Element("configuration")
        read.writeExternal(written)
        assertEquals(savedXml(source, "CommandLine"), savedXml(written, "CommandLine"))
        assertEquals(savedXml(source, "TestoRunnerSettings"), savedXml(written, "TestoRunnerSettings"))
    }

    fun testOptionsOfAnEarlierBuildAreReadAndSavedInTheSharedShape() {
        val source = JDOMUtil.load(
            """
            <configuration default="false" name="Unit" type="TestoRunConfiguration">
              <option name="infectionGitDiffBase"/>
              <option name="infectionMutators"/>
              <option name="infectionOptions"/>
              <option name="infectionStaticAnalysisTool"/>
              <option name="infectionThreads"/>
              <option name="suites">
                <list>
                  <option value="Unit"/>
                </list>
              </option>
              <option name="testoType"/>
              <TestoRunnerSettings directory="/stale" method="" options="-q -n --teamcity"/>
              <method v="2"/>
            </configuration>
            """.trimIndent()
        )
        val read = configuration()
        read.readExternal(source)

        assertEquals("What this build saved wins over what another left beside it", TestoScope.CONFIGURATION_FILE, read.selection.scope)
        assertEquals(listOf("Unit"), read.selection.suites)
        assertNull(read.selection.directoryPath)

        val written = Element("configuration")
        read.writeExternal(written)
        assertTrue(xml(written), written.getChildren("option").none(TestoRunConfigurationOptions::owns))
        val runner = checkNotNull(written.getChild("TestoRunnerSettings")) { xml(written) }
        assertEquals("ConfigurationFile", runner.getAttributeValue("scope"))
        assertEquals("-q -n --teamcity", runner.getAttributeValue("options"))
        assertEquals(listOf("Unit"), runner.getChild("suites").getChildren("option").map { it.getAttributeValue("value") })

        val reread = configuration()
        reread.readExternal(written)
        assertEquals(read.selection, reread.selection)
    }

    fun testEnvironmentVariablesAreSavedInTheOrderRead() {
        val names = listOf("ZETA", "ALPHA", "MID", "BETA", "OMEGA", "APP_ENV", "XDEBUG_MODE", "A1", "Q")
        val source = Element("configuration").addContent(
            Element("CommandLine").addContent(
                Element("envs").apply { names.forEach { addContent(Element("env").setAttribute("name", it).setAttribute("value", "1")) } }
            )
        )
        val read = configuration()
        read.readExternal(source)
        val written = Element("configuration")
        read.writeExternal(written)

        assertEquals(names, written.getChild("CommandLine").getChild("envs").getChildren("env").map { it.getAttributeValue("name") })
    }

    fun testAScopeOfANewerBuildCanBeChangedBackToDirectory() {
        val read = configuration()
        read.readExternal(JDOMUtil.load("""<configuration><TestoRunnerSettings scope="Pattern" /></configuration>"""))
        fun savedScope() = Element("configuration").also(read::writeExternal).getChild("TestoRunnerSettings")?.getAttributeValue("scope")
        assertEquals("Pattern", savedScope())

        read.selection = read.selection.copy(scope = TestoScope.FILE)
        assertEquals("File", savedScope())
        read.selection = read.selection.copy(scope = TestoScope.DIRECTORY)
        assertNull(savedScope())
    }

    fun testTheOptionsOfEarlierBuildsAreRecognisedByName() {
        for (name in listOf("scope", "suites", "testoType", "infectionOptions", "binaryPath", "environmentVariables")) {
            assertTrue(name, TestoRunConfigurationOptions.owns(Element("option").setAttribute("name", name)))
        }
        assertFalse(TestoRunConfigurationOptions.owns(Element("option").setAttribute("name", "isNameGenerated")))
    }

    fun testJunitDefaultsToEnabledAndAnExplicitOptOutIsPreserved() {
        val saved = configuration()
        assertTrue(saved.selection.logJunit)
        saved.selection = saved.selection.copy(logJunit = false, rerunFilters = listOf("temporary"))
        val element = Element("configuration")
        saved.writeExternal(element)
        assertFalse(org.jdom.output.XMLOutputter().outputString(element).contains("temporary"))
        val read = configuration()
        read.readExternal(element)
        assertFalse(read.selection.logJunit)
        assertTrue(read.selection.rerunFilters.isEmpty())
    }

    fun testWithoutABinaryTheCheckNamesTestoAndTheTestFrameworksPage() {
        runIn(FakeLaunchEnvironment())
        val configuration = configuration()

        val error = thrown<RuntimeConfigurationError> { configuration.checkConfiguration() }
        assertTrue(error.message, error.message!!.contains("Testo") && error.message!!.contains("Test Frameworks"))
        val refusal = thrown<ExecutionException> { configuration.prepare(processHandler = { NopProcessHandler() }) }
        assertTrue(refusal.message, refusal.message!!.contains("Test Frameworks"))
    }

    fun testTestoIsSetUpOnceTheTestFrameworksPageNamesTheBinary() {
        assertFalse(TestoPhp.getInstance().isTestoConfigured(project))

        // What the page stores when the user sets the path; PHP for OpenIDE publishes no writer for it.
        PhpTestFrameworkSettings.getInstance(project).update(TestoFrameworkType.ID, "/opt/testo/bin/testo", "")

        assertTrue("A page edit changes no file, and Testo still sees it", TestoPhp.getInstance().isTestoConfigured(project))
    }

    fun testADeclaredPackageNotInstalledRefusesLocally() {
        declareTestoInComposer()
        runIn(FakeLaunchEnvironment())
        val configuration = configuration()

        assertTrue("Testo is set up once the path is there", TestoPhp.getInstance().isTestoConfigured(project))
        val warning = warningOf(configuration)
        assertTrue(warning, warning!!.contains("composer install") && warning.contains("Test Frameworks"))
        val refusal = thrown<ExecutionException> { configuration.prepare(processHandler = { NopProcessHandler() }) }
        val binary = FileUtil.toSystemDependentName("vendor/testo/testo/bin/testo")
        assertTrue(refusal.message, refusal.message!!.contains(binary) && refusal.message!!.contains("composer install"))
    }

    fun testADeclaredPackageNotInstalledOnlyWarnsForAContainer() {
        val composer = declareTestoInComposer()
        runIn(FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("host.docker.internal"),
            mappings = mapOf(composer.virtualFile.parent.path to "/app"),
        ))
        val configuration = configuration()

        val warning = warningOf(configuration)
        assertTrue(warning, warning!!.contains("container"))
        val prepared = configuration.prepare(processHandler = { NopProcessHandler() })
        val invocation = listOf("php", "/app/vendor/testo/testo/bin/testo", "run")
        assertTrue(prepared.command.commandLineString, invocation in prepared.command.parametersList.list.windowed(3))
    }

    fun testAPathGivenAsIsIsCheckedByLocality() {
        runIn(FakeLaunchEnvironment())
        val configuration = configuration().apply { options.binaryPath = "/nowhere/testo" }
        assertTrue(warningOf(configuration)!!.contains("/nowhere/testo does not exist"))

        runIn(FakeLaunchEnvironment(locality = PhpLaunchLocality.Container("host.docker.internal")))
        assertTrue(warningOf(configuration)!!.contains("container"))
    }

    fun testWslPathsAreCheckedOnlyWhereTheIdeCanSeeThem() {
        val host = FileUtil.createTempDirectory("wsl-app", null)
        val binary = File(host, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }
        val wsl = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.WslDistribution("Ubuntu"),
            mappings = mapOf(FileUtil.toSystemIndependentName(host.path) to "/home/u/app", "C:/project" to "/mnt/c/project"),
        )

        assertEquals("A host path is checked on the host", BinaryCheck.Missing("C:/project/vendor/bin/testo"), TestoLaunch.checkBinary("C:/project/vendor/bin/testo", wsl))
        assertEquals("/mnt/c goes through its translation", BinaryCheck.Missing("/mnt/c/project/vendor/bin/testo"), TestoLaunch.checkBinary("/mnt/c/project/vendor/bin/testo", wsl))
        assertEquals("a mapping reaches the file", BinaryCheck.Found, TestoLaunch.checkBinary("/home/u/app/vendor/bin/testo", wsl))
        assertEquals("nothing to translate by", BinaryCheck.Unverified("/home/u/tools/testo"), TestoLaunch.checkBinary("/home/u/tools/testo", wsl))
        assertEquals(BinaryCheck.Found, TestoLaunch.checkBinary(binary.path, FakeLaunchEnvironment()))
    }
}
