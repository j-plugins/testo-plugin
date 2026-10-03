package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.intellij.execution.configurations.LocatableRunConfigurationOptions

/**
 * What an OpenIDE Testo configuration saves: every field of [TestoRunSelection] but the rerun filters, which only a
 * "Rerun Failed Tests" clone carries, and the launch fields OpenIDE's own form edits. No interpreter: a run takes the
 * active interpreter profile of the project.
 */
class TestoRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var scope by enum(TestoScope.CONFIGURATION_FILE)
    var selectedType by string()
    var directoryPath by string()
    var filePath by string()
    var methodName by string()
    var useAlternativeConfigurationFile by property(false)
    var configurationFilePath by string()
    var testRunnerOptions by string(DEFAULT_RUNNER_OPTIONS)
    var command by string(DEFAULT_COMMAND)
    var testoType by string("")
    var suites by list<String>()
    var groups by list<String>()
    var excludeGroups by list<String>()
    var dataProviderIndex by property(-1)
    var dataSetIndex by property(-1)
    var coverageDriver by enum(TestoCoverageDriver.XDEBUG)
    var coverageClover by property(false)
    var coverageCobertura by property(true)
    var coverageXml by property(true)
    var coverageLevel by string(TestoRunSelection.COVERAGE_LEVEL_AUTO)
    var coverageOptions by string(TestoRunSelection.DEFAULT_COVERAGE_OPTIONS)
    var parallel by property(1)
    var parallelTestingEnabled by property(false)
    var logHtml by property(true)
    var logJunit by property(true)
    var infectionScope by string(TestoRunSelection.INFECTION_SCOPE_COVERED)
    var infectionGitDiffBase by string("")
    var infectionThreads by string("")
    var infectionOnlyCoveringTestCases by property(false)
    var infectionWithUncovered by property(false)
    var infectionTimeoutsAsEscaped by property(false)
    var infectionMutators by string("")
    var infectionStaticAnalysisTool by string("")
    var infectionOptions by string("")

    /** The Testo binary; empty takes the one of the test framework page, then of composer. */
    var binaryPath by string()

    /** Where the process runs; empty leaves it to Testo's rules — the configuration file's directory, then the project. */
    var workingDirectory by string()
    var environmentVariables by map<String, String>()
    var passParentEnvironment by property(true)

    companion object {
        /** Quiet, no interaction, the machine-readable stream the results tree is built from — as on PhpStorm. */
        const val DEFAULT_RUNNER_OPTIONS = "-q -n --teamcity"

        const val DEFAULT_COMMAND = "run"
    }
}
