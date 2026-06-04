package com.github.xepozz.testo.ui

import com.github.xepozz.testo.tests.console.isTestoConsole
import com.intellij.execution.ConsoleFolding
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

class TestoStackTraceConsoleFolding : ConsoleFolding() {
    // Are we inside a foldable internal stack-trace run? A single ConsoleFolding instance is shared across all
    // consoles and the platform feeds it lines without any per-console reset, so the state must clear itself on the
    // first line that is NOT a `#N` stack frame. Otherwise the fold leaks past the trace — swallowing later output
    // (and, in the aggregate view, the next test's clickable header) until some unrelated blank line happens by.
    private val inInternalTrace = ThreadLocal.withInitial { false }

    override fun isEnabledForConsole(consoleView: ConsoleView): Boolean = isTestoConsole(consoleView)

    override fun shouldFoldLine(project: Project, line: String): Boolean {
        if (!STACK_FRAME.containsMatchIn(line)) {
            inInternalTrace.set(false) // any non-frame line ends the run
            return false
        }
        // Fold the contiguous frames once the trace dives into engine internals; the frames before that stay visible.
        if (line.contains(INTERNAL_FUNCTION)) inInternalTrace.set(true)
        return inInternalTrace.get()
    }

    override fun getPlaceholderText(project: Project, lines: List<String>) = "[internal stacktrace ${lines.size} lines]"

    override fun shouldBeAttachedToThePreviousLine() = false

    private companion object {
        private const val INTERNAL_FUNCTION = "[internal function]:"
        private val STACK_FRAME = Regex("^#\\d+\\s")
    }
}
