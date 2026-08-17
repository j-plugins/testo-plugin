package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoConsoleAugmenter
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.SmartList
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.debug.common.PhpDebugProcessFactory
import com.jetbrains.php.run.PhpExecutionUtil
import com.jetbrains.php.testFramework.run.PhpTestDebugRunner
import com.jetbrains.php.testFramework.run.PhpTestRunConfiguration

class TestoDebugRunner : PhpTestDebugRunner<TestoRunConfiguration>(TestoRunConfiguration::class.java) {
    override fun getRunnerId() = "TestoDebugRunner"

    // The platform resolves the runner via profile.getPeer() (AbstractRerunFailedTestsAction#performAction),
    // so a rerun-in-debug launch still routes here but hands us the MyRunProfile wrapper instead of the
    // configuration. Unwrap it before the typed doExecute (which casts to TestoRunConfiguration) runs.
    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val profile = environment.runProfile
        val configuration = (profile as? WrappingRunConfiguration<*>)?.peer as? TestoRunConfiguration
            ?: profile as? TestoRunConfiguration
            ?: return super.doExecute(state, environment)
        return doExecute(configuration, state, environment)
    }

    // Mirrors PhpTestDebugRunner#doExecute, but additionally registers the rerun-failed-tests action on the
    // debug session so the debug toolbar gets the same restart button the run toolbar has (PhpUnitDebugRunner pattern).
    override fun doExecute(
        phpTestRunConfiguration: PhpTestRunConfiguration,
        state: RunProfileState,
        env: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val runConfiguration = phpTestRunConfiguration as TestoRunConfiguration
        val project = runConfiguration.project
        val interpreter = runConfiguration.interpreter
            ?: throw ExecutionException(PhpCommandSettingsBuilder.getInterpreterNotFoundError())

        val debugExtension = PhpProjectConfigurationFacade.getInstance(project)
            .getInterpreterDebugExtension(interpreter)
            ?: throw ExecutionException(PhpBundle.message("debug.error.unknown.debugger.id"))

        val debugServer = debugExtension.startLocalDebugServer(project, interpreter) ?: return null
        val connectionsManager = debugExtension.createDebugConnectionManager()
        val sessionId = debugServer.registerSessionHandler(false, connectionsManager).sessionId

        try {
            val commandLineEnv = debugExtension.getDebugEnv(project, false, sessionId)
            val command = runConfiguration.createCommand(interpreter, commandLineEnv, SmartList(), true)
            val processHandler = runConfiguration.createProcessHandler(project, command, PtyCommandLine.isEnabled())
            ProcessTerminatedListener.attach(processHandler, project)

            val pathProcessor = command.pathProcessor
            val pathMapper = pathProcessor.createPathMapper(project)
            val properties = runConfiguration.createTestConsoleProperties(env.executor) as TestoConsoleProperties
            val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                runConfiguration.frameworkName,
                processHandler,
                properties,
            ) as SMTRunnerConsoleView
            PhpExecutionUtil.addMessageFilters(project, console, pathMapper)

            // The run path wires the channel tabs via TestoConsoleAugmenter (an ExecutionListener), but its
            // descriptor lookup misses the debug session, so install them directly here while we hold the console.
            TestoConsoleAugmenter.installChannels(project, console, properties, processHandler)
            // Same reason for the run archive: the augmenter's processTerminated never finds this session.
            processHandler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    com.github.xepozz.testo.runs.TestoRunArchiver.finalizeRun(project, properties)
                }
            })

            val starter = object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    onSessionStart(session, debugServer, sessionId, connectionsManager, project, interpreter, processHandler)
                    val driver = debugExtension.debugDriver
                    return PhpDebugProcessFactory.forPhpTests(
                        session,
                        sessionId,
                        connectionsManager,
                        driver,
                        console,
                        pathProcessor,
                    )
                }
            }
            val descriptor = startDebugDescriptor(project, env, starter)
            processHandler.startNotify()
            return descriptor
        } catch (e: ExecutionException) {
            debugServer.unregisterSessionHandler(sessionId)
            throw e
        }
    }

    // 2026.2+: the session builder's result carries the descriptor without XDebugSession.getRunContentDescriptor(),
    // which is deprecated there and logs a "split debugger" error. newSessionBuilder is absent on 2025.2, so it is
    // reached by reflection (methods resolved off the public interfaces, never the internal impl); the 2025.2 fallback
    // is the classic API, whose descriptor accessor is safe on a platform with no split debugger.
    private fun startDebugDescriptor(
        project: Project,
        env: ExecutionEnvironment,
        starter: XDebugProcessStarter,
    ): RunContentDescriptor? {
        val manager = XDebuggerManager.getInstance(project)
        val newSessionBuilder = runCatching {
            XDebuggerManager::class.java.getMethod("newSessionBuilder", XDebugProcessStarter::class.java)
        }.getOrNull()
        if (newSessionBuilder != null) {
            val builderClass = Class.forName("com.intellij.xdebugger.XDebugSessionBuilder")
            val resultClass = Class.forName("com.intellij.xdebugger.XSessionStartedResult")
            val builder = newSessionBuilder.invoke(manager, starter)
            val withEnv = builderClass.getMethod("environment", ExecutionEnvironment::class.java).invoke(builder, env)
            val result = builderClass.getMethod("startSession").invoke(withEnv)
            return resultClass.getMethod("getRunContentDescriptor").invoke(result) as RunContentDescriptor?
        }
        @Suppress("DEPRECATION")
        return manager.startSession(env, starter).runContentDescriptor
    }
}
