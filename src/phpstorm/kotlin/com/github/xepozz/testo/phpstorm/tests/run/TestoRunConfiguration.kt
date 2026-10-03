package com.github.xepozz.testo.phpstorm.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.phpstorm.PhpStormToolEnvironment
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.phpstorm.PhpToolLauncher
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoConfigurationNames
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.phpstorm.load
import com.github.xepozz.testo.phpstorm.phpStormConsoleProperties
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.actions.TestoRerunFailedTestsAction
import com.github.xepozz.testo.phpstorm.toSelection
import com.github.xepozz.testo.tests.run.TestoReportFlags
import com.github.xepozz.testo.tests.run.TestoRunPaths
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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.PathUtil
import com.intellij.remote.RemoteSdkAdditionalData
import com.intellij.util.PathMappingSettings
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.config.commandLine.PhpCommandSettings
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
import java.nio.file.Files
import java.nio.file.Path

class TestoRunConfiguration(project: Project, factory: ConfigurationFactory) : PhpTestRunConfiguration(
    project,
    factory,
    TestoBundle.message("testo.local.run.display.name"),
    TestoFrameworkType.INSTANCE,
    TestoTestRunnerSettingsValidator,
    TestoRunConfigurationHandler.INSTANCE,
), PhpAsyncRunConfiguration, TestoConfiguration {
    val myHandler = TestoRunConfigurationHandler.INSTANCE

    val testoSettings
        get() = settings as TestoRunConfigurationSettings

    override var selection: TestoRunSelection
        get() = testoSettings.getTestoRunnerSettings().toSelection()
        set(value) = testoSettings.getTestoRunnerSettings().load(value)

    override val interpreterName: String
        get() = interpreter?.name.orEmpty()

    override val interpreterKind: String
        get() = interpreterKindOf(interpreter)

    override fun createMethodFieldCompletionProvider(editor: PhpTestRunnerConfigurationEditor) =
        createMethodFileCompletionProvider(project, editor, { it.isTestoExecutable() })

    override fun suggestedName(): String =
        TestoConfigurationNames.suggestedName(selection) ?: super.suggestedName() as String

    override fun getActionName(): String? =
        TestoConfigurationNames.qualifiedMethodTail(selection) ?: super.getActionName()

    override fun checkConfiguration() {
        try {
            super<PhpTestRunConfiguration>.checkConfiguration()
        } catch (e: RuntimeConfigurationError) {
            // A filter-only run borrows the ConfigurationFile scope to keep path/filter flags off the command line,
            // but the platform then demands a configuration file. Testo needs none — it falls back to ./testo.php
            // in the working directory — so swallow exactly that error, matched by message. If the platform ever
            // rewords it this fails closed (the validation error simply comes back). The executable-path check the
            // platform would have run after this throw resurfaces as a clear ExecutionException in createCommand.
            if (!TestoConfigurationNames.isFilterOnlyRun(selection) || e.message != missingConfigurationFileMessage()) throw e
        }
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

    // createCommand runs before createTestConsoleProperties for every executor, so the console sees this run's cwd and
    // report targets.
    @Volatile
    private var lastWorkingDirectory: String? = null

    @Volatile
    private var lastReportTargets: List<TestoReportTarget> = emptyList()

    @Volatile
    private var lastToolEnvironment: TestoToolEnvironment? = null

    internal fun captureToolEnvironment(): TestoToolEnvironment {
        val interpreter = interpreter ?: throw ExecutionException("No PHP interpreter configured")
        createCommand(interpreter, mutableMapOf(), mutableListOf(), false)
        return checkNotNull(lastToolEnvironment)
    }

    override fun getWorkingDirectory(
        project: Project,
        settings: PhpTestRunConfigurationSettings,
        config: PhpTestFrameworkConfiguration?
    ): String? = TestoRunPaths.resolveWorkingDirectory(
        customWorkingDirectory = settings.commandLineSettings.workingDirectory,
        configurationFilePath = localConfigurationFile(settings, config),
        executableRoot = { executableProjectRoot(config) },
        fallback = { super.getWorkingDirectory(project, settings, config) },
    )

    private fun executableProjectRoot(config: PhpTestFrameworkConfiguration?): String? {
        val executable = config?.executablePath?.takeIf { it.isNotEmpty() } ?: return null
        val basePath = project.basePath ?: return null
        val local = interpreter?.takeIf { it.isRemote }?.let { PhpToolLauncher(project, it).toLocal(executable) } ?: executable

        return TestoRunPaths.projectRootOfExecutable(local, basePath) {
            LocalFileSystem.getInstance().findFileByPath(it) != null
        }
    }

    private fun localConfigurationFile(
        settings: PhpTestRunConfigurationSettings,
        config: PhpTestFrameworkConfiguration?,
    ): String? {
        val path = getConfigurationFile(settings.runnerSettings, config)?.takeIf { it.isNotEmpty() } ?: return null
        if (settings.runnerSettings.isUseAlternativeConfigurationFile) return path

        val remote = interpreter?.takeIf { it.isRemote } ?: return path
        // A container path is no working directory: an unmapped one is no answer.
        return PhpToolLauncher(project, remote).toLocal(path)
    }

    override fun createCommand(
        interpreter: PhpInterpreter,
        env: MutableMap<String?, String?>,
        arguments: MutableList<String?>,
        frameworkConfig: PhpTestFrameworkConfiguration?,
        withDebugger: Boolean
    ): PhpCommandSettings {
        val executablePath = frameworkConfig?.executablePath
        if (frameworkConfig == null || executablePath.isNullOrEmpty()) {
            throw ExecutionException(
                PhpBundle.message(
                    "php.interpreter.base.configuration.is.not.provided.or.empty",
                    frameworkName,
                    if (interpreter.isRemote) "'${interpreter.name}' interpreter" else "local machine",
                )
            )
        }

        val workingDirectory = getWorkingDirectory(project, settings, frameworkConfig)
        if (workingDirectory.isNullOrEmpty()) {
            throw ExecutionException(PhpBundle.message("php.interpreter.base.configuration.working.directory"))
        }
        lastWorkingDirectory = workingDirectory

        val launcher = PhpToolLauncher(project, interpreter)
        val snapshot = clone() as TestoRunConfiguration
        lastToolEnvironment = PhpStormToolEnvironment(project, interpreter, executablePath, workingDirectory,
            snapshot.settings.commandLineSettings, env)

        myHandler.prepareArguments(arguments, testoSettings)
        addReportFlags(arguments, interpreter)
        val command = launcher.command(
            executablePath,
            workingDirectory,
            settings.commandLineSettings,
            env,
            withDebugger,
            listOf(testoSettings.runnerSettings.command),
        )

        fillTestRunnerArguments(
            project,
            workingDirectory,
            settings.runnerSettings,
            arguments,
            command,
            frameworkConfig,
            myHandler,
            launcher::toInterpreterIfMapped,
        )

        return command
    }

    /**
     * Adds `--log-html` / `--log-junit` for the checked reports, pointed at an IDE-managed folder ([TestoReportFlags]).
     * Skipped on an interpreter the folder cannot be translated for: it would write to a host path it never maps back,
     * so there the reports are left to whatever testo.php configures.
     */
    private fun addReportFlags(arguments: MutableList<String?>, interpreter: PhpInterpreter) {
        lastReportTargets = emptyList()
        val runner = testoSettings.runnerSettings
        if (!runner.logHtml && !runner.logJunit) return

        val html = TestoReportTarget.resolve(project, interpreter, TestoReportFlags.htmlReportFile(project, name).toString())
        val junit = TestoReportTarget.resolve(project, interpreter, TestoReportFlags.junitReportFile(project, name).toString())
        if (!html.isReachable || !junit.isReachable) return
        // Testo's report writers create the parent themselves, but a missing directory is the one avoidable failure
        // between here and a written report, so make sure of it.
        runCatching { Files.createDirectories(Path.of(html.local).parent) }
        arguments.addAll(TestoReportFlags.reportFlagArguments(runner.logHtml, runner.logJunit, html.path, junit.path))
        lastReportTargets = listOfNotNull(html.takeIf { runner.logHtml }, junit.takeIf { runner.logJunit })
    }

    override fun createTestConsoleProperties(executor: Executor): SMTRunnerConsoleProperties {
        val manager = PhpRemoteInterpreterManager.getInstance()

        val interpreter = this.interpreter
        val pathProcessor = when {
            interpreter?.isRemote == true -> manager?.createPathMapper(this.project, interpreter.phpSdkAdditionalData)
            else -> null
        } ?: PhpCommandLinePathProcessor.LOCAL

        val pathMapper = pathProcessor.createPathMapper(this.project)
        return phpStormConsoleProperties(
            this,
            executor,
            pathMapper,
            pathProcessor,
        ).also {
            it.workingDirectory = lastWorkingDirectory
            it.reportTargets = lastReportTargets
            it.toolEnvironment = lastToolEnvironment
        }
    }

    companion object Companion {
        const val ID = "TestoConsoleCommandRunConfiguration"

        /** `local`, the remote connection type, or the SDK data class name when that cannot be read; empty for none. */
        private fun interpreterKindOf(interpreter: PhpInterpreter?): String = when {
            interpreter == null -> ""
            !interpreter.isRemote -> "local"
            else -> (interpreter.phpSdkAdditionalData as? RemoteSdkAdditionalData)
                ?.let { data -> runCatching { data.remoteConnectionType.name }.getOrNull() }
                ?: interpreter.phpSdkAdditionalData?.javaClass?.simpleName.orEmpty()
        }

        // convertToRemote returns the input unchanged when no mapping matches, which is what an already-remote
        // framework path needs; a host path (a per-interpreter configuration fabricated from the local one) is mapped.
        private fun toRemoteIfMapped(path: String, mappings: PathMappingSettings?) =
            mappings?.convertToRemote(path) ?: path
        private fun fillTestRunnerArguments(
            project: Project,
            workingDirectory: String,
            testRunnerSettings: PhpTestRunnerSettings,
            arguments: MutableList<String?>,
            command: PhpCommandSettings,
            configuration: PhpTestFrameworkConfiguration?,
            handler: PhpTestRunConfigurationHandler,
            // An already-remote framework path comes back unchanged; a host one (a per-interpreter configuration
            // fabricated from the local one) is mapped.
            toRemote: (String) -> String,
        ) {
            val testRunnerOptions = testRunnerSettings.testRunnerOptions
            if (StringUtil.isNotEmpty(testRunnerOptions)) {
                command.addArguments(ParametersList.parse(testRunnerOptions!!).toList())
            }

            command.addArguments(arguments)

            val configurationFilePath = getConfigurationFile(testRunnerSettings, configuration)
            if (!configurationFilePath.isNullOrEmpty()) {
                command.addArgument(handler.configFileOption)
                // A remote interpreter's framework paths are usually already remote; running them through the path
                // processor flags a false "Path mappings are not configured", so map only when a mapping matches.
                if (testRunnerSettings.isUseAlternativeConfigurationFile) {
                    command.addPathArgument(configurationFilePath)
                } else {
                    command.addArgument(toRemote(configurationFilePath))
                }
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
