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

        if (runner.testoType.isNotEmpty()) {
            arguments.add("--type")
            arguments.add(runner.testoType)
        }
        if (runner.suite.isNotEmpty()) {
            arguments.add("--suite")
            arguments.add(runner.suite)
        }
        // Testo takes `--group`/`--exclude-group` repeatedly (OR logic), so a comma-separated field becomes one flag
        // per name — that is how a `#[Group('db', 'slow')]` run reaches the CLI.
        for (group in splitNames(runner.group)) {
            arguments.add("--group")
            arguments.add(group)
        }
        for (group in splitNames(runner.excludeGroup)) {
            arguments.add("--exclude-group")
            arguments.add(group)
        }
        if (runner.repeat > 0) {
            arguments.add("--repeat")
            arguments.add(runner.repeat.toString())
        }
        if (runner.parallel > 0) {
            arguments.add("--parallel")
            arguments.add(runner.parallel.toString())
        }
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

    /**
     * Splits a comma-separated option value into individual names, dropping blanks. A comma is a separator; a name
     * that itself contains one carries it escaped as `\,` (see [joinNames]). Any other backslash stays literal, so a
     * name ending in a backslash cannot be followed by another name — an acceptable loss for a one-character escape.
     */
    fun splitNames(value: String): List<String> {
        val names = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length && value[i + 1] == ',' -> {
                    current.append(',')
                    i++
                }

                c == ',' -> {
                    names.add(current.toString())
                    current.clear()
                }

                else -> current.append(c)
            }
            i++
        }
        names.add(current.toString())
        return names
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** The inverse of [splitNames]: joins names into the single persisted field, escaping literal commas as `\,`. */
    fun joinNames(names: List<String>): String = names.joinToString(",") { it.replace(",", "\\,") }

    data class ParsedMethodName(
        val method: String,
        val dataProvider: String,
    )

    fun parseMethodName(methodName: String) = ParsedMethodName(
        method = methodName.substringBefore('#'),
        dataProvider = methodName.substringAfter('#', ""),
    )
}
