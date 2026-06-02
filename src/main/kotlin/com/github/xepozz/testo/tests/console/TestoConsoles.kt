package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.LangDataKeys

fun isTestoConsole(consoleView: ConsoleView): Boolean {
    val context = DataManager.getInstance().getDataContext(consoleView.component)
    val descriptor = context.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR) ?: return false
    val baseConsole = descriptor.executionConsole as? BaseTestsOutputConsoleView ?: return false
    return baseConsole.properties is TestoConsoleProperties
}
