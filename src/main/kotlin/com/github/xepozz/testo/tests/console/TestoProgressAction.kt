package com.github.xepozz.testo.tests.console

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

class TestoProgressAction : AnAction(), CustomComponentAction {

    private val state = AtomicReference(ProgressState())

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return ProgressPanel(state)
    }

    fun attachTo(console: SMTRunnerConsoleView) {
        val viewer = console.resultsViewer
        viewer.addEventsListener(object : TestResultsViewer.EventsListener {
            override fun onTestingStarted(viewer: TestResultsViewer) {
                state.set(ProgressState())
                refreshComponent()
            }

            override fun onTestNodeAdded(viewer: TestResultsViewer, test: SMTestProxy) {}

            override fun onTestingFinished(viewer: TestResultsViewer) {
                recount(viewer)
            }

            override fun onSelected(
                selected: SMTestProxy?,
                viewer: TestResultsViewer,
                model: com.intellij.execution.testframework.TestFrameworkRunningModel,
            ) {
                recount(viewer)
            }
        })
    }

    private fun recount(viewer: TestResultsViewer) {
        val root = viewer.testsRootNode
        val leaves = root.allTests.filter { it !== root && !it.isSuite }
        var passed = 0; var failed = 0; var ignored = 0; var running = 0; var total = 0
        for (leaf in leaves) {
            total++
            when {
                leaf.isDefect -> failed++
                leaf.isIgnored -> ignored++
                leaf.isInProgress -> running++
                leaf.isPassed -> passed++
                else -> passed++
            }
        }
        state.set(ProgressState(passed, failed, ignored, running, total))
        refreshComponent()
    }

    private fun refreshComponent() {
        ApplicationManager.getApplication().invokeLater {
            templatePresentation.description = state.get().toString()
        }
    }

    fun installClickFilters(console: SMTRunnerConsoleView) {
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
            label.text = parts.joinToString("  ")
        }
    }
}
