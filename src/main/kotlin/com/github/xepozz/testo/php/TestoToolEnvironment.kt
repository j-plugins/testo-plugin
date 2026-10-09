package com.github.xepozz.testo.php

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** A run's interpreter, options, environment and working directory, captured before its process starts. */
interface TestoToolEnvironment {
    val testoExecutable: String
    val workingDirectory: String

    /** A host path, or null when the interpreter's file has no host view. */
    fun toLocal(path: String): String?

    /** Prepares file visibility and a process factory. Blocking; called off the EDT. */
    fun prepare(request: TestoToolRequest): TestoPreparedTool
}

/** The complete directory is an input; outputs whose environment cannot expose them are omitted from [arguments]. */
class TestoToolRequest(
    val script: String,
    val inputDirectory: Path,
    val outputFiles: List<Path>,
    val arguments: (String, Map<Path, String>) -> List<String>,
)

/** A transport's exit is not necessarily the tool's exit. Only confirmed outcomes permit deleting inputs. */
sealed interface TestoToolOutcome {
    data class Exited(val exitCode: Int) : TestoToolOutcome
    data class Stopped(val exitCode: Int?) : TestoToolOutcome
    data class NotStarted(val reason: String) : TestoToolOutcome
    data class Unconfirmed(val reason: String) : TestoToolOutcome
}

/** Adapter-owned process control; stop is nonblocking and may be retried after an unconfirmed outcome. */
interface TestoToolLifecycle {
    val completion: CompletionStage<TestoToolOutcome>
    fun stop(): CompletionStage<TestoToolOutcome>
    fun abandon()
}

/** Owns only this launch's resources. Closing requests cleanup, but does not establish that cleanup is safe. */
class TestoPreparedTool(
    var commandLine: String,
    private val process: () -> ProcessHandler,
    private val collect: () -> Unit = {},
    private val release: () -> Unit = {},
    private val lifecycle: TestoToolLifecycle? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var cancelledBeforeStart = false
    private val observed = CompletableFuture<TestoToolOutcome>()
    private val confirmed = CompletableFuture<TestoToolOutcome>()
    private val cleanup = CompletableFuture<Unit>()
    private var stopAttempt: CompletableFuture<TestoToolOutcome>? = null
    private val handlerReady = CompletableFuture<ProcessHandler?>()
    @Volatile private var handler: ProcessHandler? = null

    val completion: CompletionStage<TestoToolOutcome> get() = observed
    val isTerminationConfirmed: Boolean get() = confirmed.isDone

    init {
        lifecycle?.completion?.whenComplete { outcome, error -> observe(outcome, error) }
    }

    private fun observe(outcome: TestoToolOutcome?, error: Throwable?): TestoToolOutcome {
        val result = outcome ?: TestoToolOutcome.Unconfirmed(error?.message ?: "Tool completion was not confirmed")
        if (result !is TestoToolOutcome.Unconfirmed) confirmed.complete(result)
        observed.complete(result)
        return result
    }

    fun start(): ProcessHandler {
        synchronized(this) {
            check(!closed.get() && !cancelledBeforeStart && started.compareAndSet(false, true)) { "Tool launch is no longer available" }
        }
        try {
            return process().also { value ->
                handler = value
                if (lifecycle == null) value.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        observe(TestoToolOutcome.Exited(event.exitCode), null)
                    }
                })
                handlerReady.complete(value)
            }
        } catch (e: Throwable) {
            if (lifecycle == null) observe(TestoToolOutcome.NotStarted(e.message.orEmpty()), null)
            handlerReady.complete(null)
            throw e
        }
    }

    fun stop(): CompletionStage<TestoToolOutcome> {
        if (confirmed.isDone) return confirmed
        lifecycle?.let {
            return try { it.stop().handle(::observe) }
            catch (e: Exception) { CompletableFuture.completedFuture(observe(null, e)) }
        }
        val attempt = synchronized(this) {
            stopAttempt?.takeUnless { it.isDone }?.let { return it }
            if (!started.get()) cancelledBeforeStart = true
            CompletableFuture<TestoToolOutcome>().also { stopAttempt = it }
        }
        if (!started.get()) AppExecutorUtil.getAppExecutorService().execute { close() }
        handlerReady.thenAcceptAsync({ value ->
            if (value == null) {
                attempt.complete(observe(TestoToolOutcome.NotStarted("Cancelled before start"), null))
                return@thenAcceptAsync
            }
            val outcome = try {
                value.destroyProcess()
                if (!value.waitFor(5_000L) && value is KillableProcessHandler && value.canKillProcess()) value.killProcess()
                if (value.waitFor(5_000L)) TestoToolOutcome.Stopped(value.exitCode)
                else TestoToolOutcome.Unconfirmed("The tool did not stop")
            } catch (e: Exception) {
                TestoToolOutcome.Unconfirmed(e.message ?: "The tool did not stop")
            }
            observe(outcome, null)
            attempt.complete(outcome)
        }, AppExecutorUtil.getAppExecutorService())
        return attempt
    }

    fun collectOutputs() = collect()

    /** Also protects the caller's original input copy when the adapter staged another copy of it. */
    fun afterRelease(action: () -> Unit) { cleanup.thenRun(action) }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!started.get()) {
            if (lifecycle != null) lifecycle.abandon()
            else {
                observe(TestoToolOutcome.NotStarted("Cancelled before start"), null)
                handlerReady.complete(null)
            }
        }
        confirmed.thenRun {
            try { release() }
            catch (e: Exception) { Logger.getInstance(TestoPreparedTool::class.java).warn("Could not release tool resources", e) }
            finally { cleanup.complete(Unit) }
        }
    }
}
