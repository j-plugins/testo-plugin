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

    @Attribute("suite")
    var suite: String = "",

    @Attribute("repeat")
    var repeat: Int = 0,

    @Attribute("parallel")
    var parallel: Int = 0,

    @Attribute("testo_type")
    var testoType: String = "",
) : PhpTestRunnerSettings() {
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
    }

    companion object Companion {
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
                runnerSettings.suite = settings.suite
                runnerSettings.groups = settings.groups.toMutableList()
                runnerSettings.excludeGroups = settings.excludeGroups.toMutableList()
                runnerSettings.legacyGroup = settings.legacyGroup
                runnerSettings.legacyExcludeGroup = settings.legacyExcludeGroup
                runnerSettings.repeat = settings.repeat
                runnerSettings.parallel = settings.parallel
                runnerSettings.testoType = settings.testoType
                runnerSettings.migrateLegacyNames()
            }

            return runnerSettings
        }
    }
}
