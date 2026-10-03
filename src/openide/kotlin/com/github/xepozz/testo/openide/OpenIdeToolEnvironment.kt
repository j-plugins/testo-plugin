package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.php.TestoToolRequest
import com.github.xepozz.testo.php.TestoToolLifecycle
import com.github.xepozz.testo.php.TestoToolOutcome
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import ru.openide.openphp.settings.launch.PhpToolLaunch
import ru.openide.openphp.settings.launch.PhpToolOutcome
import ru.openide.openphp.settings.launch.PreparedPhpToolLaunch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal class OpenIdeToolEnvironment(
    configuration: TestoRunConfiguration,
    private val environment: PhpLaunchEnvironment,
    private val prepareLaunch: (List<String>, File, Map<String, String>, List<PhpExposedPath>, Boolean) -> PreparedPhpToolLaunch =
        { args, cwd, env, paths, inherit -> PhpToolLaunch.prepare(configuration.project, environment, args, cwd, env, paths, inherit) },
) : TestoToolEnvironment {
    private val copy = configuration.clone() as TestoRunConfiguration
    private val launch = ReadAction.compute<com.github.xepozz.testo.openide.tests.run.TestoLaunch, RuntimeException> {
        copy.launch(environment)
    }
    override val testoExecutable = launch.binary ?: throw ExecutionException("Testo executable is not configured")
    override val workingDirectory = launch.workingDirectory
    private val env = copy.options.environmentVariables.toMap()
    private val passParent = copy.options.passParentEnvironment
    private val projectPath = copy.project.basePath

    override fun toLocal(path: String): String? = environment.toHost(path).takeIf {
        environment.locality == PhpLaunchLocality.SameHost || it != path || File(it).exists()
    }

    override fun prepare(request: TestoToolRequest): TestoPreparedTool {
        var staging: Path? = null
        try {
            var input = environment.exposeFile(request.inputDirectory.resolve("junit.xml").toString())
            if (input is PhpExposedPath.Unreachable) {
                val roots = listOfNotNull(projectPath?.let { Path.of(it, ".idea", "testo", "staging") },
                    Path.of(workingDirectory, ".testo-staging"))
                for (root in roots.distinct()) {
                    Files.createDirectories(root)
                    val candidate = Files.createTempDirectory(root, "mutation-")
                    staging = candidate
                    val exposed = environment.exposeFile(candidate.resolve("junit.xml").toString())
                    if (exposed !is PhpExposedPath.Unreachable) {
                        FileUtil.copyDir(request.inputDirectory.toFile(), candidate.toFile())
                        input = environment.exposeFile(candidate.resolve("junit.xml").toString())
                        if (input !is PhpExposedPath.Unreachable) break
                    }
                    NioFiles.deleteRecursively(candidate)
                    staging = null
                }
            }
            if (input is PhpExposedPath.Unreachable) throw ExecutionException("Infection cannot read the coverage reports: ${input.reason}")
            val exposures = mutableListOf(input)
            val outputs = request.outputFiles.mapNotNull { local ->
                Files.createDirectories(local.parent)
                val exposed = environment.exposeFile(local.toString())
                when (exposed) {
                    is PhpExposedPath.Unreachable -> null
                    else -> { exposures += exposed; local to pathOf(exposed) }
                }
            }.toMap()
            val inputDirectory = pathOf(input).replace('\\', '/').substringBeforeLast('/')
            val managed = prepareLaunch(
                listOf(environment.toEnvironment(request.script)) + request.arguments(inputDirectory, outputs),
                File(workingDirectory), env, exposures, passParent,
            )
            val owned = staging
            lateinit var prepared: TestoPreparedTool
            prepared = TestoPreparedTool("",
                process = { managed.start().also { prepared.commandLine = (it as? OSProcessHandler)?.commandLine.orEmpty() } },
                release = { owned?.let(NioFiles::deleteRecursively) },
                lifecycle = object : TestoToolLifecycle {
                    override val completion = managed.completion.thenApply(::toolOutcome)
                    override fun stop() = managed.stop().thenApply(::toolOutcome)
                    override fun abandon() = managed.abandon()
                })
            return prepared
        } catch (e: Throwable) {
            try { staging?.let(NioFiles::deleteRecursively) } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    private fun pathOf(path: PhpExposedPath): String = when (path) {
        is PhpExposedPath.Visible -> path.pathInEnvironment
        is PhpExposedPath.Mounted -> path.pathInEnvironment
        is PhpExposedPath.Unreachable -> error("Unreachable path: ${path.reason}")
    }
}

internal fun toolOutcome(outcome: PhpToolOutcome): TestoToolOutcome = when (outcome) {
    is PhpToolOutcome.Exited -> TestoToolOutcome.Exited(outcome.exitCode)
    is PhpToolOutcome.Stopped -> TestoToolOutcome.Stopped(outcome.exitCode)
    is PhpToolOutcome.NotStarted -> TestoToolOutcome.NotStarted(outcome.reason)
    is PhpToolOutcome.Unconfirmed -> TestoToolOutcome.Unconfirmed(outcome.reason)
}
