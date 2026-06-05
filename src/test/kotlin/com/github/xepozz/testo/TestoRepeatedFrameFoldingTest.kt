package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoRepeatedFrameFolding
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Plain JUnit4 tests for [TestoRepeatedFrameFolding]. The folding only inspects the line text and ignores the
 * [Project], so a no-op proxy stands in for it (none of its methods are ever invoked).
 *
 * Pins the intended contract: a `#N <rest>` frame folds only when its <rest> equals the immediately preceding frame's
 * <rest>; a non-frame line resets the state; the placeholder reports total repeats = folded count + 1.
 *
 * NOTE: foldStateDoesNotLeakIntoLaterFrameBlock documents the known cross-console state-leak risk of the per-instance
 * ThreadLocal — if the implementation is reworked to be per-console this should still hold.
 */
class TestoRepeatedFrameFoldingTest {
    private val project: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> null } as Project

    private fun folding() = TestoRepeatedFrameFolding()

    @Test
    fun nonFrameLineIsNeverFolded() {
        val f = folding()
        assertFalse(f.shouldFoldLine(project, "Some ordinary test output"))
        assertFalse(f.shouldFoldLine(project, ""))
        assertFalse(f.shouldFoldLine(project, "SELECT * FROM users"))
    }

    @Test
    fun firstFrameOfABlockIsShownNotFolded() {
        val f = folding()
        // The first occurrence of a frame is never a repeat, so it stays visible.
        assertFalse(f.shouldFoldLine(project, "#0 Foo->bar()"))
    }

    @Test
    fun identicalConsecutiveFrameBodyFolds() {
        val f = folding()
        // Same body after the `#N` prefix counts as a repeat even though the numbers differ.
        assertFalse(f.shouldFoldLine(project, "#0 Runner->run()"))
        assertTrue(f.shouldFoldLine(project, "#1 Runner->run()"))
        assertTrue(f.shouldFoldLine(project, "#2 Runner->run()"))
    }

    @Test
    fun differentFrameBodyBreaksTheRepeat() {
        val f = folding()
        assertFalse(f.shouldFoldLine(project, "#0 Runner->run()"))
        assertTrue(f.shouldFoldLine(project, "#1 Runner->run()"))
        // Different body -> not a repeat of the previous, becomes the new baseline.
        assertFalse(f.shouldFoldLine(project, "#2 Pipeline->step()"))
        assertTrue(f.shouldFoldLine(project, "#3 Pipeline->step()"))
    }

    @Test
    fun nonFrameLineResetsRepeatState() {
        val f = folding()
        f.shouldFoldLine(project, "#0 Runner->run()")
        // A non-frame line clears the remembered frame...
        assertFalse(f.shouldFoldLine(project, "interleaved output"))
        // ...so the same frame body afterwards is treated as a fresh first occurrence.
        assertFalse(f.shouldFoldLine(project, "#1 Runner->run()"))
    }

    @Test
    fun placeholderCountsFirstOccurrencePlusFoldedDuplicates() {
        val f = folding()
        // `lines` holds only the folded duplicates; the first frame stays visible, hence +1.
        assertEquals("  (repeated 2 times)", f.getPlaceholderText(project, listOf("#1 Runner->run()")))
        assertEquals(
            "  (repeated 4 times)",
            f.getPlaceholderText(project, listOf("#1 Runner->run()", "#2 Runner->run()", "#3 Runner->run()")),
        )
    }

    @Test
    fun foldedRunIsAttachedToThePreviousLine() {
        assertTrue(folding().shouldBeAttachedToThePreviousLine())
    }
}
