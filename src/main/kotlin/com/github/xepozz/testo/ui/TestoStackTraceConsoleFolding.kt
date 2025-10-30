package com.github.xepozz.testo.ui

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.ConsoleFolding
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.Project

class TestoStackTraceConsoleFolding : ConsoleFolding() {
    override fun isEnabledForConsole(consoleView: ConsoleView): Boolean {
        val context = DataManager.getInstance().getDataContext(consoleView.component)
        val descriptor = context.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR) ?: return false
        val baseConsole = descriptor.executionConsole as? BaseTestsOutputConsoleView ?: return false
        val props = baseConsole.properties
        return props is TestoConsoleProperties
    }

    var foldLine = false

    override fun shouldFoldLine(project: Project, line: String): Boolean {
        if (line.contains("[internal function]:")) {
            foldLine = true
        } else if (line.isEmpty()) {
            foldLine = false
        }

        return foldLine
    }

    override fun getPlaceholderText(project: Project, lines: List<String>) = "[internal stacktrace ${lines.size} lines]"

    override fun shouldBeAttachedToThePreviousLine() = false
}