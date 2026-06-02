package com.github.xepozz.testo.tests.console

import com.intellij.execution.ConsoleFolding
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

class TestoRepeatedFrameFolding : ConsoleFolding() {
    private val frame = Regex("^#\\d+ (.*)$")
    private val previousFrame = ThreadLocal<String?>()

    override fun isEnabledForConsole(consoleView: ConsoleView): Boolean = isTestoConsole(consoleView)

    override fun shouldFoldLine(project: Project, line: String): Boolean {
        val rest = frame.matchEntire(line)?.groupValues?.get(1)
        if (rest == null) {
            previousFrame.set(null)
            return false
        }
        val isRepeat = rest == previousFrame.get()
        previousFrame.set(rest)
        return isRepeat
    }

    override fun getPlaceholderText(project: Project, lines: List<String>): String =
        "  (repeated ${lines.size + 1} times)"

    override fun shouldBeAttachedToThePreviousLine(): Boolean = true
}
