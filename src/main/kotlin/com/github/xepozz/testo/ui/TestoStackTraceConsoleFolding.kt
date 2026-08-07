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
    private val pastTestFrame = ThreadLocal.withInitial { false }

    override fun isEnabledForConsole(consoleView: ConsoleView): Boolean = isTestoConsole(consoleView)

    override fun shouldFoldLine(project: Project, line: String): Boolean {
        if (!STACK_FRAME.containsMatchIn(line)) {
            pastTestFrame.set(false) // any non-frame line ends the run
            return false
        }
        // Already past the test's own frame — fold everything that follows.
        if (pastTestFrame.get()) return true
        // The test's own frame is an [internal function] calling the test method/function via -> or :: or a bare
        // namespaced function. Show it (return false) but fold every subsequent frame.
        if (line.contains(INTERNAL_FUNCTION) && TEST_CALL.containsMatchIn(line)) {
            pastTestFrame.set(true)
            return false // the test frame itself stays visible
        }
        return false
    }

    override fun getPlaceholderText(project: Project, lines: List<String>) = "[internal stacktrace ${lines.size} lines]"

    override fun shouldBeAttachedToThePreviousLine() = false

    private companion object {
        private const val INTERNAL_FUNCTION = "[internal function]:"
        private val STACK_FRAME = Regex("^#\\d+\\s")
        // Matches a namespaced test call: Ns\Class->method(), Ns\Class::method(), or Ns\function().
        // The backslash in the FQN distinguishes test code from engine internals like {closure}().
        private val TEST_CALL = Regex("""\w+\\\w+.*\(""")
    }
}
