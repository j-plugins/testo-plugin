package com.github.xepozz.testo.tests.run

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
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

    @Attribute("group")
    var group: String = "",

    @Attribute("exclude_group")
    var excludeGroup: String = "",

    @Attribute("repeat")
    var repeat: Int = 0,

    @Attribute("parallel")
    var parallel: Int = 0,
) : PhpTestRunnerSettings() {
    companion object Companion {
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
                runnerSettings.group = settings.group
                runnerSettings.excludeGroup = settings.excludeGroup
                runnerSettings.repeat = settings.repeat
                runnerSettings.parallel = settings.parallel
            }

            return runnerSettings
        }
    }
}
