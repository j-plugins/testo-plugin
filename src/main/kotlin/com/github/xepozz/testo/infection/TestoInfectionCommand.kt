package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.php.TestoToolRequest
import com.intellij.execution.ExecutionException
import java.nio.file.Files
import java.nio.file.Path

/** One mutation run over an archived Testo run's reports. [workDir] is a host directory this launch owns. */
internal class TestoInfectionLaunch(
    val ready: TestoMutationReadiness.Ready,
    val sourceFiles: List<String>,
    val workDir: Path,
    val options: TestoInfectionOptions = TestoInfectionOptions(),
    /** A rerun writes no HTML report: the run's own one covers every mutant. */
    val withHtml: Boolean = true,
) {
    val coverageDir: Path get() = workDir.resolve("coverage")
    val htmlReport: Path get() = workDir.resolve(HTML_REPORT)
    val textLog: Path get() = workDir.resolve("mutations.log")

    @Volatile
    internal var prepared: TestoPreparedTool? = null

    internal var inputProtection: AutoCloseable? = null

    companion object {
        const val HTML_REPORT = "report.html"
    }
}

internal object TestoInfectionCommand {
    fun create(environment: TestoToolEnvironment, launch: TestoInfectionLaunch): TestoPreparedTool {
        val infection = findInfection(environment)
        TestoInfectionReports.assemble(launch.ready, launch.coverageDir)
        Files.createDirectories(launch.workDir)
        val outputs = listOfNotNull(launch.htmlReport.takeIf { launch.withHtml }, launch.textLog)
        outputs.forEach { Files.deleteIfExists(it) }
        return environment.prepare(TestoToolRequest(infection, launch.coverageDir, outputs) { input, paths ->
            TestoInfectionArguments.build(input, launch.sourceFiles, paths[launch.htmlReport], paths[launch.textLog], launch.options)
        }).also { launch.prepared = it }
    }

    private fun findInfection(environment: TestoToolEnvironment): String {
        val executable = environment.testoExecutable
        val local = environment.toLocal(executable)
        val candidates = TestoInfectionExecutable.candidates(local ?: executable, environment.workingDirectory)
        // An unmapped interpreter binary cannot be inspected from the host; preserve its sibling path.
        val found = if (local == null) candidates.firstOrNull()
        else candidates.firstOrNull { Files.isRegularFile(Path.of(it)) }
        return found ?: throw ExecutionException(
            TestoBundle.message("infection.error.notFound", candidates.joinToString("\n"))
        )
    }
}
