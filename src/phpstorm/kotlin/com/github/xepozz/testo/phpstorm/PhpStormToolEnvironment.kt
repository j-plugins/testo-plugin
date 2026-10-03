package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.php.TestoToolRequest
import com.intellij.openapi.project.Project
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpCommandLineSettings
import com.jetbrains.php.run.PhpRunConfiguration

internal class PhpStormToolEnvironment(
    private val project: Project,
    private val interpreter: PhpInterpreter,
    override val testoExecutable: String,
    override val workingDirectory: String,
    private val settings: PhpCommandLineSettings,
    environment: Map<String?, String?>,
) : TestoToolEnvironment {
    private val env = environment.toMap()
    private val launcher = PhpToolLauncher(project, interpreter).also { it.toLocal(testoExecutable) }

    override fun toLocal(path: String): String? = launcher.toLocal(path)

    override fun prepare(request: TestoToolRequest): TestoPreparedTool {
        var shared: PhpToolLauncher.SharedDirectory? = null
        try {
            val outputs = request.outputFiles.associateWith { launcher.output(it.toString()) }.filterValues { it.isReachable }
            val command = launcher.command(request.script, workingDirectory, settings, env, false) { paths ->
                val input = launcher.share(request.inputDirectory, request.inputDirectory.parent.fileName.toString(), workingDirectory, paths)
                shared = input
                request.arguments(input.path, outputs.mapValues { it.value.path })
            }
            val line = command.createGeneralCommandLine(false)
            return TestoPreparedTool(
                line.commandLineString,
                process = { PhpRunConfiguration.createProcessHandler(project, command, false, false, line) },
                collect = { outputs.values.forEach { it.copyToLocal(project) } },
                release = { shared?.release() },
            )
        } catch (e: Throwable) {
            try { shared?.release() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }
}
