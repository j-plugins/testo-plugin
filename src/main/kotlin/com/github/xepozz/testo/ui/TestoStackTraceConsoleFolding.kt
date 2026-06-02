package com.github.xepozz.testo.ui

import com.github.xepozz.testo.tests.console.isTestoConsole
import com.intellij.execution.ConsoleFolding
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

class TestoStackTraceConsoleFolding : ConsoleFolding() {
    private val foldLine = ThreadLocal.withInitial { false }

    override fun isEnabledForConsole(consoleView: ConsoleView): Boolean = isTestoConsole(consoleView)

    override fun shouldFoldLine(project: Project, line: String): Boolean {
        if (line.contains("[internal function]:")) {
            foldLine.set(true)
        } else if (line.isEmpty()) {
            foldLine.set(false)
        }
        return foldLine.get()
    }

    override fun getPlaceholderText(project: Project, lines: List<String>) = "[internal stacktrace ${lines.size} lines]"

    override fun shouldBeAttachedToThePreviousLine() = false
}
