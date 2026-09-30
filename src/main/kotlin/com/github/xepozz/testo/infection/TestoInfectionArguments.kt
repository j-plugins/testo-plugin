package com.github.xepozz.testo.infection

import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.intellij.execution.configurations.ParametersList

/** The Infection flags a run configuration asks for, beyond the ones every mutation run needs. */
internal data class TestoInfectionOptions(
    val scope: String = TestoRunnerSettings.INFECTION_SCOPE_COVERED,
    val gitDiffBase: String = "",
    val threads: String = "",
    val onlyCoveringTestCases: Boolean = false,
    val withUncovered: Boolean = false,
    val timeoutsAsEscaped: Boolean = false,
    val mutators: String = "",
    val staticAnalysisTool: String = "",
    val extra: String = "",
    /** Infection's `--id`: this one mutant alone. It takes a single ID, so a rerun of several is one process each. */
    val mutantId: String? = null,
    /** The source file [mutantId] is in, as the coverage spells it: spares Infection generating every other file's mutants. */
    val mutantFile: String? = null,
    /** A file or directory the run is narrowed to; Infection's `--filter` matches it anywhere in a source's real path. */
    val filter: String? = null,
) {
    companion object {
        fun of(settings: TestoRunnerSettings) = TestoInfectionOptions(
            scope = settings.infectionScope,
            gitDiffBase = settings.infectionGitDiffBase,
            threads = settings.infectionThreads,
            onlyCoveringTestCases = settings.infectionOnlyCoveringTestCases,
            withUncovered = settings.infectionWithUncovered,
            timeoutsAsEscaped = settings.infectionTimeoutsAsEscaped,
            mutators = settings.infectionMutators,
            staticAnalysisTool = settings.infectionStaticAnalysisTool,
            extra = settings.infectionOptions,
        )
    }
}

internal object TestoInfectionArguments {
    // Windows caps a whole command line at 32 KB; past this the filter is dropped and Infection mutates everything the
    // coverage covers, which is the same set, only slower to generate.
    const val MAX_FILTER_LENGTH = 8_000

    fun build(
        coverageDirectory: String,
        sourceFiles: List<String>,
        htmlReport: String?,
        textLog: String? = null,
        options: TestoInfectionOptions = TestoInfectionOptions(),
    ): List<String> = buildList {
        add("--coverage=$coverageDirectory")
        add("--skip-initial-tests")
        add("--test-framework=testo")
        add("--teamcity")
        add("--no-progress")
        add("--no-interaction")
        when {
            options.mutantId != null -> {
                options.mutantFile?.let { add("--filter=$it") }
                add("--id=${options.mutantId}")
            }
            options.filter != null -> add("--filter=${options.filter}")
            options.scope == TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES -> {
                add("--git-diff-lines")
                options.gitDiffBase.trim().takeIf { it.isNotEmpty() }?.let { add("--git-diff-base=$it") }
            }
            options.scope == TestoRunnerSettings.INFECTION_SCOPE_ALL -> Unit
            // Without --with-uncovered, Infection skips every file this run's coverage has no test for before parsing it, so
            // the deprecated --filter would change nothing. With it, the filter is what keeps the other files out.
            options.withUncovered -> filter(sourceFiles)?.let { add("--filter=$it") }
        }
        options.threads.trim().takeIf { it.isNotEmpty() }?.let { add("--threads=$it") }
        if (options.onlyCoveringTestCases) add("--only-covering-test-cases")
        if (options.withUncovered) add("--with-uncovered")
        if (options.timeoutsAsEscaped) add("--with-timeouts")
        options.mutators.trim().takeIf { it.isNotEmpty() }?.let { add("--mutators=$it") }
        options.staticAnalysisTool.trim().takeIf { it.isNotEmpty() }?.let { add("--static-analysis-tool=$it") }
        htmlReport?.let { add("--logger-html=$it") }
        textLog?.let {
            add("--logger-text=$it")
            add("--log-verbosity=all")
        }
        // Last, so a flag typed here wins over the same flag set above.
        addAll(ParametersList.parse(options.extra))
    }

    fun filter(sourceFiles: List<String>): String? =
        sourceFiles.joinToString(",").takeIf { it.isNotEmpty() && it.length <= MAX_FILTER_LENGTH }
}

internal object TestoInfectionExecutable {
    private val PROJECT_CANDIDATES = listOf(
        "vendor/bin/infection",
        "tools/infection/vendor/bin/infection",
        "infection.phar",
        "tools/infection.phar",
    )

    /** Next to the Testo binary first (one composer `vendor/bin`), then the usual install spots under the project. */
    fun candidates(testoExecutable: String, projectRoot: String): List<String> {
        val sibling = testoExecutable.replace('\\', '/').substringBeforeLast('/', "")
            .takeIf { it.isNotEmpty() }
            ?.let { "$it/infection" }
        val root = projectRoot.replace('\\', '/').trimEnd('/')
        return (listOfNotNull(sibling) + PROJECT_CANDIDATES.map { "$root/$it" }).distinct()
    }

    fun find(testoExecutable: String, projectRoot: String, exists: (String) -> Boolean): String? =
        candidates(testoExecutable, projectRoot).firstOrNull(exists)
}
