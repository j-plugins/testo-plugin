package com.github.xepozz.testo.tests.run

import com.intellij.openapi.project.Project
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationHandler

class TestoRunConfigurationHandler : PhpTestRunConfigurationHandler {
    companion object Companion {
        @JvmField
        val INSTANCE = TestoRunConfigurationHandler()
    }

    override fun getConfigFileOption() = "--configuration"

    override fun prepareCommand(project: Project, commandSettings: PhpCommandSettings, exe: String, version: String?) {
        commandSettings.apply {
            setScript(exe, true)
            addArgument("run")
//            addArgument("--no-progress")
//            addArgument("-n")
//            addArgument("-q")
//            addArgument("--logger-gitlab=php://stdout")
        }
//        println("commandSettings: $commandSettings")
    }

    override fun runType(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        type: String,
        workingDirectory: String
    ) {
//        println("runType: $type, $workingDirectory")

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
//        println("runDirectory: $directory")
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
//        println("runFile: $file")
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
//        println("runMethod: $file, $methodName")
        if (file.isEmpty()) return

        val myMethodName = methodName.substringBefore('#')
        val dataProvider = methodName.substringAfter('#', "")

//        println("method: $myMethodName, dataProvider: $dataProvider")

        phpCommandSettings.apply {
            addArgument("--path")
            addRelativePathArgument(file, workingDirectory)
            if (myMethodName.isNotEmpty()) {
                addArgument("--filter")
                addArgument(myMethodName)
            }
            if (dataProvider.isNotEmpty()) {
                addArgument("--data-provider")
                addArgument(dataProvider)
            }
        }
    }
}