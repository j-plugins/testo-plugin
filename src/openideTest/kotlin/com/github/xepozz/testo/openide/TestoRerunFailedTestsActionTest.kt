package com.github.xepozz.testo.openide

import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.actions.TestoRerunFailedTestsAction
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.openide.tests.run.TestoRunState
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** A rerun of failed tests runs exactly them: one `--filter` selector per failed leaf, and nothing narrows them further. */
class TestoRerunFailedTestsActionTest : BasePlatformTestCase() {
    fun testOneFailedLeafGivesACloneWithOneSelector() {
        val configuration = TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
            selection = selection.apply {
                scope = TestoScope.FILE
                filePath = "/work/app/tests/CalcTest.php"
            }
        }
        val failedLeaf = "php_qn:///work/app/tests/CalcTest.php::\\App\\CalcTest::median with data set #1"

        val clone = TestoRerunFailedTestsAction.rerunConfiguration(configuration, listOfNotNull(TestoRunTarget.filterOf(failedLeaf)))!!

        assertEquals(listOf("\\App\\CalcTest::median"), clone.selection.rerunFilters)
        assertEquals(TestoScope.CONFIGURATION_FILE, clone.selection.scope)
        assertEquals("The original is left as it was", TestoScope.FILE, configuration.selection.scope)
        assertNull("Nothing failed, nothing to rerun", TestoRerunFailedTestsAction.rerunConfiguration(configuration, emptyList()))
    }

    fun testTheActionOfARunReadsTheTreeOfThatRun() {
        // Without the run's tree the action finds nothing failed, stays disabled and builds no rerun.
        val defaultEnvironment = TestoRunConfiguration.environmentProvider
        try {
            TestoRunConfiguration.environmentProvider = { FakeLaunchEnvironment() }
            val app = FileUtil.createTempDirectory("testo-rerun-app", null)
            val binary = File(app, "vendor/bin/testo").apply { parentFile.mkdirs(); writeText("#!/usr/bin/env php\n") }
            val configuration = TestoRunConfigurationType.instance().createTemplateConfiguration(project).apply {
                name = "Rerun"
                options.binaryPath = FileUtil.toSystemIndependentName(binary.path)
                options.workingDirectory = FileUtil.toSystemIndependentName(app.path)
                selection = selection.apply { logHtml = false }
            }
            val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            val prepared = configuration.prepare(processHandler = { NopProcessHandler() })

            val result = TestoRunState(configuration, environment).show(prepared) as DefaultExecutionResult
            val console = result.executionConsole as SMTRunnerConsoleView
            try {
                val action = result.restartActions.filterIsInstance<AbstractRerunFailedTestsAction>().single()
                assertSame(console.resultsViewer, action.model)
            } finally {
                Disposer.dispose(console)
            }
        } finally {
            TestoRunConfiguration.environmentProvider = defaultEnvironment
        }
    }
}
