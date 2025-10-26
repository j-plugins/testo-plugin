package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties

class TestoConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
) : SMTRunnerConsoleProperties(config, TestoBundle.message("testo.local.run.display.name"), executor) {

    override fun isPrintTestingStartedTime() = false
}