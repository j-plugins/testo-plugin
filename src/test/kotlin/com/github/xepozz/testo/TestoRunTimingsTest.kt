package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoRunTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [TestoRunTimings]. Every mark is passed in explicitly, so these pin the arithmetic rather
 * than the wall clock.
 */
class TestoRunTimingsTest {

    @Test
    fun phasesAddUpToTheTotal() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 1_000)
        timings.noteTestStarted(at = 1_400)
        timings.noteTestFinished("a", durationMs = 300, at = 1_800)
        timings.noteTestFinished("b", durationMs = 250, at = 2_100)
        timings.noteFinish(at = 2_600)

        val spans = timings.snapshot()
        assertTrue(spans.finished)
        assertEquals(1_600, spans.totalMs)
        assertEquals(400, spans.startupMs)   // start → first test
        assertEquals(700, spans.testsMs)     // first test → last test event
        assertEquals(500, spans.teardownMs)  // last test event → end
        assertEquals(550, spans.summedTestsMs)
        // The three wall-clock phases account for the whole run; the summed figure is not one of them.
        assertEquals(spans.totalMs, spans.startupMs + spans.testsMs + spans.teardownMs)
    }

    @Test
    fun concurrentTestsSumPastTheWindowTheyRanIn() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 0)
        timings.noteTestStarted(at = 0)
        // Two five-second tests on two fibers, both done five seconds in.
        timings.noteTestFinished("a", durationMs = 5_000, at = 5_000)
        timings.noteTestFinished("b", durationMs = 5_000, at = 5_000)
        timings.noteFinish(at = 5_000)

        val spans = timings.snapshot()
        assertEquals(5_000, spans.testsMs)
        assertEquals(10_000, spans.summedTestsMs)
        assertEquals(2.0, spans.parallelism!!, 0.001)
    }

    @Test
    fun sequentialTestsReportNoParallelism() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 0)
        timings.noteTestStarted(at = 0)
        timings.noteTestFinished("a", durationMs = 2_000, at = 2_000)
        timings.noteTestFinished("b", durationMs = 2_000, at = 4_000)
        timings.noteFinish(at = 4_000)

        assertEquals(1.0, timings.snapshot().parallelism!!, 0.001)
    }

    @Test
    fun aRestatedTestReplacesItsOwnDuration() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 0)
        timings.noteTestStarted(at = 0)
        timings.noteTestFinished("a", durationMs = 1_000, at = 1_000)
        timings.noteTestFinished("a", durationMs = 1_500, at = 2_000)
        timings.noteFinish(at = 2_000)

        assertEquals(1_500, timings.snapshot().summedTestsMs)
    }

    @Test
    fun aRunningRunCountsUpToNowAndOwesNoTeardown() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 1_000)
        timings.noteTestStarted(at = 1_200)
        timings.noteTestFinished("a", durationMs = 100, at = 1_500)

        val spans = timings.snapshot(now = 3_000)
        assertFalse(spans.finished)
        assertEquals(2_000, spans.totalMs)
        assertEquals(200, spans.startupMs)
        // Still running, so the window reaches now rather than stopping at the last test that happened to finish.
        assertEquals(1_800, spans.testsMs)
        assertEquals(0, spans.teardownMs)
    }

    @Test
    fun aRunThatNeverReachedATestReportsOnlyItsTotal() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 1_000)
        timings.noteFinish(at = 1_900)

        val spans = timings.snapshot()
        assertEquals(900, spans.totalMs)
        assertEquals(0, spans.startupMs)
        assertEquals(0, spans.testsMs)
        assertEquals(0, spans.teardownMs)
        assertNull(spans.parallelism)
    }

    @Test
    fun theFirstFinishWins() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 0)
        // Testing finishing and the process exiting are the same moment reported twice.
        timings.noteFinish(at = 500)
        timings.noteFinish(at = 900)

        assertEquals(500, timings.snapshot().totalMs)
    }

    @Test
    fun nothingIsReportedBeforeTheRunStarts() {
        val spans = TestoRunTimings().snapshot(now = 5_000)
        assertFalse(spans.finished)
        assertEquals(0, spans.totalMs)
    }

    @Test
    fun clearForgetsTheWholeRun() {
        val timings = TestoRunTimings()
        timings.noteStart(at = 0)
        timings.noteTestStarted(at = 10)
        timings.noteTestFinished("a", durationMs = 5, at = 20)
        timings.noteFinish(at = 30)
        timings.clear()

        assertFalse(timings.isFinished())
        assertEquals(0, timings.snapshot(now = 100).totalMs)
    }
}
