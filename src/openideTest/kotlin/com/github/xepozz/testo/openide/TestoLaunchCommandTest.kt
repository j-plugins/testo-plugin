package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

/**
 * The command a run gets, on a launch environment whose rules the test states: what the plugin decides — the binary,
 * the order of the arguments, the working directory, the reports — against what the environment does with it.
 */
class TestoLaunchCommandTest : BasePlatformTestCase() {
    private lateinit var defaultEnvironment: (com.intellij.openapi.project.Project) -> PhpLaunchEnvironment
    private lateinit var app: File

    override fun setUp() {
        super.setUp()
        defaultEnvironment = TestoRunConfiguration.environmentProvider
        app = FileUtil.createTempDirectory("testo-app", null)
        File(app, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }
        File(app, "tests").mkdirs()
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
        } finally {
            super.tearDown()
        }
    }

    private val wd get() = FileUtil.toSystemIndependentName(app.path)

    private fun configuration(logHtml: Boolean = true) = TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
        name = "Golden"
        options.binaryPath = "$wd/vendor/bin/testo"
        options.workingDirectory = wd
        selection = selection.apply {
            scope = TestoScope.METHOD
            filePath = "$wd/tests/CalcTest.php"
            methodName = "median:0:1"
            groups = listOf("db")
            this.logHtml = logHtml
            logJunit = false
        }
    }

    private fun commandIn(environment: PhpLaunchEnvironment, configuration: TestoRunConfiguration = configuration()): String {
        TestoRunConfiguration.environmentProvider = { environment }
        val prepared = configuration.prepare(processHandler = { NopProcessHandler() })
        return prepared.command.commandLineString
            .replace(FileUtil.toSystemIndependentName(PathManager.getSystemPath()), "<system>")
            .replace(project.locationHash, "<project>")
            .replace(wd, "<wd>")
    }

    fun testLocal() {
        assertEquals(
            "php <wd>/vendor/bin/testo run -q -n --teamcity --group db " +
                "--log-html=<system>/testo/reports/<project>/Golden/report.html " +
                "--path tests/CalcTest.php --filter median:0:1",
            commandIn(FakeLaunchEnvironment()),
        )
    }

    fun testContainerTranslatesPathsAndMountsTheReport() {
        val container = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("host.docker.internal"),
            mappings = mapOf(wd to "/app"),
            exposed = { PhpExposedPath.Mounted("/reports/report.html", listOf("-v", "reports:/reports")) },
        )

        assertEquals(
            "env Container(ideHostName=host.docker.internal) -v reports:/reports -w=/app php /app/vendor/bin/testo run " +
                "-q -n --teamcity --group db --log-html=/reports/report.html --path tests/CalcTest.php --filter median:0:1",
            commandIn(container),
        )
    }

    fun testAReportOutOfReachRefusesTheRun() {
        val unreachable = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("host.docker.internal"),
            mappings = mapOf(wd to "/app"),
            exposed = { PhpExposedPath.Unreachable("the service mounts no directory for it") },
        )

        val refusal = thrown<ExecutionException> { commandIn(unreachable) }
        assertTrue(refusal.message, refusal.message!!.contains("the service mounts no directory for it"))
        assertFalse("Without a report nothing is out of reach", commandIn(unreachable, configuration(logHtml = false)).contains("--log-html"))
    }
}
