package com.github.xepozz.testo.runs

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for how an archived run is presented in the history chooser: its kind (icon) and its result line. */
class TestoRunHistoryPresentationTest {

    private fun manifest(vararg statuses: Pair<String, Int>) =
        TestoRunManifest(statuses = statuses.toMap())

    @Test
    fun executorIdDecidesTheRunKind() {
        assertEquals(TestoRunKind.COVERAGE, runKindOf("Coverage"))
        assertEquals(TestoRunKind.DEBUG, runKindOf("Debug"))
        assertEquals(TestoRunKind.RUN, runKindOf("Run"))
    }

    @Test
    fun anArchiveWithoutAnExecutorReadsAsAPlainRun() {
        assertEquals(TestoRunKind.RUN, runKindOf(""))
        assertEquals(TestoRunKind.RUN, runKindOf(null))
    }

    @Test
    fun aStoppedRunIsCancelledWhateverItsTestsSaid() {
        assertEquals(TestoRunOutcome.CANCELLED, runOutcomeOf(TestoRunManifest(cancelled = true, exitCode = 1, statuses = mapOf("failed" to 2))))
    }

    @Test
    fun theExitCodeDecidesBetweenPassedAndFailed() {
        assertEquals(TestoRunOutcome.FAILED, runOutcomeOf(TestoRunManifest(exitCode = 255, statuses = mapOf("passed" to 3))))
        assertEquals(TestoRunOutcome.PASSED, runOutcomeOf(TestoRunManifest(exitCode = 0, statuses = mapOf("passed" to 3))))
    }

    @Test
    fun anArchiveWithoutAnExitCodeIsJudgedByItsTests() {
        assertEquals(TestoRunOutcome.FAILED, runOutcomeOf(manifest("passed" to 3, "error" to 1)))
        assertEquals(TestoRunOutcome.PASSED, runOutcomeOf(manifest("passed" to 3, "risky" to 1)))
    }

    @Test
    fun failuresAreCountedAcrossEveryProblemStatus() {
        // error and aborted are failures too; risky, flaky and skipped are not.
        val summary = runResultSummary(
            manifest("passed" to 100, "failed" to 40, "error" to 1, "aborted" to 1, "risky" to 2, "skipped" to 1)
        )
        assertEquals("145 total, 42 failed", summary)
    }

    @Test
    fun aCleanRunSaysSo() {
        assertEquals("12 total, all passed", runResultSummary(manifest("passed" to 10, "skipped" to 2)))
    }

    @Test
    fun aRunThatReportedNoTestsHasNoTally() {
        assertEquals("no tests", runResultSummary(manifest()))
    }
}
