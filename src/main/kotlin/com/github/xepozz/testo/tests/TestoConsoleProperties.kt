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
//    override fun getScope(): GlobalSearchScope {
//        println("getScope ${super.getScope()}")
//        return super.getScope()
//    }

    override fun getTestLocator() = this.myTestLocator

    override fun isIdBasedTestTree(): Boolean {
        return super.isIdBasedTestTree().apply { println("isIdBasedTestTree $this") }
    }
    override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? {
        if (location is PhpPsiLocationWithDataSet<*> && location.getPsiElement() !is Method) {
            return location.getNavigatable()
        } else {
            var lines = StringUtil.splitByLinesKeepSeparators(stacktrace)
//            if (PhpUnitConsoleProperties.isLaravelTestCase(lines)) {
//                lines = Arrays.copyOfRange<String?>(lines, 0, lines.size - 1) as Array<String>
//            }

            val reversedStackTrace = (StreamEx.ofReversed<String?>(lines)
                .filter { line: String? -> !line!!.isEmpty() } as StreamEx<*>).joining()
            return super.getErrorNavigatable(location, reversedStackTrace)
        }
    }

    override fun isPrintTestingStartedTime() = false
}