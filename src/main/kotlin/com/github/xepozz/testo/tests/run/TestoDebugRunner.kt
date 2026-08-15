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

            val debugSession = XDebuggerManager.getInstance(project).startSession(env, object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    onSessionStart(session, debugServer, sessionId, connectionsManager, project, interpreter, processHandler)
                    val driver = debugExtension.debugDriver

                    // The rerun-failed action rides the SM test console's own toolbar (added by the framework from our
                    // TestoConsoleProperties), so the debug session needs no extra restart-action wiring here. Pushing
                    // them onto the session toolbar would require the internal XDebugSessionImpl.addRestartActions.
                    return PhpDebugProcessFactory.forPhpTests(
                        session,
                        sessionId,
                        connectionsManager,
                        driver,
                        console,
                        pathProcessor,
                    )
                }
            })
            processHandler.startNotify()
            return debugSession.runContentDescriptor
        } catch (e: ExecutionException) {
            debugServer.unregisterSessionHandler(sessionId)
            throw e
        }
    }
}
