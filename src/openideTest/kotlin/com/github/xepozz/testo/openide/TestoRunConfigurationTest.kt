package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.openide.tests.run.BinaryCheck
import com.github.xepozz.testo.openide.tests.run.TestoLaunch
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.process.NopProcessHandler
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

    private fun declareTestoInComposer() {
        myFixture.addFileToProject("composer.json", """{"require-dev": {"testo/testo": "^0.10"}}""")
    }

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
            testoType = "test"
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
        }
        val element = Element("configuration")
        saved.writeExternal(element)

        val read = configuration()
        read.readExternal(element)

        assertEquals(saved.selection, read.selection)
        assertEquals("/opt/testo/bin/testo", read.options.binaryPath)
        assertEquals("/work/app", read.options.workingDirectory)
        assertEquals(mapOf("APP_ENV" to "test"), read.options.environmentVariables)
        assertFalse(read.options.passParentEnvironment)
        val clone = saved.clone() as TestoRunConfiguration
        assertEquals(saved.selection, clone.selection)
        clone.selection = clone.selection.copy(infectionThreads = "8")
        assertEquals("4", saved.selection.infectionThreads)
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
        assertTrue(refusal.message, refusal.message!!.contains("vendor/testo/testo/bin/testo") && refusal.message!!.contains("composer install"))
    }

    fun testADeclaredPackageNotInstalledOnlyWarnsForAContainer() {
        declareTestoInComposer()
        runIn(FakeLaunchEnvironment(locality = PhpLaunchLocality.Container("host.docker.internal")))
        val configuration = configuration()

        val warning = warningOf(configuration)
        assertTrue(warning, warning!!.contains("container"))
        val prepared = configuration.prepare(processHandler = { NopProcessHandler() })
        assertTrue(prepared.command.commandLineString, prepared.command.commandLineString.contains("vendor/testo/testo/bin/testo run"))
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
