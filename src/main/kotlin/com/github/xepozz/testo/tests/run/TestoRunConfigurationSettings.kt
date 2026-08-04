package com.github.xepozz.testo.tests.run

import com.intellij.util.xmlb.annotations.Property
import com.intellij.util.xmlb.annotations.Transient
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRunConfigurationSettings : PhpTestRunConfigurationSettings() {
    override fun createDefault() = TestoRunnerSettings().apply {
        testRunnerOptions = "-q -n --teamcity"
    }

    override fun getRunnerSettings() = getTestoRunnerSettings()

    @Transient
    override fun setRunnerSettings(runnerSettings: PhpTestRunnerSettings) {
        super.setRunnerSettings(TestoRunnerSettings.fromPhpTestRunnerSettings(runnerSettings))
    }

    @Property(surroundWithTag = false)
    fun getTestoRunnerSettings(): TestoRunnerSettings {
        val settings = super.getRunnerSettings()
        if (settings is TestoRunnerSettings) {
            // Deserialization writes the fields directly, so this is the first place that sees a configuration saved
            // in the pre-list format; folding it here keeps every reader (handler, producer, editor) list-only.
            settings.migrateLegacyNames()
            return settings
        }

        val copy = TestoRunnerSettings.fromPhpTestRunnerSettings(settings)
        setTestoRunnerSettings(copy)
        return copy
    }

    fun setTestoRunnerSettings(runnerSettings: TestoRunnerSettings) {
        setRunnerSettings(runnerSettings)
    }
}
