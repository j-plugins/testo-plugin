package com.github.xepozz.testo.openide.tests.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/** Runs a Testo configuration: the process is prepared off the EDT, the console is shown on it. */
class TestoProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "TestoProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultRunExecutor.EXECUTOR_ID && testoConfigurationOf(profile) != null

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        FileDocumentManager.getInstance().saveAllDocuments()
        val configuration = testoConfigurationOf(environment.runProfile)
            ?: throw ExecutionException("Not a Testo run configuration: ${environment.runProfile.name}")
        val runState = state as? TestoRunState ?: TestoRunState(configuration, environment)
        return inBackgroundThenOnEdt(
            prepare = { configuration.prepare() },
            show = { prepared -> RunContentBuilder(runState.show(prepared), environment).showRunContent(environment.contentToReuse) },
            abandon = { prepared -> prepared.handler.destroyProcess() },
        )
    }
}

/** The Testo configuration behind [profile], also when a rerun of failed tests wraps it. */
internal fun testoConfigurationOf(profile: RunProfile): TestoRunConfiguration? =
    profile as? TestoRunConfiguration ?: (profile as? WrappingRunConfiguration<*>)?.peer as? TestoRunConfiguration

/**
 * Runs [prepare] on a pooled thread — the command, the process, anything that waits on a process or the file system —
 * then [show] on the EDT with what it returned. A failure on either side rejects the promise, which the platform shows
 * as the run's error; [abandon] cleans up what [prepare] made when [show] fails.
 */
internal fun <T> inBackgroundThenOnEdt(
    prepare: () -> T,
    show: (T) -> RunContentDescriptor?,
    abandon: (T) -> Unit = {},
): Promise<RunContentDescriptor?> {
    val promise = AsyncPromise<RunContentDescriptor?>()
    val application = ApplicationManager.getApplication()
    application.executeOnPooledThread {
        val prepared = try {
            prepare()
        } catch (e: Throwable) {
            promise.setError(e)
            return@executeOnPooledThread
        }
        application.invokeLater {
            try {
                promise.setResult(show(prepared))
            } catch (e: Throwable) {
                abandon(prepared)
                promise.setError(e)
            }
        }
    }
    return promise
}
