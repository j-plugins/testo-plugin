package com.github.xepozz.testo.tests.run

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Transient
import com.intellij.util.xmlb.annotations.XCollection
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

@Tag("TestoRunnerSettings")
class TestoRunnerSettings(
    var dataProviderIndex: Int = -1,
    var dataSetIndex: Int = -1,
    var coverageEngine: CoverageEngine = CoverageEngine.XDEBUG,
    var parallelTestingEnabled: Boolean = false,

    @Attribute("command")
    var command: String = "run",

    /** Workers to run on. 1 sends no flag at all — the CLI has no `--parallel` yet, and the editor keeps it there. */
    @Attribute("parallel")
    var parallel: Int = 1,

    @Attribute("testo_type")
    var testoType: String = "",

    // Which coverage reports a Coverage run requests via CLI flags. Clover is off by default: cobertura carries
    // everything clover does plus branch data. coverage-xml adds the per-test overlay.
    @Attribute("coverage_clover")
    var coverageClover: Boolean = false,

    @Attribute("coverage_cobertura")
    var coverageCobertura: Boolean = true,

    @Attribute("coverage_xml")
    var coverageXml: Boolean = true,

    /** Depth of the analysis: `--coverage-level=<line|branch|path>`, or [COVERAGE_LEVEL_AUTO] to leave it to testo.php. */
    @Attribute("coverage_level")
    var coverageLevel: String = COVERAGE_LEVEL_AUTO,

    /**
     * Extra CLI arguments a Coverage run adds and an ordinary run does not. Benchmarks are excluded by default:
     * they run the same code many times over, which says nothing about coverage and costs the whole run's time.
     */
    @Attribute("coverage_options")
    var coverageOptions: String = DEFAULT_COVERAGE_OPTIONS,

    // Reports written into an IDE-managed folder on every run (`--log-html` / `--log-junit`) and copied into history.
    // HTML is on by default (it opens in a tab); JUnit is off — it exists for external tooling (mutation testing).
    @Attribute("log_html")
    var logHtml: Boolean = true,

    @Attribute("log_junit")
    var logJunit: Boolean = false,
) : PhpTestRunnerSettings() {
    /** Suite names to run, one `--suite` flag each (Testo ORs them). A name is opaque — spaces and all. */
    @get:XCollection(propertyElementName = "suites", style = XCollection.Style.v2)
    var suites: MutableList<String> = mutableListOf()

    /** Group names to run, one `--group` flag each. A name is opaque: whatever the `#[Group]` attribute spells. */
    @get:XCollection(propertyElementName = "groups", style = XCollection.Style.v2)
    var groups: MutableList<String> = mutableListOf()

    /** Group names to skip, one `--exclude-group` flag each. */
    @get:XCollection(propertyElementName = "exclude_groups", style = XCollection.Style.v2)
    var excludeGroups: MutableList<String> = mutableListOf()

    /**
     * The pre-list persisted form: a single comma-separated string. Only ever read — [migrateLegacyNames] moves it
     * into [groups] and clears it, so saved configurations survive the format change and are rewritten as lists.
     */
    @get:Attribute("group")
    var legacyGroup: String = ""

    /** The pre-list persisted form of [excludeGroups]; see [legacyGroup]. */
    @get:Attribute("exclude_group")
    var legacyExcludeGroup: String = ""

    /** The single-suite persisted form of [suites]. Never split: a suite name may hold anything, commas included. */
    @get:Attribute("suite")
    var legacySuite: String = ""

    // Set only on a "Rerun Failed Tests" clone, never persisted to the saved configuration.
    @Transient
    var rerunFilters: List<String> = emptyList()

    /** Folds any legacy comma-separated names into the list fields. Idempotent: a migrated setting has nothing to do. */
    fun migrateLegacyNames() {
        if (legacyGroup.isNotEmpty()) {
            groups = parseNames(legacyGroup).toMutableList()
            legacyGroup = ""
        }
        if (legacyExcludeGroup.isNotEmpty()) {
            excludeGroups = parseNames(legacyExcludeGroup).toMutableList()
            legacyExcludeGroup = ""
        }
        if (legacySuite.isNotEmpty()) {
            suites = mutableListOf(legacySuite)
            legacySuite = ""
        }
    }

    companion object Companion {
        const val DEFAULT_COVERAGE_OPTIONS = "--type=!bench"

        /** No `--coverage-level` flag at all: the level configured in testo.php stands. */
        const val COVERAGE_LEVEL_AUTO = "auto"

        val COVERAGE_LEVELS: List<String> = listOf(COVERAGE_LEVEL_AUTO, "line", "branch", "path")

        /**
         * Reads the comma-separated text of a Group field into names, dropping blanks. The comma lives in the editor
         * (a single text field cannot hold a list otherwise) and in the legacy persisted form — never in the model,
         * so a name coming from `#[Group]` reaches the command line untouched.
         */
        @JvmStatic
        fun parseNames(text: String): List<String> = text
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        /** Renders names back into the editor's text field. */
        @JvmStatic
        fun formatNames(names: List<String>): String = names.joinToString(", ")

        @JvmStatic
        fun fromPhpTestRunnerSettings(settings: PhpTestRunnerSettings): TestoRunnerSettings {
            val runnerSettings = TestoRunnerSettings()

            runnerSettings.scope = settings.scope
            runnerSettings.selectedType = settings.selectedType
            runnerSettings.directoryPath = settings.directoryPath
            runnerSettings.filePath = settings.filePath
            runnerSettings.methodName = settings.methodName
            runnerSettings.isUseAlternativeConfigurationFile = settings.isUseAlternativeConfigurationFile
            runnerSettings.configurationFilePath = settings.configurationFilePath
            runnerSettings.testRunnerOptions = settings.testRunnerOptions

            if (settings is TestoRunnerSettings) {
                runnerSettings.dataProviderIndex = settings.dataProviderIndex
                runnerSettings.dataSetIndex = settings.dataSetIndex
                runnerSettings.coverageEngine = settings.coverageEngine
                runnerSettings.parallelTestingEnabled = settings.parallelTestingEnabled
                runnerSettings.command = settings.command
                runnerSettings.suites = settings.suites.toMutableList()
                runnerSettings.groups = settings.groups.toMutableList()
                runnerSettings.excludeGroups = settings.excludeGroups.toMutableList()
                runnerSettings.legacyGroup = settings.legacyGroup
                runnerSettings.legacyExcludeGroup = settings.legacyExcludeGroup
                runnerSettings.legacySuite = settings.legacySuite
                runnerSettings.parallel = settings.parallel
                runnerSettings.testoType = settings.testoType
                runnerSettings.coverageClover = settings.coverageClover
                runnerSettings.coverageCobertura = settings.coverageCobertura
                runnerSettings.coverageXml = settings.coverageXml
                runnerSettings.coverageLevel = settings.coverageLevel
                runnerSettings.coverageOptions = settings.coverageOptions
                runnerSettings.logHtml = settings.logHtml
                runnerSettings.logJunit = settings.logJunit
                runnerSettings.migrateLegacyNames()
            }

            return runnerSettings
        }
    }
}
