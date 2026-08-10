package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoTestStatus
import com.github.xepozz.testo.tests.console.testoNodeIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which icon a results-tree node ends up with. The renderer needs a tree, so the choice itself lives in a function
 * that takes only what the decision turns on.
 */
class TestoTestTreeDecoratorTest {

    @Test
    fun aFinishedTestWearsTheIconOfItsStatus() {
        assertSame(TestoIcons.Status.FLAKY, testoNodeIcon(TestoTestStatus.FLAKY, isRoot = false, verdict = null))
        assertSame(TestoIcons.Status.RISKY, testoNodeIcon(TestoTestStatus.RISKY, isRoot = false, verdict = null))
        assertSame(TestoIcons.Status.PASSED, testoNodeIcon(TestoTestStatus.PASSED, isRoot = false, verdict = null))
    }

    @Test
    fun everyStatusHasOneToShow() {
        // The tree and the toolbar summary have to speak one language: a counter reading "42 flaky" is only useful
        // while every one of those 42 is findable by the same icon.
        val icons = TestoTestStatus.entries.map { testoNodeIcon(it, isRoot = false, verdict = null) }
        assertEquals(TestoTestStatus.entries.size, icons.filterNotNull().size)
    }

    @Test
    fun aGroupNodeWearsItsRolledUpStatusToo() {
        // A case, a DataProvider batch and a suite of the run all close with testSuiteFinished carrying their
        // children's outcome; a tree where only the leaves changed would show two icon families at once.
        assertSame(TestoIcons.Status.FAILED, testoNodeIcon(TestoTestStatus.FAILED, isRoot = false, verdict = null))
    }

    @Test
    fun aNodeWithNoStatusKeepsThePlatformIcon() {
        // Nothing was reported and the platform's own reading of the node came back empty — a test not yet started,
        // or one still running, whose animated icon must not be replaced by a still one.
        assertNull(testoNodeIcon(null, isRoot = false, verdict = null))
    }

    @Test
    fun theRootWearsTheRunsVerdict() {
        // The run has one verdict, and the root node stands for the run — so it takes the icon the toolbar summary's
        // ring turns into, whatever that turned out to be. Grey when the run was stopped before it reached one.
        assertSame(
            TestoIcons.Status.FAILURE,
            testoNodeIcon(null, isRoot = true, verdict = TestoIcons.Status.FAILURE),
        )
        assertSame(
            TestoIcons.Status.SUCCESS_CANCELLED,
            testoNodeIcon(null, isRoot = true, verdict = TestoIcons.Status.SUCCESS_CANCELLED),
        )
    }

    @Test
    fun theRootIgnoresWhateverStatusItWouldHaveHad() {
        // The root is a suite like any other and the store would answer for it, but its children's rolled-up outcome
        // is not the run's verdict: an exit code, and a run cut short, are things no test reported.
        assertSame(
            TestoIcons.Status.SUCCESS,
            testoNodeIcon(TestoTestStatus.FAILED, isRoot = true, verdict = TestoIcons.Status.SUCCESS),
        )
    }

    @Test
    fun aRunStillGoingLeavesTheRootAlone() {
        // No verdict yet, so the platform's animated root icon stays.
        assertNull(testoNodeIcon(TestoTestStatus.PASSED, isRoot = true, verdict = null))
    }
}
