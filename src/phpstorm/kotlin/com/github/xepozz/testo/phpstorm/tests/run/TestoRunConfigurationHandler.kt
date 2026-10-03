package com.github.xepozz.testo.phpstorm.tests.run

import com.github.xepozz.testo.launch.TestoCommandLine
import com.github.xepozz.testo.phpstorm.toSelection
import com.intellij.openapi.project.Project
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationHandler

class TestoRunConfigurationHandler : PhpTestRunConfigurationHandler {
    companion object Companion {
        @JvmField
        val INSTANCE = TestoRunConfigurationHandler()
    }

    override fun getConfigFileOption() = TestoCommandLine.CONFIG_OPTION

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
            // The caller already mapped the executable if a mapping matched — see TestoRunConfiguration.createCommand.
            setScript(exe, !isRemote)
            addArgument(command)
        }
    }

    fun prepareArguments(arguments: MutableList<String?>, testoSettings: TestoRunConfigurationSettings) {
        arguments.addAll(TestoCommandLine.selectionArguments(testoSettings.runnerSettings.toSelection()))
    }

    override fun runType(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        type: String,
        workingDirectory: String
    ) {
        TestoCommandLine.typeArguments(type).forEach { phpCommandSettings.addArgument(it) }
    }

    override fun runDirectory(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        directory: String,
        workingDirectory: String
    ) {
        TestoCommandLine.directoryArguments(directory, workingDirectory).forEach { phpCommandSettings.addArgument(it) }
    }

    override fun runFile(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        file: String,
        workingDirectory: String
    ) {
        TestoCommandLine.fileArguments(file, workingDirectory).forEach { phpCommandSettings.addArgument(it) }
    }

    override fun runMethod(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        file: String,
        methodName: String,
        workingDirectory: String
    ) {
        TestoCommandLine.methodArguments(file, methodName, workingDirectory).forEach { phpCommandSettings.addArgument(it) }
    }

    fun testoPathArguments(
        path: String,
        workingDirectory: String,
        directoryScope: Boolean,
    ): List<String> = TestoCommandLine.pathArguments(path, workingDirectory, directoryScope)

    fun parseMethodName(methodName: String) = TestoCommandLine.parseMethodName(methodName)
}
