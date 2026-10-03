package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoCoverageArguments
import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

/** A Coverage run: the driver switched on, the reports where the IDE reads them, and what refuses it before start. */
class TestoCoverageRunnerTest : BasePlatformTestCase() {
    private lateinit var defaultEnvironment: (com.intellij.openapi.project.Project) -> PhpLaunchEnvironment
    private lateinit var app: File
    private lateinit var coverage: File

    override fun setUp() {
        super.setUp()
        defaultEnvironment = TestoRunConfiguration.environmentProvider
        app = FileUtil.createTempDirectory("testo-coverage-app", null)
        File(app, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }
        coverage = FileUtil.createTempDirectory("testo-coverage", null)
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
        } finally {
            super.tearDown()
        }
    }

    private val wd get() = FileUtil.toSystemIndependentName(app.path)
    private val localCoverage get() = FileUtil.toSystemIndependentName(coverage.path) + "/Covered@testo.xml"

    private fun covered(
        driver: TestoCoverageDriver,
        environment: PhpLaunchEnvironment = FakeLaunchEnvironment(modules = setOf("core", "xdebug", "pcov")),
    ): TestoCoverageProgramRunner.Covered {
        TestoRunConfiguration.environmentProvider = { environment }
        val configuration = TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
            name = "Covered"
            options.binaryPath = "$wd/vendor/bin/testo"
            options.workingDirectory = wd
            selection = selection.apply {
                scope = TestoScope.FILE
                filePath = "$wd/tests/CalcTest.php"
                coverageDriver = driver
                logHtml = false
            }
        }
        return TestoCoverageProgramRunner.prepare(configuration, localCoverage) { NopProcessHandler() }
    }

    fun testXdebugIsSwitchedToCoverageMode() {
        val command = covered(TestoCoverageDriver.XDEBUG).run.command

        assertEquals("coverage", command.environment["XDEBUG_MODE"])
        assertFalse(command.parametersList.list.contains("-dpcov.enabled=1"))
    }

    fun testPcovIsEnabledAheadOfTheBinary() {
        val command = covered(TestoCoverageDriver.PCOV).run.command

        assertEquals("-dpcov.enabled=1", command.parametersList.list.first())
        assertNull(command.environment["XDEBUG_MODE"])
    }

    fun testTheReportFlagsAreTheCoreOnes() {
        val covered = covered(TestoCoverageDriver.XDEBUG)
        val configuration = TestoRunConfigurationType.instance().createTemplateConfiguration(project)
        val selection = configuration.selection.apply { coverageDriver = TestoCoverageDriver.XDEBUG }
        val expected = TestoCoverageArguments.reportArguments(covered.flags, covered.flags.map { it.second }) +
            TestoCoverageArguments.extraArguments(selection)

        val arguments = covered.run.command.parametersList.list
        val start = arguments.indexOf(expected.first())
        assertEquals(expected, arguments.subList(start, start + expected.size))
    }

    fun testAnExtensionKnownToBeMissingRefusesTheRun() {
        val refusal = thrown<ExecutionException> {
            covered(TestoCoverageDriver.XDEBUG, FakeLaunchEnvironment(modules = setOf("core", "pcov")))
        }
        assertTrue(refusal.message, refusal.message!!.contains("Xdebug is not loaded"))
    }

    fun testAnUnknownModuleListRunsWithAWarning() {
        val covered = covered(TestoCoverageDriver.PCOV, FakeLaunchEnvironment(modules = emptySet()))

        assertTrue(covered.unverifiedExtension, covered.unverifiedExtension!!.contains("could not tell whether PCOV is loaded"))
    }

    fun testPhpdbgIsRefusedBeforeStart() {
        val refusal = thrown<ExecutionException> { covered(TestoCoverageDriver.PHPDBG) }
        assertTrue(refusal.message, refusal.message!!.contains("PHPDBG"))
    }

    fun testAReportDirectoryOutOfReachRefusesTheRun() {
        val unreachable = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("host.docker.internal"),
            modules = setOf("xdebug"),
            exposed = { PhpExposedPath.Unreachable("no volume holds $it") },
        )

        val refusal = thrown<ExecutionException> { covered(TestoCoverageDriver.XDEBUG, unreachable) }
        assertTrue(refusal.message, refusal.message!!.contains("no volume holds"))
    }
}
