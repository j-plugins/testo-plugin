package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * The announcement has to survive the trip through the real converter, which is where it is picked up: Testo emits it
 * before the first test, so nothing about the test tree exists yet when it arrives.
 */
class TestoReportConverterPsiTest : BasePlatformTestCase() {

    fun testAnnouncementBeforeAnyTestReachesTheStore() {
        val properties = testoProperties()
        val converter = properties.createTestEventsConverter("Testo", properties)

        // Verbatim what Testo writes, as its very first line of output.
        converter.process(
            "##teamcity[testoReport format='html' path='D:/git/testo/testo/runtime/report/index.html'" +
                " relativePath='runtime/report/index.html' name='Testo HTML report' schemaVersion='1']\n",
            ProcessOutputTypes.STDOUT,
        )

        val announced = properties.reportStore.primary()
        assertNotNull("the report was not recorded", announced)
        assertEquals("D:/git/testo/testo/runtime/report/index.html", announced!!.path)
        assertEquals("runtime/report/index.html", announced.relativePath)
        assertEquals("Testo HTML report", announced.name)
    }

    fun testOrdinaryServiceMessagesLeaveTheStoreEmpty() {
        val properties = testoProperties()
        val converter = properties.createTestEventsConverter("Testo", properties)

        converter.process("Testo v0.10.39\n", ProcessOutputTypes.STDOUT)

        assertNull(properties.reportStore.primary())
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
