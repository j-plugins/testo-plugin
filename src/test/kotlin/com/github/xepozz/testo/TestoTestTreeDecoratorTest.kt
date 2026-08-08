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
        assertSame(TestoIcons.Status.FLAKY, testoNodeIcon(TestoTestStatus.FLAKY, isRoot = false))
        assertSame(TestoIcons.Status.RISKY, testoNodeIcon(TestoTestStatus.RISKY, isRoot = false))
        assertSame(TestoIcons.Status.PASSED, testoNodeIcon(TestoTestStatus.PASSED, isRoot = false))
    }

    @Test
    fun everyStatusHasOneToShow() {
        // The tree and the toolbar summary have to speak one language: a counter reading "42 flaky" is only useful
        // while every one of those 42 is findable by the same icon.
        val icons = TestoTestStatus.entries.map { testoNodeIcon(it, isRoot = false) }
        assertEquals(TestoTestStatus.entries.size, icons.filterNotNull().size)
    }

    @Test
    fun aGroupNodeWearsItsRolledUpStatusToo() {
        // A case, a DataProvider batch and a suite of the run all close with testSuiteFinished carrying their
        // children's outcome; a tree where only the leaves changed would show two icon families at once.
        assertSame(TestoIcons.Status.FAILED, testoNodeIcon(TestoTestStatus.FAILED, isRoot = false))
    }

    @Test
    fun aNodeWithNoStatusKeepsThePlatformIcon() {
        // Nothing was reported and the platform's own reading of the node came back empty — a test not yet started,
        // or one still running, whose animated icon must not be replaced by a still one.
        assertNull(testoNodeIcon(null, isRoot = false))
    }

    @Test
    fun theRootKeepsThePlatformIcon() {
        // The root is the run, not a node of it: the toolbar summary states its verdict already.
        assertNull(testoNodeIcon(TestoTestStatus.FAILED, isRoot = true))
        assertNull(testoNodeIcon(null, isRoot = true))
    }
}
