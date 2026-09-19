package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoBundle
import com.intellij.execution.ExecutionException
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
            // The caller already mapped the executable if a mapping matched — see TestoRunConfiguration.createCommand.
            setScript(exe, !isRemote)
            addArgument(command)
        }
    }

    fun prepareArguments(arguments: MutableList<String?>, testoSettings: TestoRunConfigurationSettings) {
        val runner = testoSettings.runnerSettings

        if (runner.testoType.isNotEmpty()) {
            arguments.add("--type")
            arguments.add(runner.testoType)
        }
        for (suite in runner.suites) {
            arguments.add("--suite")
            arguments.add(suite)
        }
        // Testo takes `--group` repeatedly (OR logic), one name per flag — that is how a `#[Group('db', 'slow')]` run
        // reaches the CLI. Exclusion is the same flag with a `!` prefix; the CLI has no --exclude-group at all.
        for (group in runner.groups) {
            arguments.add("--group")
            arguments.add(group)
        }
        for (group in runner.excludeGroups) {
            arguments.add("--group")
            arguments.add(if (group.startsWith("!")) group else "!$group")
        }
        // No --parallel until Testo's CLI takes it; then 1 = no flag, 0 = bare --parallel (auto), >1 = --parallel N.
        for (filter in runner.rerunFilters) {
            arguments.add("--filter")
            arguments.add(filter)
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
        testoPathArguments(directory, workingDirectory, directoryScope = true)
            .forEach { phpCommandSettings.addArgument(it) }
    }

    override fun runFile(
        project: Project,
        phpCommandSettings: PhpCommandSettings,
        file: String,
        workingDirectory: String
    ) {
        if (file.isEmpty()) return
        testoPathArguments(file, workingDirectory, directoryScope = false)
            .forEach { phpCommandSettings.addArgument(it) }
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
            testoPathArguments(file, workingDirectory, directoryScope = false).forEach { addArgument(it) }
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

    // A bare `--path` would silently run the whole suite, so an unresolvable target fails the run instead.
    fun testoPathArguments(
        path: String,
        workingDirectory: String,
        directoryScope: Boolean,
    ): List<String> = when (val resolution = TestoRunPaths.relativePath(path, workingDirectory)) {
        is TestoRunPaths.PathResolution.Relative -> listOf("--path", resolution.path)
        TestoRunPaths.PathResolution.WorkingDirectory, TestoRunPaths.PathResolution.Ancestor ->
            if (directoryScope) emptyList() else throw outsideWorkingDirectory(path, workingDirectory)
        TestoRunPaths.PathResolution.Unrelated -> throw outsideWorkingDirectory(path, workingDirectory)
    }

    private fun outsideWorkingDirectory(path: String, workingDirectory: String) =
        ExecutionException(TestoBundle.message("testo.run.path.outside", path, workingDirectory))

    data class ParsedMethodName(
        val method: String,
        val dataProvider: String,
    )

    fun parseMethodName(methodName: String) = ParsedMethodName(
        method = methodName.substringBefore('#'),
        dataProvider = methodName.substringAfter('#', ""),
    )
}
