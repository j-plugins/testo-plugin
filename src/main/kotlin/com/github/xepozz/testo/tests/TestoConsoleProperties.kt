package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.phpunit.PhpPsiLocationWithDataSet
import one.util.streamex.StreamEx

class TestoConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
    val myTestLocator: SMTestLocator,
) : SMTRunnerConsoleProperties(config, TestoBundle.message("testo.local.run.display.name"), executor) {
    override fun getTestLocator() = this.myTestLocator

    override fun isPrintTestingStartedTime() = false
}