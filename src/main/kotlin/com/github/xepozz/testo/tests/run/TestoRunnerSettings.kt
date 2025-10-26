package com.github.xepozz.testo.tests.run

import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRunnerSettings(
    var coverageEngine: CoverageEngine = CoverageEngine.XDEBUG,
    var parallelTestingEnabled: Boolean = false,
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

            return runnerSettings
        }
    }
}