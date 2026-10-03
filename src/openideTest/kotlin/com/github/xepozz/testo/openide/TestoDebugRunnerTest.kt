package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.run.TestoDebugRunner
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.intellij.execution.ExecutionException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import ru.openide.openphp.debugger.launch.PhpDebugLaunch
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import java.io.File

/** A debug run: PHP's debug arguments first, the session on Testo's console and process, nothing left behind on failure. */
class TestoDebugRunnerTest : BasePlatformTestCase() {
    private lateinit var defaultEnvironment: (com.intellij.openapi.project.Project) -> PhpLaunchEnvironment
    private lateinit var defaultDebugLaunch: (com.intellij.openapi.project.Project, PhpLaunchEnvironment) -> PhpDebugLaunch
    private lateinit var defaultHandler: (com.intellij.execution.configurations.GeneralCommandLine) -> ProcessHandler
    private lateinit var app: File

    /** Records what the runner does with it: whose process and console the session starts on, and abandonment. */
    private class FakeDebugLaunch : PhpDebugLaunch {
        var abandoned = false
        var startedOn: Pair<ProcessHandler, ConsoleView>? = null

        override val sessionKey = "testo-key"
        override val phpArguments = listOf("-dxdebug.mode=debug", "-dxdebug.start_with_request=yes")
        override val environment = mapOf("XDEBUG_SESSION" to "testo-key")

        override fun starter(processHandler: ProcessHandler, console: ConsoleView) = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                startedOn = processHandler to console
                return object : XDebugProcess(session) {
                    override fun getEditorsProvider(): XDebuggerEditorsProvider = throw UnsupportedOperationException()
                    override fun doGetProcessHandler(): ProcessHandler = processHandler
                    override fun createConsole(): com.intellij.execution.ui.ExecutionConsole = console
                    override fun stop() {}
                }
            }
        }

        override fun abandon() {
            abandoned = true
        }
    }

    override fun setUp() {
        super.setUp()
        defaultEnvironment = TestoRunConfiguration.environmentProvider
        defaultDebugLaunch = TestoDebugRunner.debugLaunchProvider
        defaultHandler = TestoDebugRunner.processHandlerProvider
        app = FileUtil.createTempDirectory("testo-debug", null)
        File(app, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }
        TestoRunConfiguration.environmentProvider = { FakeLaunchEnvironment() }
        TestoDebugRunner.processHandlerProvider = { NopProcessHandler() }
    }

    override fun tearDown() {
        try {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
            TestoDebugRunner.debugLaunchProvider = defaultDebugLaunch
            TestoDebugRunner.processHandlerProvider = defaultHandler
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(binary: String = "${FileUtil.toSystemIndependentName(app.path)}/vendor/bin/testo") =
        TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
            name = "Debugged"
            options.binaryPath = binary
            options.workingDirectory = FileUtil.toSystemIndependentName(app.path)
            selection = selection.apply { scope = TestoScope.CONFIGURATION_FILE; logHtml = false }
        }

    fun testDebugArgumentsComeFirstAndItsVariablesAreSet() {
        val debug = FakeDebugLaunch()
        TestoDebugRunner.debugLaunchProvider = { _, _ -> debug }

        val debugged = TestoDebugRunner.prepare(configuration())

        val arguments = debugged.run.command.parametersList.list
        assertEquals(listOf("-dxdebug.mode=debug", "-dxdebug.start_with_request=yes"), arguments.take(2))
        assertTrue(arguments[2], arguments[2].endsWith("/vendor/bin/testo"))
        assertEquals("run", arguments[3])
        assertEquals("testo-key", debugged.run.command.environment["XDEBUG_SESSION"])
        assertFalse(debug.abandoned)
    }

    fun testAFailureBeforeTheSessionAbandonsTheDebugLaunch() {
        val debug = FakeDebugLaunch()
        TestoDebugRunner.debugLaunchProvider = { _, _ -> debug }

        thrown<ExecutionException> { TestoDebugRunner.prepare(configuration(binary = "/nowhere/testo")) }

        assertTrue("The listener is let go", debug.abandoned)
    }

    fun testTheSessionStartsOnTestosConsoleAndProcess() {
        val debug = FakeDebugLaunch()
        TestoDebugRunner.debugLaunchProvider = { _, _ -> debug }
        val configuration = configuration()
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), configuration).build()
        val debugged = TestoDebugRunner.prepare(configuration)

        val descriptor = TestoDebugRunner().show(configuration, environment, debugged)
        try {
            val (handler, console) = checkNotNull(debug.startedOn) { "The session did not start" }
            assertSame(debugged.run.handler, handler)
            assertSame(descriptor?.executionConsole, console)
            assertTrue("The process is started once the session is", handler.isStartNotified)
        } finally {
            XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() }
            descriptor?.let { Disposer.dispose(it) }
        }
    }
}
