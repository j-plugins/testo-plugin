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
