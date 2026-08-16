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
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PathUtil
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

    override fun suggestedName(): String {
        // A filter-only run has no file/method to name itself after (it is deliberately unscoped), and the platform's
        // name for an unscoped configuration would be empty — name it after whatever selects it instead. A
        // configuration that DOES point at a config file (ApplicationConfig/SuiteConfig runs) keeps the platform's
        // file-based name even if a group was typed into it later.
        if (isFilterOnlyRun()) {
            val runner = testoSettings.runnerSettings
            val groups = runner.groups
            if (groups.isNotEmpty()) {
                val quoted = groups.joinToString(", ") { "'$it'" }
                return if (groups.size == 1) "Group $quoted" else "Groups $quoted"
            }
            val suites = runner.suites
            if (suites.isNotEmpty()) {
                val quoted = suites.joinToString(", ") { "'$it'" }
                return if (suites.size == 1) "Suite $quoted" else "Suites $quoted"
            }
            if (runner.testoType.isNotEmpty()) return "Type '${runner.testoType}'"
        }

        // The platform names a method run `<file>::<method field>`, which for a qualified selector repeats the class
        // the file name already implies: `Calculator.php::\Testo\Bench\Internal\Calculator::med:3:0`.
        qualifiedMethodTail()?.let {
            return "${PathUtil.getFileName(testoSettings.runnerSettings.filePath.orEmpty())}::$it"
        }

        return super.suggestedName() as String
    }

    override fun getActionName(): String? = qualifiedMethodTail() ?: super.getActionName()

    /**
     * The `med:3:0` of a `\Ns\Calculator::med:3:0` method field, or null when the field holds a plain method name.
     *
     * A run produced from a results-tree node puts the whole selector there — that is what Testo's `--filter` takes —
     * and everything that shows the configuration to the user is better off with just its tail.
     */
    private fun qualifiedMethodTail(): String? {
        val runner = testoSettings.runnerSettings
        if (runner.scope != PhpTestRunnerSettings.Scope.Method) return null
        val method = runner.methodName ?: return null

        return method.substringAfterLast("::").takeIf { it != method && it.isNotEmpty() }
    }

    override fun checkConfiguration() {
        try {
            super.checkConfiguration()
        } catch (e: RuntimeConfigurationError) {
            // A filter-only run borrows the ConfigurationFile scope to keep path/filter flags off the command line,
            // but the platform then demands a configuration file. Testo needs none — it falls back to ./testo.php
            // in the working directory — so swallow exactly that error, matched by message. If the platform ever
            // rewords it this fails closed (the validation error simply comes back). The executable-path check the
            // platform would have run after this throw resurfaces as a clear ExecutionException in createCommand.
            if (!isFilterOnlyRun() || e.message != missingConfigurationFileMessage()) throw e
        }
    }

    /**
     * Scope ConfigurationFile without an actual config file: the run is selected by Testo's own filters alone —
     * `--group`, `--suite`, `--type` or an explicit `--filter` list — and carries no path or method flag at all.
     */
    private fun isFilterOnlyRun(): Boolean {
        val runner = testoSettings.runnerSettings
        if (runner.scope != PhpTestRunnerSettings.Scope.ConfigurationFile) return false
        if (runner.isUseAlternativeConfigurationFile) return false

        return runner.groups.isNotEmpty()
                || runner.excludeGroups.isNotEmpty()
                || runner.suites.isNotEmpty()
                || runner.testoType.isNotEmpty()
                || runner.rerunFilters.isNotEmpty()
    }

    private fun missingConfigurationFileMessage() = PhpBundle.message(
        "validation.value.is.not.specified.or.invalid.press.fix.project.configuration",
        "Configuration file",
    )

    override fun createSettings() = TestoRunConfigurationSettings()

    override fun createRerunAction(
        consoleView: ConsoleView,
        properties: SMTRunnerConsoleProperties,
    ) = TestoRerunFailedTestsAction(consoleView, properties)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val editor = super.getConfigurationEditor() as PhpTestRunConfigurationEditor
        editor.setRunnerOptionsDocumentation("https://php-testo.github.io/docs/guide/cli-reference")

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

        val interpreter = this.interpreter
        val pathProcessor = when {
            interpreter?.isRemote == true -> manager?.createPathMapper(this.project, interpreter.phpSdkAdditionalData)
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

            when (testRunnerSettings.scope) {
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
