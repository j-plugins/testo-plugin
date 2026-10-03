package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.php.TestoToolRequest
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import ru.openide.openphp.settings.launch.PreparedPhpToolLaunch
import ru.openide.openphp.settings.launch.PhpToolOutcome
import com.intellij.execution.process.ProcessHandler
import java.util.concurrent.CompletableFuture
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class TestoToolEnvironmentTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private var preparedCommand: GeneralCommandLine? = null
    private var passParent: Boolean? = null

    private fun captured(configuration: TestoRunConfiguration, environment: PhpLaunchEnvironment) =
        OpenIdeToolEnvironment(configuration, environment) { args, cwd, env, paths, inherit ->
            preparedCommand = environment.command(args, cwd, env, paths)
            passParent = inherit
            object : PreparedPhpToolLaunch {
                override val completion = CompletableFuture<PhpToolOutcome>()
                override fun start(): ProcessHandler = error("This fixture only prepares the launch")
                override fun stop() = completion.also { it.complete(PhpToolOutcome.NotStarted("stopped")) }
                override fun abandon() { completion.complete(PhpToolOutcome.NotStarted("abandoned")) }
            }
        }

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("testo-tool")
    }

    private fun configuration(): TestoRunConfiguration = TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
        options.binaryPath = root.resolve("vendor/bin/testo").toString()
        options.workingDirectory = root.toString()
        options.environmentVariables = mutableMapOf("APP_ENV" to "test")
    }

    private fun input(): Path = Files.createDirectories(root.resolve("reports/coverage-xml/nested")).let {
        Files.writeString(it.resolve("A.xml"), "coverage")
        Files.writeString(root.resolve("reports/junit.xml"), "junit")
        root.resolve("reports")
    }

    private fun request(input: Path, arguments: (String, Map<Path, String>) -> List<String> = { path, _ -> listOf("--coverage=$path") }) =
        TestoToolRequest("/app/vendor/bin/infection", input, listOf(root.resolve("output/report.html")), arguments)

    fun testTheCapturedConfigurationSuppliesCwdAndEnvironment() {
        var command: GeneralCommandLine? = null
        val fake = FakeLaunchEnvironment()
        val environment = object : PhpLaunchEnvironment by fake {
            override fun command(phpArgs: List<String>, workDir: File?, env: Map<String, String>, exposures: List<PhpExposedPath>): GeneralCommandLine =
                fake.command(phpArgs, workDir, env, exposures).also { command = it }
        }
        val configuration = configuration()
        configuration.options.passParentEnvironment = false
        val captured = captured(configuration, environment)
        configuration.options.workingDirectory = root.resolve("other").toString()
        configuration.options.environmentVariables = mutableMapOf("APP_ENV" to "changed")
        configuration.options.passParentEnvironment = true
        captured.prepare(request(input())).use {
            assertEquals(root.toString(), command!!.workDirectory!!.path)
            assertEquals("test", command!!.environment["APP_ENV"])
            assertEquals(false, passParent)
        }
    }

    fun testMountedInputsAndOutputsArePassedToTheCommand() {
        val environment = FakeLaunchEnvironment(locality = PhpLaunchLocality.Container("host"), exposed = {
            PhpExposedPath.Mounted("/shared/" + Path.of(it).fileName, listOf("-v", Path.of(it).parent.toString() + ":/shared"))
        })
        captured(configuration(), environment).prepare(request(input())).use {
            val line = preparedCommand!!.commandLineString
            assertTrue(line, line.contains("-v"))
            assertTrue(line, line.contains("--coverage=/shared"))
        }
    }

    fun testStagingCopiesTheWholeDirectoryAndReleasesOnlyItsOwnCopy() {
        val source = input()
        val untouched = Files.createDirectories(root.resolve(".testo-staging/keep"))
        var staged: Path? = null
        val environment = FakeLaunchEnvironment(locality = PhpLaunchLocality.WslDistribution("Ubuntu"), exposed = { path ->
            if (path.contains("/.testo-staging/") && path.endsWith("junit.xml")) {
                staged = Path.of(path).parent
                PhpExposedPath.Visible("/app" + path.removePrefix(root.toString()))
            } else PhpExposedPath.Unreachable("outside mapped project")
        })
        val prepared = captured(configuration(), environment).prepare(request(source))
        assertTrue(Files.exists(staged!!.resolve("coverage-xml/nested/A.xml")))
        assertTrue(Files.exists(staged!!.resolve("junit.xml")))
        assertTrue(preparedCommand!!.commandLineString.contains("--coverage=/app/.testo-staging/"))
        prepared.close()
        prepared.close()
        assertFalse(Files.exists(staged))
        assertTrue(Files.exists(untouched))
        assertTrue(Files.exists(source.resolve("junit.xml")))
    }

    fun testPreparationFailureReleasesStaging() {
        var staged: Path? = null
        val environment = FakeLaunchEnvironment(exposed = { path ->
            if (path.contains("/staging/") && path.endsWith("junit.xml")) {
                staged = Path.of(path).parent
                PhpExposedPath.Visible("/visible/junit.xml")
            } else PhpExposedPath.Unreachable("not mapped")
        })
        assertNotNull(thrown<IllegalStateException> {
            captured(configuration(), environment).prepare(request(input()) { _, _ -> error("arguments failed") })
        })
        assertNotNull(staged)
        assertFalse(Files.exists(staged))
    }

    fun testUnreachableInputRefusesBeforeCommandPreparation() {
        var called = false
        val fake = FakeLaunchEnvironment(exposed = { PhpExposedPath.Unreachable("no mapping") })
        val environment = object : PhpLaunchEnvironment by fake {
            override fun command(phpArgs: List<String>, workDir: File?, env: Map<String, String>, exposures: List<PhpExposedPath>): GeneralCommandLine {
                called = true
                return fake.command(phpArgs, workDir, env, exposures)
            }
        }
        val error = thrown<ExecutionException> { captured(configuration(), environment).prepare(request(input())) }
        assertTrue(error.message, error.message!!.contains("coverage reports"))
        assertFalse(called)
    }
}
