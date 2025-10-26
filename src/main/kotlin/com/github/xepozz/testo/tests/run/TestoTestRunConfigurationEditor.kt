package com.github.xepozz.testo.tests.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationEditor

class TestoTestRunConfigurationEditor(
    private val parentEditor: PhpTestRunConfigurationEditor,
    val configuration: TestoRunConfiguration
) : SettingsEditor<TestoRunConfiguration>() {
    private val myMainPanel = panel {
        val runnerSettings = configuration.testoSettings.runnerSettings

        row {
            cell(parentEditor.component)
        }
    }

    override fun createEditor() = myMainPanel

    override fun isSpecificallyModified() = myMainPanel.isModified() || parentEditor.isSpecificallyModified

    override fun resetEditorFrom(testoRunConfiguration: TestoRunConfiguration) {
        myMainPanel.reset()
        parentEditor.resetFrom(testoRunConfiguration)
    }

    override fun applyEditorTo(testoRunConfiguration: TestoRunConfiguration) {
        parentEditor.applyTo(testoRunConfiguration)
        myMainPanel.apply()
    }
}