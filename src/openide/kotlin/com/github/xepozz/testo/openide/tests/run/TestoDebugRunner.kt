package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.runs.TestoRunArchiver
import com.github.xepozz.testo.runs.isStoppedFromIde
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoConsoleAugmenter
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import org.jetbrains.concurrency.Promise
import ru.openide.openphp.debugger.launch.PhpDebugLaunch
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment

/**
 * Debugs a Testo configuration. Off the EDT: the launch environment, the debug launch with its listener, the command —
 * PHP's debug arguments, then the binary, then Testo's — and the process, from the handler that releases the debuggee
 * on Stop. On the EDT: Testo's console with its channel tabs and run archive, then the debug session. Any failure before
 * the session abandons the debug launch and destroys the process.
 */
class TestoDebugRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "TestoDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && testoConfigurationOf(profile) != null

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = testoConfigurationOf(environment.runProfile)
            ?: throw ExecutionException("Not a Testo run configuration: ${environment.runProfile.name}")
        return inBackgroundThenOnEdt(
            prepare = { prepare(configuration) },
            show = { debugged -> show(configuration, environment, debugged) },
            abandon = { debugged -> debugged.abandon() },
        )
    }

    internal class Debugged(val debug: PhpDebugLaunch, val run: TestoRunConfiguration.PreparedRun) {
        fun abandon() {
            debug.abandon()
            run.handler.destroyProcess()
        }
    }

    internal fun show(configuration: TestoRunConfiguration, environment: ExecutionEnvironment, debugged: Debugged): RunContentDescriptor? {
        val project = configuration.project
        val handler = debugged.run.handler
        val properties = configuration.createTestConsoleProperties(environment.executor) as TestoConsoleProperties
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(properties.testFrameworkName, handler, properties)
            as SMTRunnerConsoleView
        // The run path wires the channel tabs and the run archive from an execution listener, which never finds a
        // debug session's descriptor; so they are wired here, while the console is at hand.
        TestoConsoleAugmenter.installChannels(project, console, properties, handler)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                TestoRunArchiver.finalizeRun(project, properties, event.exitCode, handler.isStoppedFromIde())
            }
        })
        val descriptor = XDebuggerManager.getInstance(project)
            .newSessionBuilder(debugged.debug.starter(handler, console))
            .environment(environment)
            .startSession()
            .runContentDescriptor
        handler.startNotify()
        return descriptor
    }

    companion object {
        /** Where a debug launch comes from: PHP for OpenIDE. Tests put their own in. */
        @Volatile
        internal var debugLaunchProvider: (Project, PhpLaunchEnvironment) -> PhpDebugLaunch = PhpDebugLaunch::prepare

        /** The handler a debugged process runs under: the one that releases the debuggee on Stop. Tests put their own in. */
        @Volatile
        internal var processHandlerProvider: (GeneralCommandLine) -> ProcessHandler = PhpDebugLaunch::processHandler

        /** The debug launch and the process for [configuration]; the launch is abandoned when the process fails. */
        internal fun prepare(configuration: TestoRunConfiguration): Debugged {
            val environment = TestoRunConfiguration.environmentProvider(configuration.project)
            val debug = debugLaunchProvider(configuration.project, environment)
            val run = try {
                configuration.prepare(
                    environment = environment,
                    phpArguments = debug.phpArguments,
                    environmentVariables = debug.environment,
                    processHandler = processHandlerProvider,
                )
            } catch (e: Throwable) {
                debug.abandon()
                throw e
            }
            return Debugged(debug, run)
        }
    }
}
