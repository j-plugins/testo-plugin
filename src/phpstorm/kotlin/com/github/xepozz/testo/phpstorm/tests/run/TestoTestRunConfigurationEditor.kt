package com.github.xepozz.testo.phpstorm.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.index.TestoGroupsIndex
import com.github.xepozz.testo.tests.run.TestoTagsField
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridLayoutManager
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationEditor
import java.awt.Component
import java.awt.Container
import java.awt.Insets
import java.lang.reflect.InvocationTargetException
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class TestoTestRunConfigurationEditor(
    private val parentEditor: PhpTestRunConfigurationEditor,
    val configuration: TestoRunConfiguration
) : SettingsEditor<TestoRunConfiguration>() {
    private val suiteField = TestoTagsField(
        TestoBundle.message("testo.tags.suites.empty"),
        TestoBundle.message("testo.tags.suites.add"),
    )
    private val groupField = TestoTagsField(
        TestoBundle.message("testo.tags.groups.empty"),
        TestoBundle.message("testo.tags.groups.add"),
    ) { TestoGroupsIndex.allGroups(configuration.project) }
    private val excludeGroupField = TestoTagsField(
        TestoBundle.message("testo.tags.groups.empty"),
        TestoBundle.message("testo.tags.groups.exclude.add"),
    ) { TestoGroupsIndex.allGroups(configuration.project) }
    // Held disabled until Testo grows the flag; 1 is "no --parallel at all", so a parked field changes no run.
    private val parallelField = JSpinner(SpinnerNumberModel(1, 0, 64, 1)).apply {
        isEnabled = false
        toolTipText = "Not supported by Testo yet"
    }
    private val coverageEngineField = ComboBox(SUPPORTED_COVERAGE_ENGINES.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { engine ->
            when (engine) {
                CoverageEngine.XDEBUG -> "Xdebug"
                CoverageEngine.PCOV -> "PCOV"
                else -> engine?.name ?: ""
            }
        }
    }
    private val htmlReportBox = JBCheckBox("HTML")
    private val junitReportBox = JBCheckBox("JUnit")
    private val coverageCloverBox = JBCheckBox("Clover")
    private val coverageCoberturaBox = JBCheckBox("Cobertura")
    private val coverageXmlBox = JBCheckBox("coverage-xml")
    private val coverageLevelField = ComboBox(TestoRunnerSettings.COVERAGE_LEVELS.toTypedArray())
    private val coverageOptionsField = JBTextField()
    private val infectionScopeField = ComboBox(TestoRunnerSettings.INFECTION_SCOPES.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { scope ->
            when (scope) {
                TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES -> TestoBundle.message("infection.scope.gitLines")
                TestoRunnerSettings.INFECTION_SCOPE_ALL -> TestoBundle.message("infection.scope.all")
                else -> TestoBundle.message("infection.scope.covered")
            }
        }
    }
    private val infectionGitDiffBaseField = JBTextField()
    private val infectionThreadsField = ComboBox(TestoRunnerSettings.INFECTION_THREADS.toTypedArray()).apply {
        isEditable = true
    }
    private val infectionOnlyCoveringBox = JBCheckBox("--only-covering-test-cases")
    private val infectionWithUncoveredBox = JBCheckBox("--with-uncovered")
    private val infectionTimeoutsBox = JBCheckBox("--with-timeouts")
    private val infectionMutatorsField = JBTextField()
    private val infectionStaticAnalysisField = ComboBox(TestoRunnerSettings.INFECTION_STATIC_ANALYSIS_TOOLS.toTypedArray()).apply {
        isEditable = true
    }
    private val infectionOptionsField = JBTextField()

    private val parallelInjected = injectParallelRow()

    private val myMainPanel = panel {
        row {
            cell(parentEditor.component)
                .align(AlignX.FILL)
        }.layout(RowLayout.LABEL_ALIGNED)

        if (!parallelInjected) {
            row {
                label(PARALLEL_LABEL)
                    .gap(RightGap.COLUMNS)
                cell(parallelField)
            }
                .layout(RowLayout.PARENT_GRID)
        }

        group("Filter") {
            row {
                label("Suite")
                    .gap(RightGap.COLUMNS)
                cell(suiteField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("One --suite=<name> per tag; type a name and press Enter")

            row {
                label("Group")
                    .gap(RightGap.COLUMNS)
                cell(groupField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("One --group=<name> per tag; names come from the #[Group] attributes of the project")

            row {
                label("Exclude group")
                    .gap(RightGap.COLUMNS)
                cell(excludeGroupField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("One --group=!<name> per tag: the CLI reads the ! prefix as an exclusion")
        }

        group("Reports") {
            row {
                label("Write")
                    .gap(RightGap.COLUMNS)
                cell(htmlReportBox)
                cell(junitReportBox)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--log-html / --log-junit into an IDE-managed folder, kept in the run history. HTML opens in a tab; JUnit is for external tooling")
        }

        group("Coverage") {
            row {
                label("Preferred engine")
                    .gap(RightGap.COLUMNS)
                cell(coverageEngineField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Engine used to collect code coverage")

            row {
                label("Level")
                    .gap(RightGap.COLUMNS)
                cell(coverageLevelField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--coverage-level=<line|branch|path>; auto leaves it to testo.php, or collects branches when Xdebug and Cobertura or Clover are on. Branch and path need Xdebug")

            row {
                label("Reports")
                    .gap(RightGap.COLUMNS)
                cell(coverageCloverBox)
                cell(coverageCoberturaBox)
                cell(coverageXmlBox)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Reports a Coverage run requests: --coverage-clover / --coverage-cobertura / --coverage-xml; all are applied together")

            row {
                label("Additional options")
                    .gap(RightGap.COLUMNS)
                cell(coverageOptionsField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Arguments added to Coverage runs only, e.g. --coverage-level=branch. The default keeps benchmarks out of coverage")
        }

        group("Mutation Testing (Infection)") {
            row {
                label("Mutate")
                    .gap(RightGap.COLUMNS)
                cell(infectionScopeField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--filter with the files the run covers, --git-diff-lines, or no filter at all; Infection takes only one of them")

            row {
                label("Git base")
                    .gap(RightGap.COLUMNS)
                cell(infectionGitDiffBaseField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--git-diff-base for \"Lines changed in git\", e.g. origin/main; empty is Infection's default")

            row {
                label("Threads")
                    .gap(RightGap.COLUMNS)
                cell(infectionThreadsField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--threads=<n|max>; empty leaves it to infection.json5")

            row {
                label("Flags")
                    .gap(RightGap.COLUMNS)
                cell(infectionOnlyCoveringBox)
                cell(infectionWithUncoveredBox)
                cell(infectionTimeoutsBox)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Run only the tests covering the mutated line; mutate uncovered code too; count timeouts as escaped")

            row {
                label("Mutators")
                    .gap(RightGap.COLUMNS)
                cell(infectionMutatorsField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--mutators, e.g. @default,-MethodCallRemoval; empty leaves it to infection.json5")

            row {
                label("Static analysis")
                    .gap(RightGap.COLUMNS)
                cell(infectionStaticAnalysisField)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("--static-analysis-tool: a mutant the tool rejects counts as killed; empty leaves it off")

            row {
                label("Additional options")
                    .gap(RightGap.COLUMNS)
                cell(infectionOptionsField)
                    .align(AlignX.FILL)
            }
                .layout(RowLayout.PARENT_GRID)
                .rowComment("Appended to the Infection command line last, so they win over the fields above")
        }
    }

    init {
        val listener = { fireEditorStateChanged() }
        val documentAdapter = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = listener()
        }

        suiteField.addChangeListener(listener)
        groupField.addChangeListener(listener)
        excludeGroupField.addChangeListener(listener)
        coverageOptionsField.document.addDocumentListener(documentAdapter)
        infectionGitDiffBaseField.document.addDocumentListener(documentAdapter)
        infectionMutatorsField.document.addDocumentListener(documentAdapter)
        infectionStaticAnalysisField.addActionListener { listener() }
        infectionOptionsField.document.addDocumentListener(documentAdapter)
        infectionScopeField.addActionListener { listener() }
        infectionThreadsField.addActionListener { listener() }
        infectionOnlyCoveringBox.addActionListener { listener() }
        infectionWithUncoveredBox.addActionListener { listener() }
        infectionTimeoutsBox.addActionListener { listener() }
        parallelField.addChangeListener { listener() }
        htmlReportBox.addActionListener { listener() }
        junitReportBox.addActionListener { listener() }
        coverageEngineField.addActionListener { listener() }
        coverageCloverBox.addActionListener { listener() }
        coverageCoberturaBox.addActionListener { listener() }
        coverageXmlBox.addActionListener { listener() }
        coverageLevelField.addActionListener { listener() }
    }

    override fun createEditor(): JComponent = myMainPanel

    override fun isSpecificallyModified(): Boolean {
        val runner = configuration.testoSettings.runnerSettings
        return suiteField.names != runner.suites
                || groupField.names != runner.groups
                || excludeGroupField.names != runner.excludeGroups
                || (parallelField.value as Int) != runner.parallel
                || htmlReportBox.isSelected != runner.logHtml
                || junitReportBox.isSelected != runner.logJunit
                || coverageEngineField.selectedItem != runner.coverageEngine
                || coverageCloverBox.isSelected != runner.coverageClover
                || coverageCoberturaBox.isSelected != runner.coverageCobertura
                || coverageXmlBox.isSelected != runner.coverageXml
                || coverageLevelField.selectedItem != runner.coverageLevel
                || coverageOptionsField.text != runner.coverageOptions
                || infectionScopeField.selectedItem != runner.infectionScope
                || infectionGitDiffBaseField.text != runner.infectionGitDiffBase
                || infectionThreads() != runner.infectionThreads
                || infectionOnlyCoveringBox.isSelected != runner.infectionOnlyCoveringTestCases
                || infectionWithUncoveredBox.isSelected != runner.infectionWithUncovered
                || infectionTimeoutsBox.isSelected != runner.infectionTimeoutsAsEscaped
                || infectionMutatorsField.text != runner.infectionMutators
                || infectionStaticAnalysis() != runner.infectionStaticAnalysisTool
                || infectionOptionsField.text != runner.infectionOptions
                || parentEditor.isSpecificallyModified
    }

    override fun resetEditorFrom(testoRunConfiguration: TestoRunConfiguration) {
        val runnerSettings = testoRunConfiguration.testoSettings.runnerSettings
        suiteField.names = runnerSettings.suites
        groupField.names = runnerSettings.groups
        excludeGroupField.names = runnerSettings.excludeGroups
        parallelField.value = runnerSettings.parallel
        htmlReportBox.isSelected = runnerSettings.logHtml
        junitReportBox.isSelected = runnerSettings.logJunit
        coverageEngineField.selectedItem = runnerSettings.coverageEngine
        coverageCloverBox.isSelected = runnerSettings.coverageClover
        coverageCoberturaBox.isSelected = runnerSettings.coverageCobertura
        coverageXmlBox.isSelected = runnerSettings.coverageXml
        coverageLevelField.selectedItem = runnerSettings.coverageLevel
        coverageOptionsField.text = runnerSettings.coverageOptions
        infectionScopeField.selectedItem = runnerSettings.infectionScope
        infectionGitDiffBaseField.text = runnerSettings.infectionGitDiffBase
        infectionThreadsField.selectedItem = runnerSettings.infectionThreads
        infectionOnlyCoveringBox.isSelected = runnerSettings.infectionOnlyCoveringTestCases
        infectionWithUncoveredBox.isSelected = runnerSettings.infectionWithUncovered
        infectionTimeoutsBox.isSelected = runnerSettings.infectionTimeoutsAsEscaped
        infectionMutatorsField.text = runnerSettings.infectionMutators
        infectionStaticAnalysisField.selectedItem = runnerSettings.infectionStaticAnalysisTool
        infectionOptionsField.text = runnerSettings.infectionOptions

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
        runnerSettings.suites = suiteField.names.toMutableList()
        runnerSettings.groups = groupField.names.toMutableList()
        runnerSettings.excludeGroups = excludeGroupField.names.toMutableList()
        runnerSettings.parallel = parallelField.value as? Int ?: 1
        runnerSettings.logHtml = htmlReportBox.isSelected
        runnerSettings.logJunit = junitReportBox.isSelected
        runnerSettings.coverageEngine = coverageEngineField.selectedItem as? CoverageEngine ?: CoverageEngine.XDEBUG
        runnerSettings.coverageClover = coverageCloverBox.isSelected
        runnerSettings.coverageCobertura = coverageCoberturaBox.isSelected
        runnerSettings.coverageXml = coverageXmlBox.isSelected
        runnerSettings.coverageLevel = coverageLevelField.selectedItem as? String
            ?: TestoRunnerSettings.COVERAGE_LEVEL_AUTO
        runnerSettings.coverageOptions = coverageOptionsField.text
        runnerSettings.infectionScope = infectionScopeField.selectedItem as? String ?: TestoRunnerSettings.INFECTION_SCOPE_COVERED
        runnerSettings.infectionGitDiffBase = infectionGitDiffBaseField.text
        runnerSettings.infectionThreads = infectionThreads()
        runnerSettings.infectionOnlyCoveringTestCases = infectionOnlyCoveringBox.isSelected
        runnerSettings.infectionWithUncovered = infectionWithUncoveredBox.isSelected
        runnerSettings.infectionTimeoutsAsEscaped = infectionTimeoutsBox.isSelected
        runnerSettings.infectionMutators = infectionMutatorsField.text
        runnerSettings.infectionStaticAnalysisTool = infectionStaticAnalysis()
        runnerSettings.infectionOptions = infectionOptionsField.text
    }

    // Editable: any count Infection takes, not only the offered ones.
    private fun infectionThreads(): String = (infectionThreadsField.editor.item ?: infectionThreadsField.selectedItem)?.toString()?.trim().orEmpty()

    private fun infectionStaticAnalysis(): String =
        (infectionStaticAnalysisField.editor.item ?: infectionStaticAnalysisField.selectedItem)?.toString()?.trim().orEmpty()

    /**
     * Puts Parallel into the PHP editor's own *Test Runner options* row, where the rest of the runner's flags are.
     * That row is a one-row `GridLayoutManager` form with no seam to extend — the editor exposes the whole panel and
     * nothing smaller — so it is found by its label and rebuilt with a second row; re-adding the children with the
     * constraints they already had keeps the label column shared. Anything unexpected falls back to a row of our own.
     */
    private fun injectParallelRow(): Boolean = runCatching {
        val optionsLabel = findRunnerOptionsLabel(parentEditor.component) ?: return false
        val row = optionsLabel.parent as? JPanel ?: return false
        val layout = row.layout as? GridLayoutManager ?: return false
        if (layout.rowCount != 1) return false

        val existing = row.components.map { it to layout.getConstraintsForComponent(it) }
        row.removeAll()
        row.layout = GridLayoutManager(2, layout.columnCount, Insets(0, 0, 0, 0), -1, -1)
        existing.forEach { (component, constraints) -> row.add(component, constraints) }
        row.add(JBLabel(PARALLEL_LABEL), labelConstraints(1, 0))
        row.add(parallelField, labelConstraints(1, 1))
        true
    }.getOrDefault(false)

    // The bundle string carries the mnemonic marker (`Test Runner &options:`), which the form strips into a
    // displayedMnemonic — so the rendered label never equals the raw message.
    private fun findRunnerOptionsLabel(component: Component): JLabel? {
        val text = UIUtil.removeMnemonic(PhpBundle.message("php.test.framework.field.test.runner.options"))
        return when {
            component is JLabel && component.text == text -> component
            component is Container -> component.components.firstNotNullOfOrNull { findRunnerOptionsLabel(it) }
            else -> null
        }
    }

    private fun labelConstraints(row: Int, column: Int) = GridConstraints(
        row, column, 1, 1,
        GridConstraints.ANCHOR_WEST,
        GridConstraints.FILL_NONE,
        GridConstraints.SIZEPOLICY_FIXED,
        GridConstraints.SIZEPOLICY_FIXED,
        null, null, null,
    )

    companion object {
        val SUPPORTED_COVERAGE_ENGINES: List<CoverageEngine> = listOf(CoverageEngine.XDEBUG, CoverageEngine.PCOV)

        private const val PARALLEL_LABEL = "Parallel (0 = auto)"
    }
}
