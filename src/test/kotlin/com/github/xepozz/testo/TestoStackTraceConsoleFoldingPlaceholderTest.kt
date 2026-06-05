package com.github.xepozz.testo

import com.github.xepozz.testo.ui.TestoStackTraceConsoleFolding
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Plain JUnit4 tests for [TestoStackTraceConsoleFolding]: the placeholder label and STACK_FRAME regex edge cases
 * (multi-digit frame numbers, the required separator after the #N token). The folding ignores the [Project], so a
 * no-op proxy stands in for it. Complements TestoStackTraceConsoleFoldingTest (which covers the fold-state contract).
 */
class TestoStackTraceConsoleFoldingPlaceholderTest {
    private val project: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> null } as Project

    private fun folding() = TestoStackTraceConsoleFolding()

    @Test
    fun placeholderReportsTheNumberOfFoldedLines() {
        val f = folding()
        assertEquals(
            "[internal stacktrace 3 lines]",
            f.getPlaceholderText(project, listOf("a", "b", "c")),
        )
    }

    @Test
    fun placeholderHandlesEmptyList() {
        val f = folding()
        assertEquals("[internal stacktrace 0 lines]", f.getPlaceholderText(project, emptyList()))
    }

    @Test
    fun multiDigitFrameNumbersAreRecognizedAndFolded() {
        val f = folding()
        // First the marker turns folding on, then a high-numbered frame must still fold.
        assertTrue(f.shouldFoldLine(project, "#0 [internal function]: a()"))
        assertTrue(f.shouldFoldLine(project, "#12 /app/src/Runner.php(48): Foo->bar()"))
        assertTrue(f.shouldFoldLine(project, "#137 /app/src/Pipeline.php(110): Runner->run()"))
    }

    @Test
    fun aHashWithoutDigitsOrSeparatorIsNotAFrame() {
        val f = folding()
        assertFalse(f.shouldFoldLine(project, "#nope this is prose"))
        assertFalse(f.shouldFoldLine(project, "#0no-space-after-number"))
    }

    @Test
    fun framesBeforeTheInternalMarkerStayVisible() {
        val f = folding()
        // A normal multi-digit frame before any [internal function] marker must remain unfolded.
        assertFalse(f.shouldFoldLine(project, "#42 /app/src/Runner.php(48): Foo->bar()"))
    }
}
