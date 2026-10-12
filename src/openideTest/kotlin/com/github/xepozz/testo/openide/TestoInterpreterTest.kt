package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.openide.tests.run.TestoLaunch
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationEditor
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import ru.openide.openphp.run.testing.settings.PhpTestFrameworkSettings
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

/** The interpreter a configuration names: how it is saved, read back from older configurations, and resolved. */
class TestoInterpreterTest : BasePlatformTestCase() {
    private lateinit var defaultEnvironment: (Project) -> PhpLaunchEnvironment
    private lateinit var defaultProfileEnvironment: (Project, String) -> PhpLaunchEnvironment?
    private lateinit var defaultProfiles: (Project) -> List<Pair<String, String>>
    private lateinit var defaultActiveName: (Project) -> String?
    private lateinit var binary: String

    private val active = FakeLaunchEnvironment(displayName = "Active")
    private val compose = FakeLaunchEnvironment(locality = PhpLaunchLocality.Container("compose"), displayName = "Compose")

    override fun setUp() {
        super.setUp()
        defaultEnvironment = TestoRunConfiguration.environmentProvider
        defaultProfileEnvironment = TestoRunConfiguration.profileEnvironmentProvider
        defaultProfiles = TestoRunConfiguration.profilesProvider
        defaultActiveName = TestoRunConfiguration.activeProfileNameProvider
        TestoRunConfiguration.environmentProvider = { active }
        TestoRunConfiguration.profileEnvironmentProvider = { _, id -> compose.takeIf { id == "compose-id" } }
        TestoRunConfiguration.profilesProvider = { listOf("local-id" to "Active", "compose-id" to "Compose") }
        TestoRunConfiguration.activeProfileNameProvider = { "Active" }
        val app = FileUtil.createTempDirectory("testo-interpreter", null)
        binary = FileUtil.toSystemIndependentName(
            File(app, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }.path,
        )
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
            TestoRunConfiguration.profileEnvironmentProvider = defaultProfileEnvironment
            TestoRunConfiguration.profilesProvider = defaultProfiles
            TestoRunConfiguration.activeProfileNameProvider = defaultActiveName
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(): TestoRunConfiguration =
        TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
            name = "Interpreted"
            options.binaryPath = binary
            options.workingDirectory = binary.substringBeforeLast("/vendor/")
            selection = selection.copy(logHtml = false, logJunit = false)
        }

    private fun read(xml: String): TestoRunConfiguration =
        configuration().apply { readExternal(JDOMUtil.load(xml)) }

    fun testAConfigurationSavedWithoutAnInterpreterRunsOnTheActiveProfile() {
        val old = read("""<configuration><option name="scope" value="FILE" /><option name="binaryPath" value="$binary" /></configuration>""")

        assertNull(old.options.interpreterId)
        assertSame(active, old.launchEnvironment())
        old.checkConfiguration()
    }

    fun testASavedCommandStillLoadsAndRuns() {
        val old = read("""<configuration><option name="command" value="list" /><option name="binaryPath" value="$binary" /></configuration>""")

        assertEquals("list", old.selection.command)
        val command = old.prepare(processHandler = { NopProcessHandler() }).command
        assertTrue(command.commandLineString, command.parametersList.list.contains("list"))
    }

    fun testTheChosenInterpreterIsSavedAndRunsTheConfiguration() {
        val saved = configuration().apply {
            options.interpreterId = "compose-id"
            options.interpreterName = "Compose"
            options.interpreterOptions = "-d memory_limit=1G"
        }
        val element = Element("configuration")
        saved.writeExternal(element)
        val read = configuration().apply { readExternal(element) }

        assertEquals("compose-id", read.options.interpreterId)
        assertEquals("Compose", read.options.interpreterName)
        assertSame(compose, read.launchEnvironment())
        val prepared = read.prepare(phpArguments = listOf("-dpcov.enabled=1"), processHandler = { NopProcessHandler() })
        assertSame(compose, read.lastEnvironment)
        assertEquals("Compose", read.interpreterName)
        assertEquals("container", read.interpreterKind)
        val arguments = prepared.command.parametersList.list
        val php = arguments.indexOf("php")
        assertEquals(listOf("-d", "memory_limit=1G", "-dpcov.enabled=1"), arguments.subList(php + 1, php + 4))
    }

