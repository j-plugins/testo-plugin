package com.github.xepozz.testo.tests.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationEditor
import java.lang.reflect.InvocationTargetException
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class TestoTestRunConfigurationEditor(
    private val parentEditor: PhpTestRunConfigurationEditor,
    val configuration: TestoRunConfiguration
) : SettingsEditor<TestoRunConfiguration>() {
    private val commandField = ComboBox(arrayOf("run")).apply { isEditable = true }
    private val suiteField = JBTextField()
    private val groupField = JBTextField()
    private val excludeGroupField = JBTextField()
    private val repeatField = JSpinner(SpinnerNumberModel(0, 0, 10000, 1))
    private val parallelField = JSpinner(SpinnerNumberModel(0, 0, 64, 1))

    private val myMainPanel = panel {
        row {
            cell(parentEditor.component)
                .align(AlignX.FILL)
        }.layout(RowLayout.LABEL_ALIGNED)

        group("Testo Options") {
            row {
                label("Command")
                    .gap(RightGap.COLUMNS)
                cell(commandField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Subcommand to execute (default: run)")

            row {
                label("Suite")
                    .gap(RightGap.COLUMNS)
                cell(suiteField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--suite=<name>")

            row {
                label("Group")
                    .gap(RightGap.COLUMNS)
                cell(groupField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--group=<name>")

            row {
                label("Exclude group")
                    .gap(RightGap.COLUMNS)
                cell(excludeGroupField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--exclude-group=<name>")

            row {
                label("Repeat")
                    .gap(RightGap.COLUMNS)
                cell(repeatField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--repeat=<N> (0 = disabled)")

            row {
                label("Parallel")
                    .gap(RightGap.COLUMNS)
                cell(parallelField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--parallel=<N> (0 = disabled)")
        }
    }

    init {
        val listener = { fireEditorStateChanged() }
        val documentAdapter = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = listener()
        }

        commandField.addActionListener { listener() }
        suiteField.document.addDocumentListener(documentAdapter)
        groupField.document.addDocumentListener(documentAdapter)
        excludeGroupField.document.addDocumentListener(documentAdapter)
        repeatField.addChangeListener { listener() }
        parallelField.addChangeListener { listener() }
    }

    override fun createEditor(): JComponent = myMainPanel

    override fun isSpecificallyModified(): Boolean {
        val runner = configuration.testoSettings.runnerSettings
        return commandField.selectedItem != runner.command
                || suiteField.text != runner.suite
                || groupField.text != runner.group
                || excludeGroupField.text != runner.excludeGroup
                || (repeatField.value as Int) != runner.repeat
                || (parallelField.value as Int) != runner.parallel
                || parentEditor.isSpecificallyModified
    }

    override fun resetEditorFrom(testoRunConfiguration: TestoRunConfiguration) {
        val runnerSettings = testoRunConfiguration.testoSettings.runnerSettings
        commandField.selectedItem = runnerSettings.command
        suiteField.text = runnerSettings.suite
        groupField.text = runnerSettings.group
        excludeGroupField.text = runnerSettings.excludeGroup
        repeatField.value = runnerSettings.repeat
        parallelField.value = runnerSettings.parallel

        parentEditor.javaClass.declaredMethods.find { it.name == "resetEditorFrom" && it.parameterCount == 1 }?.let {
            it.isAccessible = true
            it.invoke(parentEditor, testoRunConfiguration)
        } ?: parentEditor.resetFrom(testoRunConfiguration)
    }

    override fun applyEditorTo(testoRunConfiguration: TestoRunConfiguration) {
        parentEditor.javaClass.declaredMethods.find { it.name == "applyEditorTo" && it.parameterCount == 1 }?.let {
            it.isAccessible = true
            try {
                it.invoke(parentEditor, testoRunConfiguration)
            } catch (exception: InvocationTargetException) {
                if (exception.cause?.javaClass?.simpleName == "ReadOnlyModificationException") {
                    return@let
                }
                throw exception
            }
        } ?: parentEditor.applyTo(testoRunConfiguration)

        val runnerSettings = testoRunConfiguration.testoSettings.runnerSettings
        runnerSettings.command = commandField.selectedItem as? String ?: "run"
        runnerSettings.suite = suiteField.text
        runnerSettings.group = groupField.text
        runnerSettings.excludeGroup = excludeGroupField.text
        runnerSettings.repeat = repeatField.value as? Int ?: 0
        runnerSettings.parallel = parallelField.value as? Int ?: 0
    }
}
