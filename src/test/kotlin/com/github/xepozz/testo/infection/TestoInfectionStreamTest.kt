package com.github.xepozz.testo.infection

import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import java.nio.file.Files
import java.nio.file.Path

/** A real `infection --teamcity` stream (Testo's retry plugin) through the mutation console's converter. */
class TestoInfectionStreamTest : BasePlatformTestCase() {

    fun testMutantsLandUnderTheirFileWithTheirOutput() {
        val root = replay()

        val file = root.children.single()
        assertEquals("plugin/retry/src/Interceptor/RetryPolicyRunInterceptor.php", file.name)
        val mutants = file.children
        assertEquals(19, mutants.size)
        assertEquals(1, mutants.count { it.isDefect })

        val killed = mutants.first { it.name.startsWith("Infection\\Mutator\\Boolean\\FalseValue") }
        assertFalse(killed.isDefect)
        assertTrue(killed.output(), killed.output().contains("Mutation result: killed by tests"))
    }

    private fun replay(): SMTestProxy.SMRootTestProxy {
        val configuration = TestoRunConfiguration(project, TestoRunConfigurationType.INSTANCE)
        val launch = TestoInfectionLaunch(
            TestoMutationReadiness.Ready(Path.of("coverage-xml"), Path.of("junit.xml")),
            emptyList(),
            Files.createTempDirectory("testo-infection"),
        )
        val properties = TestoInfectionConsoleProperties(
            configuration,
            DefaultRunExecutor.getRunExecutorInstance(),
            PhpPathMapper.create(emptyList()),
            launch,
        )
        val root = SMTestProxy.SMRootTestProxy()
        val processor = GeneralIdBasedToSMTRunnerEventsConvertor(project, root, TestoInfectionConsoleProperties.FRAMEWORK_NAME)
        val converter = properties.createTestEventsConverter(TestoInfectionConsoleProperties.FRAMEWORK_NAME, properties)
        converter.setProcessor(processor)
        processor.onStartTesting()

        Files.readAllLines(Path.of("src/test/testData/infection/retry.teamcity.txt"))
            .forEach { converter.process("$it\n", ProcessOutputTypes.STDOUT) }
        converter.flushBufferOnProcessTermination(0)
        converter.finishTesting()
        UIUtil.dispatchAllInvocationEvents()
        return root
    }

    private fun SMTestProxy.output(): String {
        val printer = StringBuilder()
        printOn(object : Printer {
            override fun print(text: String, contentType: ConsoleViewContentType) {
                printer.append(text)
            }

            override fun onNewAvailable(printable: Printable) = printable.printOn(this)
            override fun printHyperlink(text: String, info: HyperlinkInfo?) {
                printer.append(text)
            }

            override fun mark() = Unit
        })
        return printer.toString()
    }
}
