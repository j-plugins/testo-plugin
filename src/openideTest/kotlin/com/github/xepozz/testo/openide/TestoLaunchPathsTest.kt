package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.run.testing.settings.PhpTestFrameworkSettings
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

class TestoLaunchPathsTest : BasePlatformTestCase() {
    private lateinit var originalEnvironment: (Project) -> PhpLaunchEnvironment
    private lateinit var app: File
    private var rootConfig: File? = null

    override fun setUp() {
        super.setUp()
        originalEnvironment = TestoRunConfiguration.environmentProvider
        PhpTestFrameworkSettings.getInstance(project).loadState(PhpTestFrameworkSettings.State())
        app = File(project.basePath!!, "launch-paths-fixture")
        assertFalse(app.exists())
        app.mkdirs()
        write("vendor/bin/testo", "#!/usr/bin/env php")
        write("composer.json", """{"require-dev":{"testo/testo":"^0.10"}}""")
        write("testo.php")
        write("tests/CalcTest.php", "<?php class CalcTest {}")
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = originalEnvironment
            PhpTestFrameworkSettings.getInstance(project).loadState(PhpTestFrameworkSettings.State())
            rootConfig?.let { FileUtil.delete(it) }
            FileUtil.delete(app)
        } finally {
            super.tearDown()
        }
    }

    private fun write(path: String, text: String = "<?php return [];"): File = File(app, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun withRootConfiguration() {
        rootConfig = File(project.basePath!!, "testo.php").also {
            assertFalse(it.exists())
            it.writeText("<?php return [];")
        }
    }

    private fun configuration(environment: PhpLaunchEnvironment = FakeLaunchEnvironment()): TestoRunConfiguration {
        TestoRunConfiguration.environmentProvider = { environment }
        return TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
            options.binaryPath = File(app, "vendor/bin/testo").path
            selection = selection.apply {
                scope = TestoScope.FILE
                filePath = File(app, "tests/CalcTest.php").path
                logHtml = false
                logJunit = false
            }
        }
    }

    private fun command(configuration: TestoRunConfiguration) =
        configuration.prepare(processHandler = { NopProcessHandler() }).command

    private fun selectedConfig(configuration: TestoRunConfiguration): String {
        val arguments = command(configuration).parametersList.list
        return arguments[arguments.indexOf("--config") + 1]
    }

    private fun frameworkConfiguration(path: String) {
        PhpTestFrameworkSettings.getInstance(project).update(TestoFrameworkType.ID, "", path)
    }

    fun testConfigurationSearchStartsAtTheExplicitWorkingDirectory() {
        withRootConfiguration()
        val config = configuration().apply { options.workingDirectory = app.path }
        assertEquals(File(app, "testo.php").path, selectedConfig(config))
    }

    fun testConfigurationSearchStartsAtTheBinaryProjectWhenNoDirectoryIsSet() {
        withRootConfiguration()
        val config = configuration()
        assertEquals(File(app, "testo.php").path, selectedConfig(config))
        assertEquals(app.path, config.launch(FakeLaunchEnvironment()).workingDirectory)
    }

    fun testFrameworkConfigurationHasPriorityOverDiscovery() {
        val chosen = write("explicit.php")
        frameworkConfiguration(chosen.path)
        val config = configuration().apply { options.workingDirectory = app.path }
        assertEquals(chosen.path, selectedConfig(config))
    }

    fun testMappedFrameworkConfigurationKeepsFileScopeInHostCoordinates() {
        frameworkConfiguration("/app/testo.php")
        val environment = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("localhost"), mappings = mapOf(app.path to "/app"),
        )
        val config = configuration(environment)
        assertEquals(app.path, config.launch(environment).workingDirectory)
        assertEquals("/app/testo.php", selectedConfig(config))
        assertTrue(command(config).commandLineString, command(config).commandLineString.contains("--path tests/CalcTest.php"))
    }

    fun testMappedAlternativeConfigurationAlsoUsesTheHostDirectory() {
        val environment = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("localhost"), mappings = mapOf(app.path to "/app"),
        )
        val config = configuration(environment).apply {
            selection = selection.apply {
                useAlternativeConfigurationFile = true
                configurationFilePath = "/app/testo.php"
                scope = TestoScope.METHOD
                methodName = "adds"
            }
        }
        assertEquals(app.path, config.launch(environment).workingDirectory)
        assertTrue(command(config).commandLineString, command(config).commandLineString.contains("--path tests/CalcTest.php --filter adds"))
    }

    fun testMappedFrameworkConfigurationAlsoSupportsDirectoryScope() {
        frameworkConfiguration("/app/testo.php")
        val environment = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("localhost"), mappings = mapOf(app.path to "/app"),
        )
        val config = configuration(environment).apply {
            selection = selection.apply {
                scope = TestoScope.DIRECTORY
                directoryPath = File(app, "tests").path
            }
        }
        assertTrue(command(config).commandLineString, command(config).commandLineString.contains("--path tests"))
    }

    fun testUnmappedContainerConfigurationFallsBackToTheBinaryProject() {
        frameworkConfiguration("/tools/testo.php")
        val environment = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.Container("localhost"), mappings = mapOf(app.path to "/app"),
        )
        val config = configuration(environment)
        assertEquals(app.path, config.launch(environment).workingDirectory)
        assertEquals("/tools/testo.php", selectedConfig(config))
    }

    fun testWslReverseMappingNormalizesHostSeparators() {
        frameworkConfiguration("/home/app/testo.php")
        val mapped = FakeLaunchEnvironment(
            locality = PhpLaunchLocality.WslDistribution("Ubuntu"), mappings = mapOf(app.path to "/home/app"),
        )
        val environment = object : PhpLaunchEnvironment by mapped {
            override fun toHost(pathInEnvironment: String): String {
                val host = mapped.toHost(pathInEnvironment)
                return if (host != pathInEnvironment) host.replace('/', '\\') else host
            }
        }
        val config = configuration(environment)
        assertEquals(app.path, config.launch(environment).workingDirectory)
        assertEquals("/home/app/testo.php", selectedConfig(config))
        assertTrue(command(config).commandLineString, command(config).commandLineString.contains("--path tests/CalcTest.php"))
    }
}
