package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.console.ChannelOutputStore
import com.github.xepozz.testo.tests.console.LogLevelFilter
import com.github.xepozz.testo.tests.console.TestoOutputToGeneralEventsConverter
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.phpunit.PhpPsiLocationWithDataSet
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import one.util.streamex.StreamEx

class TestoConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
    val pathMapper: PhpPathMapper,
) : SMTRunnerConsoleProperties(config, TestoBundle.message("testo.local.run.display.name"), executor),
    SMCustomMessagesParsing {
    val myTestLocator = TestoTestLocator(pathMapper)

    val channelStore = ChannelOutputStore()

    val levelFilter = LogLevelFilter()

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter =
        TestoOutputToGeneralEventsConverter(testFrameworkName, consoleProperties, channelStore, levelFilter)

    override fun getTestStackTraceParser(url: String, proxy: SMTestProxy, project: Project) =
        TestoStackTraceParser.parse(url, proxy.stacktrace, proxy.errorMessage, testLocator, project)

    override fun getTestLocator() = this.myTestLocator

    override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? {
        if (location is PhpPsiLocationWithDataSet<*> && location.getPsiElement() !is Method) {
            return location.navigatable
        } else {
            val reversedStackTrace = StringUtil.splitByLinesKeepSeparators(stacktrace)
                .reversed()
                .filter { it.isNotEmpty() }
                .joinToString("")
            return super.getErrorNavigatable(location, reversedStackTrace)
        }
    }

    override fun isPrintTestingStartedTime() = true

    // Promote Expand All / Collapse All onto the test toolbar; they bind to the TreeExpander it already supplies.
    override fun appendAdditionalActions(
        actionGroup: com.intellij.openapi.actionSystem.DefaultActionGroup,
        parent: javax.swing.JComponent,
        target: TestConsoleProperties,
    ) {
        super.appendAdditionalActions(actionGroup, parent, target)
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        listOfNotNull(actionManager.getAction("ExpandAll"), actionManager.getAction("CollapseAll"))
            .forEach(actionGroup::add)
    }
}