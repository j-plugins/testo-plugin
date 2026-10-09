package com.github.xepozz.testo.launch

/**
 * What a Testo run configuration runs and how, in terms of Testo alone: every setting a Testo configuration saves,
 * with none of the IDE's own (interpreter, its options, environment, working directory).
 *
 * The decisions the plugin makes — which run a source context produces, which arguments the CLI gets, how a
 * configuration is named — are functions over this. A configuration of a particular IDE only translates it to and
 * from its own saved settings.
 */
data class TestoRunSelection(
    var scope: TestoScope = TestoScope.CONFIGURATION_FILE,
    /** The suite a [TestoScope.TYPE] run passes to `--suite`. */
    var selectedType: String? = null,
    var directoryPath: String? = null,
    var filePath: String? = null,
    /** The `--filter` selector of a [TestoScope.METHOD] run, optionally followed by `#<data provider>`. */
    var methodName: String? = null,
    /** Whether [configurationFilePath] was chosen explicitly rather than taken from the framework settings. */
    var useAlternativeConfigurationFile: Boolean = false,
    var configurationFilePath: String? = null,
    var testRunnerOptions: String? = null,
    var command: String = "run",
    var testoType: String = "",
    var suites: List<String> = emptyList(),
    var groups: List<String> = emptyList(),
    var excludeGroups: List<String> = emptyList(),
    /** The failed tests a "Rerun Failed Tests" clone selects; never saved. */
    var rerunFilters: List<String> = emptyList(),
    var dataProviderIndex: Int = -1,
    var dataSetIndex: Int = -1,
    var coverageDriver: TestoCoverageDriver = TestoCoverageDriver.XDEBUG,
    var coverageClover: Boolean = false,
    var coverageCobertura: Boolean = true,
    var coverageXml: Boolean = true,
    var coverageLevel: String = COVERAGE_LEVEL_AUTO,
    var coverageOptions: String = DEFAULT_COVERAGE_OPTIONS,
    var parallel: Int = 1,
    var parallelTestingEnabled: Boolean = false,
    var logHtml: Boolean = true,
    var logJunit: Boolean = true,
    var infectionScope: String = INFECTION_SCOPE_COVERED,
    var infectionGitDiffBase: String = "",
    var infectionThreads: String = "",
    var infectionOnlyCoveringTestCases: Boolean = false,
    var infectionWithUncovered: Boolean = false,
    var infectionTimeoutsAsEscaped: Boolean = false,
    var infectionMutators: String = "",
    var infectionStaticAnalysisTool: String = "",
    var infectionOptions: String = "",
) {
    /** Takes every value of [other], so a selection someone holds on to sees the result of work done on a copy. */
    fun assign(other: TestoRunSelection) {
        scope = other.scope
        selectedType = other.selectedType
        directoryPath = other.directoryPath
        filePath = other.filePath
        methodName = other.methodName
        useAlternativeConfigurationFile = other.useAlternativeConfigurationFile
        configurationFilePath = other.configurationFilePath
        testRunnerOptions = other.testRunnerOptions
        command = other.command
        testoType = other.testoType
        suites = other.suites
        groups = other.groups
        excludeGroups = other.excludeGroups
        rerunFilters = other.rerunFilters
        dataProviderIndex = other.dataProviderIndex
        dataSetIndex = other.dataSetIndex
        coverageDriver = other.coverageDriver
        coverageClover = other.coverageClover
        coverageCobertura = other.coverageCobertura
        coverageXml = other.coverageXml
        coverageLevel = other.coverageLevel
        coverageOptions = other.coverageOptions
        parallel = other.parallel
        parallelTestingEnabled = other.parallelTestingEnabled
        logHtml = other.logHtml
        logJunit = other.logJunit
        infectionScope = other.infectionScope
        infectionGitDiffBase = other.infectionGitDiffBase
        infectionThreads = other.infectionThreads
        infectionOnlyCoveringTestCases = other.infectionOnlyCoveringTestCases
        infectionWithUncovered = other.infectionWithUncovered
        infectionTimeoutsAsEscaped = other.infectionTimeoutsAsEscaped
        infectionMutators = other.infectionMutators
        infectionStaticAnalysisTool = other.infectionStaticAnalysisTool
        infectionOptions = other.infectionOptions
    }

    companion object {
        const val INFECTION_SCOPE_COVERED = "covered"
        const val INFECTION_SCOPE_GIT_LINES = "git-lines"
        const val INFECTION_SCOPE_ALL = "all"
        val INFECTION_SCOPES = listOf(INFECTION_SCOPE_COVERED, INFECTION_SCOPE_GIT_LINES, INFECTION_SCOPE_ALL)
        val INFECTION_THREADS = listOf("", "max", "1", "2", "4", "8")
        val INFECTION_STATIC_ANALYSIS_TOOLS = listOf("", "phpstan")

        const val DEFAULT_COVERAGE_OPTIONS = "--type=!bench"

        /** No `--coverage-level` flag at all: the level configured in testo.php stands. */
        const val COVERAGE_LEVEL_AUTO = "auto"
    }
}

/** What a run is narrowed to. */
enum class TestoScope { TYPE, DIRECTORY, FILE, METHOD, CONFIGURATION_FILE }

/** The extension that collects coverage. Testo's editor offers the first two; the third is only ever read back. */
enum class TestoCoverageDriver { XDEBUG, PCOV, PHPDBG }
