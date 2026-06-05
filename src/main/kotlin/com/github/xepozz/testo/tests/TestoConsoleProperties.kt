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

    // Guards the channel-tab install: set once whoever wires the tabs first (the run-path ExecutionListener or the
    // debug runner, which installs them directly), so the other side is a no-op instead of a double install.
    var channelsInstalled = false

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

    // The log-level filter belongs on the test results toolbar's visible row. Adding it here (rather than via
    // appendAdditionalActions, which the platform routes into the gear submenu) puts it among the primary actions at
    // construction time — so it survives the snapshot that RunTab merges into the run tab's toolbar, and it shows in
    // the standalone debug console toolbar too.
    public override fun createImportActions(): Array<com.intellij.openapi.actionSystem.AnAction> =
        arrayOf(
            com.github.xepozz.testo.tests.console.TestoLogLevelFilterAction(levelFilter),
            *(super.createImportActions() ?: emptyArray()),
        )
}