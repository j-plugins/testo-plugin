package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.runs.TestoRunRecording
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.actions.testoRunProfile
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path

/** Runs Infection over this tab's archived run, skipping Infection's own initial test run. */
class TestoMutateAction : DumbAwareAction(
    TestoBundle.messagePointer("action.testo.mutate.text"),
    TestoBundle.messagePointer("action.testo.mutate.description"),
    TestoIcons.MUTATE,
) {
    @Volatile
    private var cached: Pair<String, TestoMutationReadiness>? = null

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val context = context(e)
        e.presentation.isVisible = context != null
        if (context == null) return
        val readiness = readiness(context.configuration.project, context.runDir)
        e.presentation.isEnabled = readiness is TestoMutationReadiness.Ready
        e.presentation.description = when (readiness) {
            is TestoMutationReadiness.Ready -> TestoBundle.message("action.testo.mutate.description")
            TestoMutationReadiness.Missing.NOT_FINISHED -> TestoBundle.message("infection.missing.notFinished")
            TestoMutationReadiness.Missing.NOT_PASSED -> TestoBundle.message("infection.missing.notPassed")
            TestoMutationReadiness.Missing.NO_COVERAGE_XML -> TestoBundle.message("infection.missing.coverageXml")
            TestoMutationReadiness.Missing.NO_JUNIT -> TestoBundle.message("infection.missing.junit")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = context(e) ?: return
        val ready = readiness(context.configuration.project, context.runDir) as? TestoMutationReadiness.Ready ?: return
        val sources = runCatching { TestoInfectionReports.coveredSourceFiles(ready.coverageXml) }
            .onFailure { thisLogger().warn("Could not read the covered sources of ${ready.coverageXml}", it) }
            .getOrDefault(emptyList())

        val clone = context.configuration.clone() as TestoRunConfiguration
        clone.name = TestoBundle.message("infection.run.name", context.configuration.name)
        clone.infectionLaunch = TestoInfectionLaunch(ready, sources, workDir(clone.project, context.runDir))
        val factory = clone.factory ?: return
        val settings = RunManager.getInstance(clone.project).createConfiguration(clone, factory)
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    // Re-read only when run.json changes: it appears once the archive completes, and update runs on every repaint.
    private fun readiness(project: Project, runDir: Path): TestoMutationReadiness {
        val manifestFile = runDir.resolve(TestoRunRecording.MANIFEST_FILE)
        val key = "$runDir|${runCatching { Files.getLastModifiedTime(manifestFile).toMillis() }.getOrDefault(0)}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val manifest = TestoRunStore.getInstance(project).readManifest(runDir)
        return TestoInfectionReports.readiness(runDir, manifest).also { cached = key to it }
    }

    private class Context(val configuration: TestoRunConfiguration, val runDir: Path)

    private fun context(e: AnActionEvent): Context? {
        val environment = e.getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT) ?: return null
        val configuration = environment.testoRunProfile() as? TestoRunConfiguration ?: return null
        val console = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.executionConsole as? SMTRunnerConsoleView
        val properties = console?.properties as? TestoConsoleProperties ?: return null
        val runDir = runCatching { properties.currentRunDir() }.getOrNull() ?: return null
        return Context(configuration, runDir)
    }

    private fun workDir(project: Project, runDir: Path): Path = Path.of(
        PathManager.getSystemPath(),
        "testo",
        "infection",
        project.locationHash,
        FileUtil.sanitizeFileName(runDir.fileName.toString()),
    )
}
