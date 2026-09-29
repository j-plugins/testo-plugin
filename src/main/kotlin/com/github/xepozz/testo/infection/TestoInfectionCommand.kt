package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.PhpToolLauncher
import com.github.xepozz.testo.tests.run.TestoReportTarget
import com.intellij.execution.ExecutionException
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationSettings
import java.nio.file.Files
import java.nio.file.Path

/** One mutation run over an archived Testo run's reports. [workDir] is a host directory this launch owns. */
internal class TestoInfectionLaunch(
    val ready: TestoMutationReadiness.Ready,
    val sourceFiles: List<String>,
    val workDir: Path,
    val options: TestoInfectionOptions = TestoInfectionOptions(),
) {
    val coverageDir: Path get() = workDir.resolve("coverage")
    val htmlReport: Path get() = workDir.resolve("report.html")
    val textLog: Path get() = workDir.resolve("mutations.log")

    @Volatile
    internal var shared: PhpToolLauncher.SharedDirectory? = null

    @Volatile
    internal var htmlTarget: TestoReportTarget? = null

    @Volatile
    internal var textTarget: TestoReportTarget? = null
}

internal object TestoInfectionCommand {
    fun create(
        launcher: PhpToolLauncher,
        launch: TestoInfectionLaunch,
        testoExecutable: String,
        workingDirectory: String,
        settings: PhpTestRunConfigurationSettings,
        env: Map<String?, String?>,
        withDebugger: Boolean,
    ): PhpCommandSettings {
        val infection = findInfection(launcher, testoExecutable, workingDirectory)

        TestoInfectionReports.assemble(launch.ready, launch.coverageDir)
        val shared = launcher.share(launch.coverageDir, launch.workDir.fileName.toString())
        launch.shared = shared

        Files.createDirectories(launch.workDir)
        Files.deleteIfExists(launch.htmlReport)
        val html = launcher.output(launch.htmlReport.toString()).takeIf { it.isReachable }
        launch.htmlTarget = html
        Files.deleteIfExists(launch.textLog)
        val text = launcher.output(launch.textLog.toString()).takeIf { it.isReachable }
        launch.textTarget = text

        return launcher.command(
            infection,
            workingDirectory,
            settings.commandLineSettings,
            env,
            withDebugger,
            TestoInfectionArguments.build(shared.path, launch.sourceFiles, html?.path, text?.path, launch.options),
        )
    }

    private fun findInfection(launcher: PhpToolLauncher, testoExecutable: String, workingDirectory: String): String {
        val local = launcher.toLocal(testoExecutable)
        val candidates = TestoInfectionExecutable.candidates(local ?: testoExecutable, workingDirectory)
        // An unmapped remote Testo binary cannot be looked around from the host: its sibling is the one guess left.
        val found = if (local == null) candidates.firstOrNull()
        else candidates.firstOrNull { Files.isRegularFile(Path.of(it)) }
        return found ?: throw ExecutionException(
            TestoBundle.message("infection.error.notFound", candidates.joinToString("\n"))
        )
    }
}
