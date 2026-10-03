package com.github.xepozz.testo.launch

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunPaths
import com.intellij.execution.ExecutionException

/** The Testo CLI arguments a run gets — everything after the command, except the runner options and reports. */
object TestoCommandLine {
    /** The option a configuration file's path follows. */
    const val CONFIG_OPTION = "--config"


    /** `--type`, `--suite`, `--group`, excluded `--group !…` and rerun `--filter`, in that order. */
    fun selectionArguments(selection: TestoRunSelection): List<String> = buildList {
        if (selection.testoType.isNotEmpty()) {
            add("--type")
            add(selection.testoType)
        }
        for (suite in selection.suites) {
            add("--suite")
            add(suite)
        }
        // Testo takes `--group` repeatedly (OR logic), one name per flag — that is how a `#[Group('db', 'slow')]` run
        // reaches the CLI. Exclusion is the same flag with a `!` prefix; the CLI has no --exclude-group at all.
        for (group in selection.groups) {
            add("--group")
            add(group)
        }
        for (group in selection.excludeGroups) {
            add("--group")
            add(if (group.startsWith("!")) group else "!$group")
        }
        // No --parallel until Testo's CLI takes it; then 1 = no flag, 0 = bare --parallel (auto), >1 = --parallel N.
        for (filter in selection.rerunFilters) {
            add("--filter")
            add(filter)
        }
    }

    /** A [TestoScope.TYPE] run: the named suite. */
    fun typeArguments(type: String): List<String> = listOf("--suite", type)

    fun directoryArguments(directory: String, workingDirectory: String): List<String> =
        if (directory.isEmpty()) emptyList() else pathArguments(directory, workingDirectory, directoryScope = true)

    fun fileArguments(file: String, workingDirectory: String): List<String> =
        if (file.isEmpty()) emptyList() else pathArguments(file, workingDirectory, directoryScope = false)

    /** A [TestoScope.METHOD] run: the file, the `--filter` selector and, after `#`, the data provider. */
    fun methodArguments(file: String, methodName: String, workingDirectory: String): List<String> {
        if (file.isEmpty()) return emptyList()

        val parsed = parseMethodName(methodName)

        return buildList {
            addAll(pathArguments(file, workingDirectory, directoryScope = false))
            if (parsed.method.isNotEmpty()) {
                add("--filter")
                add(parsed.method)
            }
            if (parsed.dataProvider.isNotEmpty()) {
                add("--data-provider")
                add(parsed.dataProvider)
            }
        }
    }

    // A bare `--path` would silently run the whole suite, so an unresolvable target fails the run instead.
    fun pathArguments(
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
