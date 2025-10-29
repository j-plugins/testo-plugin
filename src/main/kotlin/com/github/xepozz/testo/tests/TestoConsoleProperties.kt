package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.TestProxyFilterProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.phpunit.PhpPsiLocationWithDataSet
import com.jetbrains.php.phpunit.TestoStackTraceParser
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import one.util.streamex.StreamEx

class TestoConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
    val pathMapper: PhpPathMapper,
) : SMTRunnerConsoleProperties(config, TestoBundle.message("testo.local.run.display.name"), executor) {
    val myTestLocator = TestoTestLocator(pathMapper)

    override fun getFilterProvider(): TestProxyFilterProvider? {
        println("getFilterProvider")
        return TestProxyFilterProvider { nodeType, nodeName, nodeArguments ->
            println("test proxy filter provider: nodeType: $nodeType, nodeName: $nodeName, nodeArguments: $nodeArguments")

            null
        }
    }

    override fun getTestStackTraceParser(url: String, proxy: SMTestProxy, project: Project) =
        TestoStackTraceParser.parse(url, proxy.stacktrace, proxy.errorMessage, testLocator, project)

    override fun getTestLocator() = this.myTestLocator

    override fun getErrorNavigatable(location: Location<*>, stacktrace: String): Navigatable? {
        if (location is PhpPsiLocationWithDataSet<*> && location.getPsiElement() !is Method) {
            return location.navigatable
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

    override fun isPrintTestingStartedTime() = true
}