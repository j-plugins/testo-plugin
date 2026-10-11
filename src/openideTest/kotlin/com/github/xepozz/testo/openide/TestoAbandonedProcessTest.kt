package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.run.terminateAbandoned
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.TimeUnit

/** A launch abandoned before its console leaves no process behind, whether its handler was started or not. */
class TestoAbandonedProcessTest : BasePlatformTestCase() {
    private class RecordingHandler(command: GeneralCommandLine) : KillableColoredProcessHandler(command) {
        var destroyed = 0
        var killed = 0

        override fun destroyProcessImpl() {
            destroyed++
            super.destroyProcessImpl()
        }

        override fun killProcess() {
            killed++
            super.killProcess()
        }
    }

    fun testAProcessWhoseHandlerNeverStartedIsKilled() {
        val handler = RecordingHandler(sleepingCommand())
        try {
            handler.terminateAbandoned()

            assertTrue("The process outlived its abandoned launch", handler.process.waitFor(10, TimeUnit.SECONDS))
            assertFalse("Nothing reads a handler that never started", handler.isStartNotified)
        } finally {
            handler.process.destroyForcibly()
        }
    }

    fun testAStartedHandlerIsStoppedAsTheStopButtonWould() {
        val handler = RecordingHandler(sleepingCommand())
        try {
            handler.startNotify()
            handler.terminateAbandoned()

            assertEquals(1, handler.destroyed)
            assertEquals(0, handler.killed)
        } finally {
            handler.process.destroyForcibly()
            handler.waitFor(10_000)
        }
    }
}

/** A JVM that sleeps for a minute: a process that outlives any test unless something ends it. */
internal fun sleepingCommand(): GeneralCommandLine {
    val source = File(FileUtil.createTempDirectory("testo-sleep", null), "Sleep.java")
    source.writeText("class Sleep { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }")
    val java = File(System.getProperty("java.home"), "bin/java").path
    return GeneralCommandLine(java, source.path)
}
