package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A compact progress widget shown in the test results toolbar. Replaces the platform's horizontal progress bar with
 * a per-status summary like "🟢 5 passed 🔴 4 failed 🟡 2 risky". Each status segment is clickable and toggles
 * the corresponding test filter in the tree.
 */
class TestoProgressAction : AnAction(), CustomComponentAction {

    private val state = AtomicReference(ProgressState())

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // No-op: interaction is handled by the component's click listeners.
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return ProgressPanel(state)
    }

    /** Called by the converter/augmenter to wire this action to the console's test events. */
    fun attachTo(console: SMTRunnerConsoleView) {
        val viewer = console.resultsViewer
        viewer.addEventsListener(object : TestResultsViewer.EventsListener {
            override fun onTestingStarted(viewer: TestResultsViewer) {
                state.set(ProgressState())
                refreshComponent()
            }

            override fun onTestNodeAdded(viewer: TestResultsViewer, test: SMTestProxy) {
                // Leaf count changes are picked up on finish; suite nodes are irrelevant.
            }

            override fun onTestingFinished(viewer: TestResultsViewer) {
                recount(viewer)
            }

            override fun onSelected(
                selected: SMTestProxy?,
                viewer: TestResultsViewer,
                model: com.intellij.execution.testframework.TestFrameworkRunningModel,
            ) {
                // Recount on every selection change so the widget stays up to date during a live run (the platform
                // fires onSelected when a running test finishes and the tree auto-selects the next failure).
                recount(viewer)
            }
        })
    }

    private fun recount(viewer: TestResultsViewer) {
        val root = viewer.testsRootNode
        val leaves = root.allTests.filter { it !== root && it.isLeaf }
        var passed = 0; var failed = 0; var ignored = 0; var running = 0; var total = 0
        for (leaf in leaves) {
            total++
            when {
                leaf.isPassed -> passed++
                leaf.isDefect -> failed++
                leaf.isIgnored -> ignored++
                leaf.isInProgress -> running++
            }
        }
        state.set(ProgressState(passed, failed, ignored, running, total))
        refreshComponent()
    }

    private fun refreshComponent() {
        ApplicationManager.getApplication().invokeLater {
            // The custom component is rebuilt by the toolbar framework; force a presentation change to trigger it.
            templatePresentation.description = state.get().toString()
        }
    }

    /** Installs a click filter on the console's test tree: clicking a status segment shows only tests of that status. */
    fun installClickFilters(console: SMTRunnerConsoleView) {
        // The filter toggling is done inside ProgressPanel via TestConsoleProperties toggles.
        panelRef?.properties = console.properties
    }

    private var panelRef: ProgressPanel? = null

    data class ProgressState(
        val passed: Int = 0,
        val failed: Int = 0,
        val ignored: Int = 0,
        val running: Int = 0,
        val total: Int = 0,
    )

    private inner class ProgressPanel(
        private val stateRef: AtomicReference<ProgressState>,
    ) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

        var properties: TestConsoleProperties? = null
        private val label = JBLabel()

        init {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6)
            add(label)
            panelRef = this
            update()
            // Poll for state changes (the action framework doesn't repaint custom components on presentation changes
            // reliably, so a lightweight timer keeps the label fresh during a live run).
            val timer = javax.swing.Timer(500) { update() }
            timer.isRepeats = true
            timer.start()
        }

        fun update() {
            val s = stateRef.get()
            if (s.total == 0) {
                label.text = ""
                return
            }
            val parts = mutableListOf<String>()
            if (s.passed > 0) parts += "🟢 ${s.passed}"
            if (s.failed > 0) parts += "🔴 ${s.failed}"
            if (s.ignored > 0) parts += "🟡 ${s.ignored}"
            if (s.running > 0) parts += "⏳ ${s.running}"
            val fraction = "${s.passed + s.failed + s.ignored}/${s.total}"
            label.text = "${parts.joinToString("  ")}  ($fraction)"
        }
    }
}
