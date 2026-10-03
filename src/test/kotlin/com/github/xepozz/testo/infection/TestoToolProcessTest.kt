package com.github.xepozz.testo.infection

import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolLifecycle
import com.github.xepozz.testo.php.TestoToolOutcome
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LightPlatformTestCase
import org.junit.Assert.*
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class TestoToolProcessTest : LightPlatformTestCase() {
    private open class Handler(private val onStart: Handler.() -> Unit) : ProcessHandler() {
        var destroyed = false
        override fun startNotify() { super.startNotify(); onStart() }
        fun finish() = notifyProcessTerminated(0)
        fun output(text: String) = notifyTextAvailable(text, com.intellij.execution.process.ProcessOutputTypes.STDOUT)
        override fun destroyProcessImpl() { destroyed = true; notifyProcessTerminated(143) }
        override fun detachProcessImpl() = notifyProcessDetached()
        override fun detachIsDefault(): Boolean = false
        override fun getProcessInput(): OutputStream? = null
    }

    private fun mutationRun() = TestoMutationRun("test", Path.of("run"), Path.of("run/infection")) { it }

    private class Lifecycle : TestoToolLifecycle {
        override val completion = CompletableFuture<TestoToolOutcome>()
        var nextStop: TestoToolOutcome = TestoToolOutcome.Unconfirmed("transport lost")
        var stops = 0
        override fun stop() = CompletableFuture.completedFuture(nextStop).also { stops++ }
        override fun abandon() { completion.complete(TestoToolOutcome.NotStarted("abandoned")) }
    }

    fun testTransportExitKeepsInputsAndStopUntilExplicitConfirmation() {
        val lifecycle = Lifecycle()
        val handler = Handler {
            finish()
            lifecycle.completion.complete(TestoToolOutcome.Unconfirmed("transport lost"))
        }
        val run = mutationRun()
        var releases = 0
        var inputs = 0
        var collected = false
        val prepared = TestoPreparedTool("php infection", { handler }, { collected = true }, { releases++ }, lifecycle)
        assertThrows(ExecutionException::class.java) {
            executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, {})
        }
        run.finish(null)
        prepared.afterRelease { inputs++ }
        assertEquals(0, releases)
        assertEquals(0, inputs)
        assertFalse(collected)
        assertTrue(run.isBusy)
        assertNotNull(run.stopper)
        assertEquals(0, lifecycle.stops)
        run.stop()
        assertTrue(run.isBusy)
        assertEquals(0, releases)
        lifecycle.nextStop = TestoToolOutcome.Stopped(null)
        run.stop()
        assertEquals(1, releases)
        assertEquals(1, inputs)
        assertFalse(run.isBusy)
        assertNull(run.stopper)
        assertTrue(prepared.completion.toCompletableFuture().join() is TestoToolOutcome.Unconfirmed)
        assertNull(run.exitCode)
        assertEquals("transport lost", run.failureReason)
        prepared.close()
        assertEquals(1, releases)
    }

    fun testManagedOutcomeSuppliesToolExitCodeInsteadOfTransportCode() {
        val lifecycle = Lifecycle()
        val handler = Handler { finish(); lifecycle.completion.complete(TestoToolOutcome.Exited(23)) }
        val run = mutationRun()
        val prepared = TestoPreparedTool("php infection", { handler }, lifecycle = lifecycle)
        assertEquals(23, executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, {}))
    }

    fun testConfirmedExitWaitsForTheLastBufferedMessages() {
        val lifecycle = Lifecycle()
        var polls = 0
        val handler = object : Handler({ lifecycle.completion.complete(TestoToolOutcome.Exited(0)) }) {
            override fun waitFor(timeoutInMilliseconds: Long): Boolean {
                // Model a reader which still has another batch after the first wait expires.
                if (++polls == 1) return false
                output("##teamcity[testCount count='7']\n")
                finish()
                return true
            }
        }
        val run = mutationRun()
        var collected = false
        val prepared = TestoPreparedTool("php infection", { handler }, { collected = true }, lifecycle = lifecycle)
        try {
            assertEquals(0, executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, {}))
            assertEquals(7, run.expected)
            assertTrue(handler.isProcessTerminated)
            assertTrue(collected)
        } finally { if (!handler.isProcessTerminated) handler.finish() }
    }

    fun testOutputDrainRemainsCancellableWithoutReportingSuccess() {
        val lifecycle = Lifecycle()
        val indicator = EmptyProgressIndicator()
        val handler = object : Handler({ lifecycle.completion.complete(TestoToolOutcome.Exited(0)) }) {
            override fun waitFor(timeoutInMilliseconds: Long): Boolean { indicator.cancel(); return false }
        }
        val run = mutationRun()
        var released = false
        var collected = false
        val prepared = TestoPreparedTool("php infection", { handler }, { collected = true }, { released = true }, lifecycle)
        try {
            assertThrows(ProcessCanceledException::class.java) {
                executeToolProcess(prepared, run, TestoMutationStream(run), indicator, { run.stopRequested }, {})
            }
            assertFalse(collected)
            assertTrue(run.stopRequested)
            assertTrue(released)
        } finally { handler.finish() }
    }

    fun testUnconfirmedOutcomeDoesNotWaitForInheritedOutputPipes() {
        val lifecycle = Lifecycle()
        val handler = Handler { lifecycle.completion.complete(TestoToolOutcome.Unconfirmed("child holds stdout")) }
        val run = mutationRun()
        var recordingClosed = false
        val stream = TestoMutationStream(run, { check(!recordingClosed) { "Recorder already closed" } })
        val prepared = TestoPreparedTool("php infection", { handler }, lifecycle = lifecycle)
        val result = CompletableFuture.supplyAsync {
            runCatching { executeToolProcess(prepared, run, stream, EmptyProgressIndicator(), { false }, {}) }.exceptionOrNull()
        }
        try {
            assertTrue(result.get(2, TimeUnit.SECONDS) is ExecutionException)
            assertFalse(handler.isProcessTerminated)
            assertEquals(0, lifecycle.stops)
            recordingClosed = true
            handler.output("late output\n")
        } finally {
            handler.finish()
            lifecycle.nextStop = TestoToolOutcome.Stopped(null)
            run.stop()
        }
    }

    fun testStopDoesNotWaitForStartToReturn() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val lifecycle = Lifecycle().apply { nextStop = TestoToolOutcome.Stopped(null) }
        val prepared = TestoPreparedTool("php infection", {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            Handler { finish() }
        }, lifecycle = lifecycle)
        val starting = CompletableFuture.supplyAsync { prepared.start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val stopping = CompletableFuture.supplyAsync { prepared.stop().toCompletableFuture().join() }
            assertTrue(stopping.get(2, TimeUnit.SECONDS) is TestoToolOutcome.Stopped)
        } finally {
            proceed.countDown()
            starting.get(2, TimeUnit.SECONDS).startNotify()
            prepared.close()
        }
    }

    fun testDelayedAbandonDoesNotReleaseBeforeConfirmation() {
        val result = CompletableFuture<TestoToolOutcome>()
        var abandoned = false
        val lifecycle = object : TestoToolLifecycle {
            override val completion = result
            override fun stop() = result
            override fun abandon() { abandoned = true }
        }
        var released = false
        val prepared = TestoPreparedTool("php infection", { error("must not start") }, release = { released = true }, lifecycle = lifecycle)
        prepared.close()
        assertTrue(abandoned)
        assertFalse(released)
        result.complete(TestoToolOutcome.NotStarted("cancelled"))
        assertTrue(released)
        assertThrows(IllegalStateException::class.java) { prepared.start() }
    }

    fun testFailedStartWithUnconfirmedStopStillKeepsResources() {
        val lifecycle = Lifecycle()
        val run = mutationRun()
        var released = false
        val prepared = TestoPreparedTool("php infection", { throw ExecutionException("start failed") },
            release = { released = true }, lifecycle = lifecycle)
        assertThrows(ExecutionException::class.java) {
            executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, {})
        }
        run.finish(null)
        assertFalse(released)
        assertTrue(run.isBusy)
        lifecycle.nextStop = TestoToolOutcome.NotStarted("verified no process")
        run.stop()
        assertTrue(released)
        assertFalse(run.isBusy)
    }

    fun testStopBeforeNotifyUsesAdapterControl() {
        val lifecycle = Lifecycle().apply { nextStop = TestoToolOutcome.Stopped(143) }
        val handler = Handler { finish() }
        val run = mutationRun()
        val prepared = TestoPreparedTool("php infection", {
            run.stop()
            lifecycle.completion.complete(TestoToolOutcome.Stopped(143))
            handler
        }, lifecycle = lifecycle)
        assertEquals(143, executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { run.stopRequested }, {}))
        assertEquals(1, lifecycle.stops)
        assertFalse(handler.destroyed)
        assertNull(run.stopper)
    }

    fun testStoppedPreparationCreatesNoProcessAndReleasesResources() {
        var started = false
        var released = 0
        val run = mutationRun()
        val prepared = TestoPreparedTool("php infection", { started = true; Handler { finish() } }, release = { released++ })
        assertThrows(ProcessCanceledException::class.java) {
            executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { true }, {})
        }
        prepared.close()
        assertFalse(started)
        assertEquals(1, released)
    }

    fun testSuccessfulProcessCollectsOutputsBeforeReleasing() {
        val calls = mutableListOf<String>()
        val run = mutationRun()
        val prepared = TestoPreparedTool("php infection", { Handler { finish() } }, { calls += "collect" }, { calls += "release" })
        assertEquals(0, executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, { calls += "command" }))
        assertEquals(listOf("command", "collect", "release"), calls)
        assertNull(run.stopper)
    }

    fun testCancellationTerminatesOwnedProcess() {
        val indicator = EmptyProgressIndicator()
        val handler = Handler { indicator.cancel() }
        val run = mutationRun()
        var released = false
        val prepared = TestoPreparedTool("php infection", { handler }, release = { released = true })
        assertEquals(143, executeToolProcess(prepared, run, TestoMutationStream(run), indicator, { run.stopRequested }, {}))
        assertTrue(handler.destroyed)
        assertTrue(run.stopRequested)
        assertTrue(released)
    }

    fun testFailureAfterProcessCreationTerminatesItAndReleasesResources() {
        val handler = Handler { error("start failed") }
        val run = mutationRun()
        var released = false
        val prepared = TestoPreparedTool("php infection", { handler }, release = { released = true })
        assertThrows(IllegalStateException::class.java) {
            executeToolProcess(prepared, run, TestoMutationStream(run), EmptyProgressIndicator(), { false }, {})
        }
        assertTrue(handler.destroyed)
        assertTrue(released)
        assertNull(run.stopper)
    }
}
