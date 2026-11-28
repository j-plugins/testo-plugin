package com.github.xepozz.testo.runs

import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.runs.TestoRunArchiver.withConfiguration
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.openapi.util.Disposer
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

    fun testAReplayedTabOffersRerunFailedTests() {
        val replay = TestoRunReplayProfile(project, Path.of("run"), TestoRunManifest().withConfiguration(configuration()))
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, replay).build()

        val result = replay.getState(executor, environment).execute(executor, environment.runner)!!
        Disposer.register(testRootDisposable, result.executionConsole)

        assertInstanceOf((result as DefaultExecutionResult).restartActions.single(), AbstractRerunFailedTestsAction::class.java)
    }
}
