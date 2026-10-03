package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolOutcome
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs a prepared tool to completion, including cancellation between preparation and process creation. */
internal fun executeToolProcess(
    prepared: TestoPreparedTool,
    run: TestoMutationRun,
    stream: TestoMutationStream,
    indicator: ProgressIndicator,
    stopped: () -> Boolean,
    record: (String) -> Unit,
): Int = prepared.use {
    if (stopped() || indicator.isCanceled) throw ProcessCanceledException()
    run.pendingTool = prepared
    prepared.afterRelease {
        val wasUnconfirmed = run.unconfirmedReason != null
        run.releaseTool(prepared)
        if (wasUnconfirmed && run.finishedAt != null && java.nio.file.Files.isDirectory(run.workDir)) {
            TestoMutationArchive.writeSummary(run.workDir, run)
        }
    }
    run.stopper = {
        prepared.stop().thenAccept { outcome ->
            if (outcome is TestoToolOutcome.Unconfirmed) {
                run.appendLog(TestoBundle.message("infection.error.unconfirmed", outcome.reason))
                run.changed()
            }
        }
    }
    var observed = false
    var detachOutput: () -> Unit = {}
    try {
        val handler = prepared.start()
        val header = "$ ${prepared.commandLine}"
        record(header)
        run.appendLog(header)
        val outputLock = Any()
        var acceptingOutput = true
        val listener = object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                synchronized(outputLock) {
                    if (acceptingOutput && outputType != ProcessOutputTypes.SYSTEM) {
                        stream.feed(event.text, ProcessOutputType.isStdout(outputType))
                    }
                }
            }
        }
        handler.addProcessListener(listener)
        detachOutput = {
            synchronized(outputLock) { acceptingOutput = false }
            handler.removeProcessListener(listener)
        }
        handler.startNotify()
        if (stopped()) run.stop()
        // A surviving child can hold the transport's output open after its OS process has exited.
        val outcome = awaitOutcome(prepared.completion) {
            if (indicator.isCanceled && !stopped()) run.stop()
            if (run.isRunning) {
                val done = run.finishedCount()
                val total = run.expected
                indicator.isIndeterminate = total <= 0
                if (total > 0) indicator.fraction = done.toDouble() / total
                indicator.text2 = TestoBundle.message("infection.task.progress", done.toString(), total.toString())
            }
        }
        observed = true
        if (outcome !is TestoToolOutcome.Unconfirmed) {
            // OS exit confirms ownership, not that the handler has delivered all buffered messages.
            while (!handler.waitFor(200L)) {
                if (indicator.isCanceled || stopped()) {
                    run.stop()
                    throw ProcessCanceledException()
                }
            }
        }
        detachOutput()
        stream.flush()
        if (outcome is TestoToolOutcome.Unconfirmed) {
            run.markUnconfirmed(prepared, outcome.reason)
            throw ExecutionException(TestoBundle.message("infection.error.unconfirmed", outcome.reason))
        }
        prepared.collectOutputs()
        when (outcome) {
            is TestoToolOutcome.Exited -> outcome.exitCode
            is TestoToolOutcome.Stopped -> { run.stop(); outcome.exitCode ?: -1 }
            is TestoToolOutcome.NotStarted -> throw ExecutionException(outcome.reason)
            is TestoToolOutcome.Unconfirmed -> error("Handled above")
        }
    } finally {
        detachOutput()
        // A transport failure is only an observation. Stop here only when our own setup/listener code failed.
        if (!observed && !prepared.isTerminationConfirmed) {
            val outcome = awaitOutcome(prepared.stop()) {}
            if (outcome is TestoToolOutcome.Unconfirmed) run.markUnconfirmed(prepared, outcome.reason)
        }
    }
}

private fun awaitOutcome(stage: CompletionStage<TestoToolOutcome>, tick: () -> Unit): TestoToolOutcome {
    val future = stage.toCompletableFuture()
    while (true) {
        try { return future.get(200, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { tick() }
    }
}
