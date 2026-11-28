package com.github.xepozz.testo.runs

import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.runs.TestoRunArchiver.withConfiguration
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class TestoReplayConfigurationTest : BasePlatformTestCase() {
    private fun configuration(): TestoConfiguration =
        TestoPhp.getInstance().configurationFactory().createTemplateConfiguration(project) as TestoConfiguration

    private fun TestoConfiguration.properties(): TestoConsoleProperties =
        createTestConsoleProperties(DefaultRunExecutor.getRunExecutorInstance()) as TestoConsoleProperties

    fun testALiveTabRerunsItsOwnConfiguration() {
        val live = configuration()

        assertSame(live, live.properties().testoConfiguration)
    }

    fun testAReplayedTabRerunsTheArchivedConfiguration() {
        val archived = configuration().apply { selection = selection.copy(rerunFilters = listOf("\\App\\CalcTest::sum")) }
        val replay = TestoRunReplayProfile(project, Path.of("run"), TestoRunManifest().withConfiguration(archived))
        val properties = configuration().properties().apply { replayProfile = replay }

        assertSame(replay.testoConfiguration, properties.testoConfiguration)
        assertEquals(listOf("\\App\\CalcTest::sum"), properties.testoConfiguration!!.selection.rerunFilters)
    }
}
