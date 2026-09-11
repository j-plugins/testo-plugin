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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.PathUtil
import com.intellij.util.PathMappingSettings
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
import java.nio.file.Files

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

    /**
     * Testo's working directory is the PHP/Testo project root — the parent of `testo.php` — not necessarily the
     * PhpStorm project root. Using [Project.getBasePath] alone breaks remote interpreters whose path mapping starts
     * at a subdirectory (Docker Compose: `…/app` → `/var/www/project`) and leaves `--path` empty after relativize.
     *
     * Prefer an explicit custom WD, then the active configuration file's parent, then the platform's composer /
     * content-root fallback ([PhpTestRunConfiguration.getWorkingDirectory]).
     *
     * Configuration / executable paths in Test Framework settings are often already remote (`/var/www/project/…`).
     * Those must be reverse-mapped to a local path before taking the parent — otherwise `--path` is relativized
     * against `/var/www/project` while the test file is still a WSL/`/home/…` path and the flag is dropped.
     */
    override fun getWorkingDirectory(
        project: Project,
        settings: PhpTestRunConfigurationSettings,
        config: PhpTestFrameworkConfiguration?,
    ): String? = TestoRunPaths.resolveWorkingDirectory(
        customWorkingDirectory = settings.commandLineSettings.workingDirectory?.let { localizePath(it) },
        configurationFilePath = getConfigurationFile(settings.runnerSettings, config)?.let { localizePath(it) },
        fallback = { super.getWorkingDirectory(project, settings, config) },
    )

    /**
     * Maps a path that may already be remote (as stored in Test Framework settings) back to the host filesystem.
     *
     * Returns an IDE-visible local form (`//wsl.localhost/…` or a Windows path) — never the bare `/home/…` inside a
     * WSL UNC. [checkConfiguration] resolves the working directory through LocalFileSystem; `/home/…` is invisible
     * to PhpStorm on Windows even when it is the right Linux path for `--path` relativize (that uses [TestoRunPaths.canonicalize]).
     */
    private fun localizePath(path: String): String {
        val independent = FileUtil.toSystemIndependentName(path)

        fun visibleLocal(p: String): String? =
            LocalFileSystem.getInstance().findFileByPath(p)?.let { FileUtil.toSystemIndependentName(it.path) }

        visibleLocal(independent)?.let { return it }
        // Lookup via the `/home/…` form is fine; prefer the path VFS actually stores (often WSL UNC).
        visibleLocal(TestoRunPaths.canonicalize(independent))?.let { return it }

        val remoteInterpreter = interpreter?.takeIf { it.isRemote } ?: return independent
        val mappings = pathMappings(remoteInterpreter) ?: return independent
        val local = mappings.convertToLocal(independent)?.takeIf { it.isNotEmpty() }
            ?: mappings.convertToLocal(TestoRunPaths.canonicalize(independent))?.takeIf { it.isNotEmpty() }
            ?: return independent
        val normalized = FileUtil.toSystemIndependentName(local)
        return visibleLocal(normalized)
            ?: visibleLocal(TestoRunPaths.canonicalize(normalized))
            ?: normalized
    }

    private fun pathMappings(interpreter: PhpInterpreter): PathMappingSettings? {
        val manager = PhpRemoteInterpreterManager.getInstance() ?: return null
        return manager.createPathMappings(project, interpreter.phpSdkAdditionalData)
    }

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

        // Test Framework settings often store already-remote paths (`/var/www/project/…`). Feeding those to
        // setScript/addPathArgument makes canProcess fail and pops a false "Path mappings are not configured"
        // warning even when Docker Compose mappings are fine — reverse-map to a local path first.
        val localExecutable = localizePath(executablePath)

        val workingDirectory = getWorkingDirectory(project, settings, frameworkConfig)
        if (workingDirectory.isNullOrEmpty()) {
            throw ExecutionException(PhpBundle.message("php.interpreter.base.configuration.working.directory"))
        }
        command.setWorkingDir(workingDirectory)

        myHandler.prepareArguments(arguments, testoSettings)
        addReportFlags(arguments, interpreter)
        myHandler.prepareCommand(project, command, localExecutable, null, testoSettings.runnerSettings.command)

        command.importCommandLineSettings(settings.commandLineSettings, workingDirectory)
        command.addEnvs(env)

        fillTestRunnerArguments(
            workingDirectory,
            settings.runnerSettings,
            arguments,
            command,
            frameworkConfig,
            myHandler,
        )

        return command
    }

    /**
     * Adds `--log-html` / `--log-junit` for the checked reports, pointed at an IDE-managed folder ([TestoReportFlags]).
     * Local interpreters only: a remote one would write these to a host path it never maps back, so there the reports
     * are left to whatever testo.php configures — no worse than before the flags existed.
     */
    private fun addReportFlags(arguments: MutableList<String?>, interpreter: PhpInterpreter) {
        if (interpreter.isRemote) return
        val runner = testoSettings.runnerSettings
        if (!runner.logHtml && !runner.logJunit) return

        val htmlPath = TestoReportFlags.htmlReportFile(project, name)
        val junitPath = TestoReportFlags.junitReportFile(project, name)
        // Testo's report writers create the parent themselves, but a missing directory is the one avoidable failure
        // between here and a written report, so make sure of it.
        runCatching { Files.createDirectories(htmlPath.parent) }
        arguments.addAll(
            TestoReportFlags.reportFlagArguments(runner.logHtml, runner.logJunit, htmlPath.toString(), junitPath.toString())
        )
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
    }

    private fun fillTestRunnerArguments(
        workingDirectory: String,
        testRunnerSettings: PhpTestRunnerSettings,
        arguments: MutableList<String?>,
        command: PhpCommandSettings,
        configuration: PhpTestFrameworkConfiguration?,
        handler: PhpTestRunConfigurationHandler,
    ) {
        val testRunnerOptions = TestoRunnerSettings.effectiveTestRunnerOptions(testRunnerSettings.testRunnerOptions)
        command.addArguments(ParametersList.parse(testRunnerOptions).toList())

        command.addArguments(arguments)

        val configurationFilePath = getConfigurationFile(testRunnerSettings, configuration)
            ?.takeIf { it.isNotEmpty() }
            ?.let { localizePath(it) }
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
