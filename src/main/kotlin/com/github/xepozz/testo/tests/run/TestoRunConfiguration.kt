package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.actions.TestoRerunFailedTestsAction
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpAsyncRunConfiguration
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import com.jetbrains.php.testFramework.PhpTestFrameworkConfiguration
import com.jetbrains.php.testFramework.run.PhpTestRunConfiguration
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationEditor
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationHandler
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerConfigurationEditor
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRunConfiguration(project: Project, factory: ConfigurationFactory) : PhpTestRunConfiguration(
    project,
    factory,
    TestoBundle.message("testo.local.run.display.name"),
    TestoFrameworkType.INSTANCE,
    TestoTestRunnerSettingsValidator,
    TestoRunConfigurationHandler.INSTANCE,
), PhpAsyncRunConfiguration {
    val myHandler = TestoRunConfigurationHandler.INSTANCE

    val testoSettings
        get() = settings as TestoRunConfigurationSettings

    override fun createMethodFieldCompletionProvider(editor: PhpTestRunnerConfigurationEditor) =
        createMethodFileCompletionProvider(project, editor, { it.isTestoExecutable() })

    override fun suggestedName() = super.suggestedName() as String

    override fun createSettings() = TestoRunConfigurationSettings()

//    override fun createRerunAction(
//        consoleView: ConsoleView,
//        properties: SMTRunnerConsoleProperties,
//    ) = TestoRerunFailedTestsAction(consoleView, properties)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val editor = super.getConfigurationEditor() as PhpTestRunConfigurationEditor
        editor.setRunnerOptionsDocumentation("https://github.com/testo/testo")

        return TestoTestRunConfigurationEditor(editor, this)
    }

    override fun getWorkingDirectory(
        project: Project,
        settings: PhpTestRunConfigurationSettings,
        config: PhpTestFrameworkConfiguration?
    ) = project.basePath

    override fun createCommand(
        interpreter: PhpInterpreter,
        env: MutableMap<String?, String?>,
        arguments: MutableList<String?>,
        frameworkConfig: PhpTestFrameworkConfiguration?,
        withDebugger: Boolean
    ): PhpCommandSettings {
        val command = PhpCommandSettingsBuilder(project, interpreter)
            .loadAndStartDebug(withDebugger)
            .build()

        val executablePath = frameworkConfig?.executablePath
        if (frameworkConfig == null || executablePath.isNullOrEmpty()) {
            throw ExecutionException(
                PhpBundle.message(
                    "php.interpreter.base.configuration.is.not.provided.or.empty",
                    frameworkName,
                    if (command.isRemote) "'${interpreter.name}' interpreter" else "local machine",
                )
            )
        }

        val workingDirectory = getWorkingDirectory(project, settings, frameworkConfig)
        if (workingDirectory.isNullOrEmpty()) {
            throw ExecutionException(PhpBundle.message("php.interpreter.base.configuration.working.directory"))
        }
        command.setWorkingDir(workingDirectory)

        myHandler.prepareArguments(arguments, testoSettings)
        myHandler.prepareCommand(project, command, executablePath, null, testoSettings.runnerSettings.command)

        command.importCommandLineSettings(settings.commandLineSettings, workingDirectory)
        command.addEnvs(env)

        fillTestRunnerArguments(
            project,
            workingDirectory,
            settings.runnerSettings,
            arguments,
            command,
            frameworkConfig,
            myHandler,
        )

        return command
    }

    override fun createTestConsoleProperties(executor: Executor): SMTRunnerConsoleProperties {
        val manager = PhpRemoteInterpreterManager.getInstance()

        val pathProcessor = when (this.interpreter?.isRemote) {
            true -> manager?.createPathMapper(this.project, interpreter!!.phpSdkAdditionalData)
            else -> null
        } ?: PhpCommandLinePathProcessor.LOCAL

        val pathMapper = pathProcessor.createPathMapper(this.project)
        return TestoConsoleProperties(
            this,
            executor,
            pathMapper,
        )
    }

    companion object Companion {
        const val ID = "TestoConsoleCommandRunConfiguration"

        private fun fillTestRunnerArguments(
            project: Project,
            workingDirectory: String,
            testRunnerSettings: PhpTestRunnerSettings,
            arguments: MutableList<String?>,
            command: PhpCommandSettings,
            configuration: PhpTestFrameworkConfiguration?,
            handler: PhpTestRunConfigurationHandler,
        ) {
            val testRunnerOptions = testRunnerSettings.testRunnerOptions
            if (StringUtil.isNotEmpty(testRunnerOptions)) {
                command.addArguments(ParametersList.parse(testRunnerOptions!!).toList())
            }

            command.addArguments(arguments)

            val configurationFilePath = getConfigurationFile(testRunnerSettings, configuration)
            if (!configurationFilePath.isNullOrEmpty()) {
                command.addArgument(handler.configFileOption)
                command.addPathArgument(configurationFilePath)
            }

            val scope = testRunnerSettings.scope
            when (scope) {
                PhpTestRunnerSettings.Scope.Type -> handler.runType(
                    project,
                    command,
                    StringUtil.notNullize(testRunnerSettings.selectedType),
                    workingDirectory,
                )

                PhpTestRunnerSettings.Scope.Directory -> handler.runDirectory(
                    project,
                    command,
                    StringUtil.notNullize(testRunnerSettings.directoryPath),
                    workingDirectory,
                )

                PhpTestRunnerSettings.Scope.File -> handler.runFile(
                    project,
                    command,
                    StringUtil.notNullize(testRunnerSettings.filePath),
                    workingDirectory,
                )

                PhpTestRunnerSettings.Scope.Method -> {
                    val filePath = StringUtil.notNullize(testRunnerSettings.filePath)
                    handler.runMethod(project, command, filePath, testRunnerSettings.methodName, workingDirectory)
                }

                PhpTestRunnerSettings.Scope.ConfigurationFile -> {}
            }
        }
    }
}
