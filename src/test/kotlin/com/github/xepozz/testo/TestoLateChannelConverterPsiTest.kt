package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.NewChannelDetector
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * A test's channel output, fed through the real converter after its node was rendered — which happens on
 * `testStarted` — must still open the channel's tab, whatever status closes the test.
 */
class TestoLateChannelConverterPsiTest : BasePlatformTestCase() {

    fun testBenchTablesAfterTestStartedOpenTheirTabs() {
        val opened = runLateChannel(
            "##teamcity[testStdOut name='sumInCycle' out='Results for sumInCycle:|n+---+' channel='bench-result' level='info' nodeId='3' parentNodeId='2' flowId='3']",
            "##teamcity[testStdOut name='sumInCycle' out='Iterations:|n+---+' channel='bench-iterations' level='info' nodeId='3' parentNodeId='2' flowId='3']",
            "##teamcity[testFailed name='sumInCycle' message='Failed assertion' details='' nodeId='3' parentNodeId='2' flowId='3']",
            "##teamcity[testFinished name='sumInCycle' duration='1084' status='failed' nodeId='3' parentNodeId='2' flowId='3']",
        )

        assertEquals(listOf("bench-result", "bench-iterations"), opened.channels)
        assertEquals(listOf("Results for sumInCycle:\n+---+"), opened.texts("bench-result"))
    }

    fun testSkippedTestOutputOpensItsTab() {
        val opened = runLateChannel(
            "##teamcity[testStdOut name='sumInCycle' out='Skipping: no driver' channel='log' level='info' nodeId='3' parentNodeId='2' flowId='3']",
            "##teamcity[testIgnored name='sumInCycle' message='No driver' nodeId='3' parentNodeId='2' flowId='3']",
            "##teamcity[testFinished name='sumInCycle' duration='0' status='skipped' nodeId='3' parentNodeId='2' flowId='3']",
        )

        assertEquals(listOf("log"), opened.channels)
        assertEquals(listOf("Skipping: no driver"), opened.texts("log"))
    }

    private class Opened(val channels: List<String>, val texts: (String) -> List<String>)

    private fun runLateChannel(vararg afterStart: String): Opened {
        val properties = testoProperties()
        val converter = properties.createTestEventsConverter("Testo", properties)
        val store = properties.channelStore
        converter.process(
            "##teamcity[testStarted name='sumInCycle' locationHint='php_qn://D:/p/BenchAttr.php::\\Tests\\BenchAttr::sumInCycle'" +
                " testSuite='Bench' testType='bench' nodeId='3' parentNodeId='2' flowId='3']\n",
            ProcessOutputTypes.STDOUT,
        )

        // What the channel tabs do once the node is selected: take the channels it has, then follow its "all" stream.
        val key = store.keyFor("sumInCycle")
        val detector = NewChannelDetector(store.channelsFor(key).keys) { true }
        val channels = mutableListOf<String>()
        store.attachAll(key) { chunk -> detector.offer(chunk)?.let(channels::add) }

        afterStart.forEach { converter.process("$it\n", ProcessOutputTypes.STDOUT) }

        return Opened(channels) { channel -> store.channelsFor(key)[channel].orEmpty().map { it.text } }
    }

    private fun testoProperties(): TestoConsoleProperties {
        val configuration = TestoRunConfigurationType().createTemplateConfiguration(project)
        return TestoConsoleProperties(
            configuration,
            DefaultRunExecutor.getRunExecutorInstance(),
            PhpPathMapper.create(project),
        )
    }
}