    fun testTheChosenInterpreterTakesItsOwnTestFrameworkSettings() {
        val settings = PhpTestFrameworkSettings.getInstance(project)
        try {
            settings.update(TestoFrameworkType.ID, "/project/vendor/bin/testo", "/project/testo.php")
            settings.updateProfile(TestoFrameworkType.ID, "compose-id", "/app/vendor/bin/testo", "")
            val configuration = configuration().apply { options.binaryPath = null }
            val onActive = configuration.launch(active)
            assertEquals("/project/vendor/bin/testo", onActive.binary)
            assertEquals("/project/testo.php", onActive.configFile)

            configuration.options.interpreterId = "compose-id"
            val onCompose = configuration.launch(compose)
            assertEquals("/app/vendor/bin/testo", onCompose.binary)
            assertEquals("A field the profile leaves empty falls back to the project's", "/project/testo.php", onCompose.configFile)
        } finally {
            settings.loadState(PhpTestFrameworkSettings.State())
        }
    }

    fun testAConfigFileIsLookedForFromTheStartUpToTheProject() {
        val root = FileUtil.createTempDirectory("testo-config", null)
        val nested = File(root, "app/tests").apply { mkdirs() }
        File(root, "app/testo.php").writeText("<?php\n")
        val names = listOf("testo.php", "testo.dist.php")
        assertEquals(File(root, "app/testo.php").absolutePath, TestoLaunch.findConfigFile(names, nested.path, root.path))
        assertNull(TestoLaunch.findConfigFile(names, nested.path, File(root, "app/tests").path))
        assertNull(TestoLaunch.findConfigFile(names, root.parent, root.path))
    }

    fun testARemovedInterpreterRefusesTheRunInsteadOfFallingBack() {
        val configuration = configuration().apply {
            options.interpreterId = "gone-id"
            options.interpreterName = "Old Docker"
        }

        val error = thrown<RuntimeConfigurationError> { configuration.checkConfiguration() }
        assertTrue(error.message, error.message!!.contains("Old Docker"))
        val refusal = thrown<ExecutionException> { configuration.prepare(processHandler = { NopProcessHandler() }) }
        assertTrue(refusal.message, refusal.message!!.contains("Old Docker"))
        assertTrue(thrown<ExecutionException> { configuration.launchEnvironment() }.message!!.contains("no longer exists"))
    }

    fun testAProfileIsFoundInTheEnvironmentListByItsPlace() {
        val local = FakeLaunchEnvironment(displayName = "Local")
        val docker = FakeLaunchEnvironment(displayName = "Docker")
        val wsl = FakeLaunchEnvironment(displayName = "WSL")
        val profiles = listOf("l" to "Local", "d" to "Docker", "w" to "WSL")

        // The active profile leads the list; the others follow in their own order.
        val environments = listOf(docker, local, wsl)
        assertSame(docker, TestoRunConfiguration.profileEnvironment("d", "d", profiles, environments))
        assertSame(local, TestoRunConfiguration.profileEnvironment("l", "d", profiles, environments))
        assertSame(wsl, TestoRunConfiguration.profileEnvironment("w", "d", profiles, environments))
        assertNull(TestoRunConfiguration.profileEnvironment("x", "d", profiles, environments))

        // No active profile: the list opens with the plain PHP on the path.
        val path = FakeLaunchEnvironment(displayName = "PHP on PATH")
        assertSame(local, TestoRunConfiguration.profileEnvironment("l", null, profiles, listOf(path, local, docker, wsl)))

        thrown<ExecutionException> { TestoRunConfiguration.profileEnvironment("w", "d", profiles, listOf(docker, local)) }
        thrown<ExecutionException> { TestoRunConfiguration.profileEnvironment("l", "d", profiles, listOf(docker, wsl, local)) }
    }

    fun testTheFormKeepsTheInterpreterTheCommandAndARemovedProfile() {
        val editor = TestoRunConfigurationEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.component
        val configuration = configuration().apply {
            options.interpreterId = "gone-id"
            options.interpreterName = "Old Docker"
            options.interpreterOptions = "-d display_errors=1"
            selection = selection.copy(command = "list", scope = TestoScope.METHOD, filePath = "/app/tests/ATest.php", methodName = "a")
        }

        editor.resetFrom(configuration)
        val applied = configuration().apply { selection = selection.copy(command = "list") }
        editor.applyTo(applied)

        assertEquals("gone-id", applied.options.interpreterId)
        assertEquals("Old Docker", applied.options.interpreterName)
        assertEquals("-d display_errors=1", applied.options.interpreterOptions)
        assertEquals("list", applied.selection.command)
        assertEquals(TestoScope.METHOD, applied.selection.scope)
        assertEquals("a", applied.selection.methodName)

        editor.resetFrom(configuration().apply { options.interpreterId = null })
        editor.applyTo(applied)
        assertNull("The project default saves no interpreter", applied.options.interpreterId)
    }
}
