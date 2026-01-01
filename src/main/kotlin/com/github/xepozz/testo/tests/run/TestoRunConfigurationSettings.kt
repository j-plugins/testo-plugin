package com.github.xepozz.testo.tests.run

import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRunConfigurationSettings : PhpTestRunConfigurationSettings() {
    override fun createDefault() = TestoRunnerSettings().apply {
        testRunnerOptions = "-q -n --teamcity"
    }

    override fun getRunnerSettings() = getTestoRunnerSettings()

    override fun setRunnerSettings(runnerSettings: PhpTestRunnerSettings) {
        super.setRunnerSettings(TestoRunnerSettings.fromPhpTestRunnerSettings(runnerSettings))
    }

    fun getTestoRunnerSettings(): TestoRunnerSettings {
        val settings = super.getRunnerSettings()
        if (settings is TestoRunnerSettings) {
            return settings
        } else {
            val copy = TestoRunnerSettings.fromPhpTestRunnerSettings(settings)
            this.setTestoRunnerSettings(copy)
            return copy
        }
    }

    fun setTestoRunnerSettings(runnerSettings: TestoRunnerSettings) {
        this.setRunnerSettings(runnerSettings)
    }
}