package com.github.xepozz.testo

import com.github.xepozz.testo.ui.TestoStackTraceConsoleFolding
import com.intellij.openapi.project.Project
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Plain JUnit4 tests for [TestoStackTraceConsoleFolding]. The folding only inspects the line text and ignores the
 * [Project], so a no-op proxy stands in for it (none of its methods are ever invoked).
 *
 * These tests pin the intended contract: only the contiguous PHP stack-trace block (the `#N` frames starting at the
 * first `[internal function]:`) is folded, and the fold never bleeds into surrounding output. They currently FAIL —
 * they capture the over-folding bug where the "fold" state, kept in a per-instance ThreadLocal and only cleared on a
 * blank line, leaks across lines (and across the single shared instance's consoles) and swallows everything until the
 * next blank line.
 */
class TestoStackTraceConsoleFoldingTest {
    private val project: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> null } as Project

    private fun folding() = TestoStackTraceConsoleFolding()

    // Sanity: folding starts at the first `[internal function]:` line and covers the frames after it.
    @Test
    fun foldsInternalStackTraceFrames() {
        val f = folding()
        assertFalse(f.shouldFoldLine(project, "#0 /app/src/Runner.php(48): Foo->bar()")) // before the marker
        assertTrue(f.shouldFoldLine(project, "#1 [internal function]: Tests\\AssertTest->repeatFail()"))
        assertTrue(f.shouldFoldLine(project, "#2 /app/src/Pipeline.php(110): Runner->{closure}()"))
    }

    // The first line of the message that PRECEDES the trace must not be folded.
    @Test
    fun doesNotFoldTheExceptionHeaderBeforeTheTrace() {
        val f = folding()
        assertFalse(f.shouldFoldLine(project, "Testo\\Assert\\AssertionException"))
        assertFalse(f.shouldFoldLine(project, "Failed asserting that 3 <= 2"))
    }

    // BUG: a non-frame line that follows the internal frames (no blank line in between) gets swallowed by the fold.
    @Test
    fun doesNotFoldNonStackTraceTextThatFollowsTheTrace() {
        val f = folding()
        f.shouldFoldLine(project, "#0 [internal function]: Tests\\AssertTest->repeatFail()")
        f.shouldFoldLine(project, "#1 /app/src/Runner.php(48): Foo->bar()")
        // Regular output continues right after the trace, no blank separator:
        assertFalse(
            "plain output after a stack trace must not be folded",
            f.shouldFoldLine(project, "Next test output: results = [1, 2, 3]"),
        )
    }

    // BUG: the fold state must not survive into a later, unrelated line sequence (the platform reuses ONE folding
    // instance for every console, calling shouldFoldLine across all of them on the same thread).
    @Test
    fun foldStateDoesNotLeakIntoLaterOutput() {
        val f = folding()
        // A trace that is the last thing in one console — it does not end with a blank line.
        f.shouldFoldLine(project, "#0 [internal function]: Tests\\AssertTest->repeatFail()")
        f.shouldFoldLine(project, "#1 /app/src/Pipeline.php(110): Runner->run()")
        // Another console's very first line is ordinary output and must render unfolded.
        assertFalse(
            "fold state leaked into a separate console's output",
            f.shouldFoldLine(project, "SELECT * FROM users WHERE id IN (1, 2, 3)"),
        )
    }

    // Sanity: a blank line is supposed to end folding (the only reset the current code has).
    @Test
    fun blankLineEndsFolding() {
        val f = folding()
        assertTrue(f.shouldFoldLine(project, "#0 [internal function]: a()"))
        assertFalse(f.shouldFoldLine(project, ""))
        assertFalse(f.shouldFoldLine(project, "after the blank line"))
    }
}
