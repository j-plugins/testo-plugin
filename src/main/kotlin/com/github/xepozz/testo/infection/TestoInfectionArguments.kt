package com.github.xepozz.testo.infection

internal object TestoInfectionArguments {
    // Windows caps a whole command line at 32 KB; past this the filter is dropped and Infection mutates everything the
    // coverage covers, which is the same set, only slower to generate.
    const val MAX_FILTER_LENGTH = 8_000

    fun build(
        coverageDirectory: String,
        sourceFiles: List<String>,
        htmlReport: String?,
        textLog: String? = null,
    ): List<String> = buildList {
        add("--coverage=$coverageDirectory")
        add("--skip-initial-tests")
        add("--test-framework=testo")
        add("--teamcity")
        add("--no-progress")
        add("--no-interaction")
        filter(sourceFiles)?.let { add("--filter=$it") }
        htmlReport?.let { add("--logger-html=$it") }
        textLog?.let {
            add("--logger-text=$it")
            add("--log-verbosity=all")
        }
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
