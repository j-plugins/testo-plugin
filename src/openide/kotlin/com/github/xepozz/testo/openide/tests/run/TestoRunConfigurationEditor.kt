package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.index.TestoGroupsIndex
import com.github.xepozz.testo.launch.TestoCoverageDriver
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.tests.run.TestoTagsField
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * The form of an OpenIDE Testo configuration. It has no interpreter field: a run takes the active interpreter profile of
 * the project, and the form says so.
 */
class TestoRunConfigurationEditor(private val project: Project) : SettingsEditor<TestoRunConfiguration>() {
    private val scopeField = ComboBox(SCOPES.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { scope -> scopeName(scope) }
        addActionListener { updateScopeFields() }
    }
    private val typeField = JBTextField()
    private val directoryField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private val fileField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor())
    }
    private val methodField = JBTextField()
    private val useConfigurationFileBox = JBCheckBox("Use a configuration file").apply { addActionListener { updateScopeFields() } }
    private val configurationFileField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor())
    }

    private val commandField = JBTextField()
    private val testoTypeField = JBTextField()
    private val suiteField = TestoTagsField(
        TestoBundle.message("testo.tags.suites.empty"),
        TestoBundle.message("testo.tags.suites.add"),
    )
    private val groupField = TestoTagsField(
        TestoBundle.message("testo.tags.groups.empty"),
        TestoBundle.message("testo.tags.groups.add"),
    ) { TestoGroupsIndex.allGroups(project) }
    private val excludeGroupField = TestoTagsField(
        TestoBundle.message("testo.tags.groups.empty"),
        TestoBundle.message("testo.tags.groups.exclude.add"),
    ) { TestoGroupsIndex.allGroups(project) }

    private val htmlReportBox = JBCheckBox("HTML")
    private val junitReportBox = JBCheckBox("JUnit")

    private val coverageDriverField = ComboBox(arrayOf(TestoCoverageDriver.XDEBUG, TestoCoverageDriver.PCOV)).apply {
        renderer = SimpleListCellRenderer.create("") { driver ->
            when (driver) {
                TestoCoverageDriver.XDEBUG -> "Xdebug"
                TestoCoverageDriver.PCOV -> "PCOV"
                else -> driver?.name.orEmpty()
            }
        }
    }
    private val coverageLevelField = ComboBox(COVERAGE_LEVELS.toTypedArray())
    private val coverageCloverBox = JBCheckBox("Clover")
    private val coverageCoberturaBox = JBCheckBox("Cobertura")
    private val coverageXmlBox = JBCheckBox("coverage-xml")
    private val coverageOptionsField = JBTextField()

    private val infectionScopeField = ComboBox(TestoRunSelection.INFECTION_SCOPES.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { scope ->
            TestoBundle.message(when (scope) {
                TestoRunSelection.INFECTION_SCOPE_GIT_LINES -> "infection.scope.gitLines"
                TestoRunSelection.INFECTION_SCOPE_ALL -> "infection.scope.all"
                else -> "infection.scope.covered"
            })
        }
    }
    private val infectionGitDiffBaseField = JBTextField()
    private val infectionThreadsField = ComboBox(TestoRunSelection.INFECTION_THREADS.toTypedArray()).apply { isEditable = true }
    private val infectionOnlyCoveringBox = JBCheckBox("--only-covering-test-cases")
    private val infectionWithUncoveredBox = JBCheckBox("--with-uncovered")
    private val infectionTimeoutsBox = JBCheckBox("--with-timeouts")
    private val infectionMutatorsField = JBTextField()
    private val infectionStaticAnalysisField = ComboBox(TestoRunSelection.INFECTION_STATIC_ANALYSIS_TOOLS.toTypedArray()).apply { isEditable = true }
    private val infectionOptionsField = JBTextField()

    private val binaryField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor())
    }
    private val runnerOptionsField = RawCommandLineEditor()
    private val workingDirectoryField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private val environmentField = EnvironmentVariablesComponent()

    private val mainPanel = panel {
        group("Test Runner") {
            row("Test scope:") { cell(scopeField) }
            row("Suite:") { cell(typeField).align(AlignX.FILL) }
            row("Directory:") { cell(directoryField).align(AlignX.FILL) }
            row("File:") { cell(fileField).align(AlignX.FILL) }
            row("Method:") { cell(methodField).align(AlignX.FILL) }
                .rowComment("A --filter selector; after # the data provider")
            row { cell(useConfigurationFileBox) }
            row("Configuration file:") { cell(configurationFileField).align(AlignX.FILL) }
            row("Command:") { cell(commandField) }
            row("Test runner options:") { cell(runnerOptionsField).align(AlignX.FILL) }
        }
        group("Filter") {
            row("Type:") { cell(testoTypeField) }.rowComment("--type=<name>, e.g. test, inline, bench")
            row("Suite:") { cell(suiteField).align(AlignX.FILL) }
                .rowComment("One --suite=<name> per tag; type a name and press Enter")
            row("Group:") { cell(groupField).align(AlignX.FILL) }
                .rowComment("One --group=<name> per tag; names come from the #[Group] attributes of the project")
            row("Exclude group:") { cell(excludeGroupField).align(AlignX.FILL) }
                .rowComment("One --group=!<name> per tag: the CLI reads the ! prefix as an exclusion")
        }
        group("Reports") {
            row("Write:") {
                cell(htmlReportBox)
                cell(junitReportBox)
            }.rowComment("--log-html / --log-junit into an IDE-managed folder, kept in the run history")
        }
        group("Coverage") {
            row("Engine:") { cell(coverageDriverField) }
            row("Level:") { cell(coverageLevelField) }
                .rowComment("--coverage-level=<line|branch|path>; auto leaves it to testo.php, or collects branches when Xdebug and Cobertura or Clover are on")
            row("Reports:") {
                cell(coverageCloverBox)
                cell(coverageCoberturaBox)
                cell(coverageXmlBox)
            }
            row("Additional options:") { cell(coverageOptionsField).align(AlignX.FILL) }
                .rowComment("Arguments added to Coverage runs only. The default keeps benchmarks out of coverage")
        }
        group("Mutation Testing (Infection)") {
            row("Scope:") { cell(infectionScopeField) }
            row("Git diff base:") { cell(infectionGitDiffBaseField).align(AlignX.FILL) }
            row("Threads:") { cell(infectionThreadsField) }.rowComment("Empty leaves threads to infection.json5")
            row {
                cell(infectionOnlyCoveringBox)
                cell(infectionWithUncoveredBox)
                cell(infectionTimeoutsBox)
            }
            row("Mutators:") { cell(infectionMutatorsField).align(AlignX.FILL) }
            row("Static analysis tool:") { cell(infectionStaticAnalysisField) }
            row("Additional options:") { cell(infectionOptionsField).align(AlignX.FILL) }
                .rowComment("Appended last; these arguments take precedence over the fields above")
        }
        group("Launch") {
            row("Testo executable:") { cell(binaryField).align(AlignX.FILL) }
                .rowComment("Empty takes the one of Settings | PHP | Test Frameworks, then of composer")
            row("Working directory:") { cell(workingDirectoryField).align(AlignX.FILL) }
                .rowComment("Empty runs where testo.php is, or else in the project the executable belongs to")
            row { cell(environmentField).align(AlignX.FILL) }
            row { comment("Runs under the project's active PHP interpreter profile — Settings | PHP | CLI Interpreters") }
        }
    }

    override fun createEditor(): JComponent = mainPanel

    override fun resetEditorFrom(configuration: TestoRunConfiguration) {
        val selection = configuration.selection
        val options = configuration.options
        scopeField.selectedItem = selection.scope
        typeField.text = selection.selectedType.orEmpty()
        directoryField.text = selection.directoryPath.orEmpty()
        fileField.text = selection.filePath.orEmpty()
        methodField.text = selection.methodName.orEmpty()
        useConfigurationFileBox.isSelected = selection.useAlternativeConfigurationFile
        configurationFileField.text = selection.configurationFilePath.orEmpty()
        commandField.text = selection.command
        runnerOptionsField.text = selection.testRunnerOptions.orEmpty()
        testoTypeField.text = selection.testoType
        suiteField.names = selection.suites
        groupField.names = selection.groups
        excludeGroupField.names = selection.excludeGroups
        htmlReportBox.isSelected = selection.logHtml
        junitReportBox.isSelected = selection.logJunit
        coverageDriverField.selectedItem = selection.coverageDriver
        coverageLevelField.selectedItem = selection.coverageLevel
        coverageCloverBox.isSelected = selection.coverageClover
        coverageCoberturaBox.isSelected = selection.coverageCobertura
        coverageXmlBox.isSelected = selection.coverageXml
        coverageOptionsField.text = selection.coverageOptions
        infectionScopeField.selectedItem = selection.infectionScope
        infectionGitDiffBaseField.text = selection.infectionGitDiffBase
        infectionThreadsField.selectedItem = selection.infectionThreads
        infectionOnlyCoveringBox.isSelected = selection.infectionOnlyCoveringTestCases
        infectionWithUncoveredBox.isSelected = selection.infectionWithUncovered
        infectionTimeoutsBox.isSelected = selection.infectionTimeoutsAsEscaped
        infectionMutatorsField.text = selection.infectionMutators
        infectionStaticAnalysisField.selectedItem = selection.infectionStaticAnalysisTool
        infectionOptionsField.text = selection.infectionOptions
        binaryField.text = options.binaryPath.orEmpty()
        workingDirectoryField.text = options.workingDirectory.orEmpty()
        environmentField.envs = options.environmentVariables
        environmentField.isPassParentEnvs = options.passParentEnvironment
        updateScopeFields()
    }

    override fun applyEditorTo(configuration: TestoRunConfiguration) {
        configuration.selection = configuration.selection.apply { applyTo(this) }
        with(configuration.options) {
            binaryPath = binaryField.text.trim().ifEmpty { null }
            workingDirectory = workingDirectoryField.text.trim().ifEmpty { null }
            environmentVariables = environmentField.envs.toMutableMap()
            passParentEnvironment = environmentField.isPassParentEnvs
        }
    }

    private fun applyTo(selection: TestoRunSelection) {
        selection.scope = scopeField.selectedItem as? TestoScope ?: TestoScope.CONFIGURATION_FILE
        selection.selectedType = typeField.text.trim().ifEmpty { null }
        selection.directoryPath = directoryField.text.trim().ifEmpty { null }
        selection.filePath = fileField.text.trim().ifEmpty { null }
        selection.methodName = methodField.text.trim().ifEmpty { null }
        selection.useAlternativeConfigurationFile = useConfigurationFileBox.isSelected
        selection.configurationFilePath = configurationFileField.text.trim().ifEmpty { null }
        selection.command = commandField.text.trim().ifEmpty { TestoRunConfigurationOptions.DEFAULT_COMMAND }
        selection.testRunnerOptions = runnerOptionsField.text.trim().ifEmpty { null }
        selection.testoType = testoTypeField.text.trim()
        selection.suites = suiteField.names
        selection.groups = groupField.names
        selection.excludeGroups = excludeGroupField.names
        selection.logHtml = htmlReportBox.isSelected
        selection.logJunit = junitReportBox.isSelected
        selection.coverageDriver = coverageDriverField.selectedItem as? TestoCoverageDriver ?: TestoCoverageDriver.XDEBUG
        selection.coverageLevel = coverageLevelField.selectedItem as? String ?: TestoRunSelection.COVERAGE_LEVEL_AUTO
        selection.coverageClover = coverageCloverBox.isSelected
        selection.coverageCobertura = coverageCoberturaBox.isSelected
        selection.coverageXml = coverageXmlBox.isSelected
        selection.coverageOptions = coverageOptionsField.text
        selection.infectionScope = infectionScopeField.selectedItem as? String ?: TestoRunSelection.INFECTION_SCOPE_COVERED
        selection.infectionGitDiffBase = infectionGitDiffBaseField.text
        selection.infectionThreads = infectionThreadsField.editor.item?.toString()?.trim().orEmpty()
        selection.infectionOnlyCoveringTestCases = infectionOnlyCoveringBox.isSelected
        selection.infectionWithUncovered = infectionWithUncoveredBox.isSelected
        selection.infectionTimeoutsAsEscaped = infectionTimeoutsBox.isSelected
        selection.infectionMutators = infectionMutatorsField.text
        selection.infectionStaticAnalysisTool = infectionStaticAnalysisField.editor.item?.toString()?.trim().orEmpty()
        selection.infectionOptions = infectionOptionsField.text
    }

    private fun updateScopeFields() {
        val scope = scopeField.selectedItem as? TestoScope
        typeField.isEnabled = scope == TestoScope.TYPE
        directoryField.isEnabled = scope == TestoScope.DIRECTORY
        fileField.isEnabled = scope == TestoScope.FILE || scope == TestoScope.METHOD
        methodField.isEnabled = scope == TestoScope.METHOD
        configurationFileField.isEnabled = useConfigurationFileBox.isSelected
    }

    private companion object {
        val SCOPES = listOf(TestoScope.CONFIGURATION_FILE, TestoScope.DIRECTORY, TestoScope.FILE, TestoScope.METHOD, TestoScope.TYPE)

        val COVERAGE_LEVELS = listOf(TestoRunSelection.COVERAGE_LEVEL_AUTO, "line", "branch", "path")

        fun scopeName(scope: TestoScope?): String = when (scope) {
            TestoScope.CONFIGURATION_FILE -> "Configuration file"
            TestoScope.DIRECTORY -> "Directory"
            TestoScope.FILE -> "File"
            TestoScope.METHOD -> "Method"
            TestoScope.TYPE -> "Suite"
            null -> ""
        }
    }
}
