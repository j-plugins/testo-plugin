package com.github.xepozz.testo.tests.run

import com.intellij.openapi.project.Project
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationHandler

class TestoRunConfigurationHandler : PhpTestRunConfigurationHandler {
    companion object Companion {
        @JvmField
        val INSTANCE = TestoRunConfigurationHandler()
    }

    override fun getConfigFileOption() = "--config"

    override fun prepareCommand(project: Project, commandSettings: PhpCommandSettings, exe: String, version: String?) {
        prepareCommand(project, commandSettings, exe, version, "run")
    }

    fun prepareCommand(
        project: Project,
        commandSettings: PhpCommandSettings,
        exe: String,
        version: String?,
        command: String,
    ) {
        commandSettings.apply {
            setScript(exe, true)
            addArgument(command)
        }
    }

    fun prepareArguments(arguments: MutableList<String?>, testoSettings: TestoRunConfigurationSettings) {
        val runner = testoSettings.runnerSettings

        if (runner.suite.isNotEmpty()) {
            arguments.add("--suite")
            arguments.add(runner.suite)
        }
        if (runner.group.isNotEmpty()) {
            arguments.add("--group")
            arguments.add(runner.group)
        }
        if (runner.excludeGroup.isNotEmpty()) {
            arguments.add("--exclude-group")
            arguments.add(runner.excludeGroup)
        }
        if (runner.repeat > 0) {
            arguments.add("--repeat")
            arguments.add(runner.repeat.toString())
        }
        if (runner.parallel > 0) {
            arguments.add("--parallel")
            arguments.add(runner.parallel.toString())
        }
    }

    override fun runType(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        type: String,
        workingDirectory: String
    ) {
        phpCommandSettings.apply {
            addArgument("--suite")
            addArgument(type)
        }
    }

    override fun runDirectory(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        directory: String,
        workingDirectory: String
    ) {
        if (directory.isEmpty()) return

        phpCommandSettings.apply {
            addArgument("--path")
            addRelativePathArgument(directory, workingDirectory)
        }
    }

    override fun runFile(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        file: String,
        workingDirectory: String
    ) {
        if (file.isEmpty()) return

        phpCommandSettings.apply {
            addArgument("--path")
            addRelativePathArgument(file, workingDirectory)
        }
    }

    override fun runMethod(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        file: String,
        methodName: String,
        workingDirectory: String
    ) {
        if (file.isEmpty()) return

        val parsed = parseMethodName(methodName)

        phpCommandSettings.apply {
            addArgument("--path")
            addRelativePathArgument(file, workingDirectory)
            if (parsed.method.isNotEmpty()) {
                addArgument("--filter")
                addArgument(parsed.method)
            }
            if (parsed.dataProvider.isNotEmpty()) {
                addArgument("--data-provider")
                addArgument(parsed.dataProvider)
            }
        }
    }

    data class ParsedMethodName(
        val method: String,
        val dataProvider: String,
    )

    fun parseMethodName(methodName: String): ParsedMethodName {
        val method = methodName.substringBefore('#')
        val dataProvider = methodName.substringAfter('#', "")
        return ParsedMethodName(method, dataProvider)
    }
}
