package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.runs.TestoRunRecording
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.actions.testoRunProfile
import com.github.xepozz.testo.tests.console.TestoReportsRowCell
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.impl.RunDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Mutation testing in the reports row, right after Coverage: a button that runs Infection over this run's reports,
 * its dropdown of Infection options, and — once there is a mutation run — its progress, which opens the *Mutations*
 * tool window. The options are the run configuration's own, so the editor shows the same ones.
 */
internal class TestoMutationCell(private val properties: TestoConsoleProperties) : JComponent(), TestoReportsRowCell {
    override val component: JComponent get() = this

    private val project get() = properties.project
    private val spinner = Timer(SPIN_MS) { spin() }

    private var readinessKey: String? = null
    private var readiness: TestoMutationReadiness = TestoMutationReadiness.Missing.NOT_FINISHED
    private var context: Context? = null
    private var run: TestoMutationRun? = null
    private var hovered: Zone? = null

    private var progressLabel = ""
    private var fraction = 0.0
    private var indeterminate = true
    private var verdict: Icon? = null
    private var escaped = 0
    private var spinAngle = 0

    private enum class Zone { BUTTON, ARROW, PROGRESS }

    private class Context(val configuration: TestoRunConfiguration, val runDir: Path, val saved: RunnerAndConfigurationSettings?) {
        /** Where the options are read and written: the saved configuration, else the tab's own copy. */
        val options: TestoRunConfiguration get() = saved?.configuration as? TestoRunConfiguration ?: configuration
    }

    override fun getFont(): Font = UIUtil.getLabelFont()

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hovered = null
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                when (zoneAt(e.x)) {
                    Zone.ARROW -> showMenu()
                    Zone.PROGRESS -> run?.let { TestoMutationToolWindow.show(project, it) }
                    Zone.BUTTON -> onButton()
                    null -> Unit
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val zone = zoneAt(e.x)
                if (zone != hovered) {
                    hovered = zone
                    toolTipText = tooltip(zone)
                    repaint()
                }
            }
        })
    }

    override fun removeNotify() {
        spinner.stop()
        super.removeNotify()
    }

    override fun refresh(): Boolean {
        val current = resolveContext() ?: return false
        context = current
        readiness = readiness(current.runDir)
        val mutation = TestoMutationService.getInstance(project).runFor(current.runDir)
        run = mutation
        updateProgress(mutation)
        if (mutation?.isRunning == true) spinner.start() else spinner.stop()
        toolTipText = tooltip(hovered)
        repaint()
        return true
    }

    private fun resolveContext(): Context? {
        if (project.isDisposed) return null
        val environment = DataManager.getInstance().getDataContext(this).getData(ExecutionDataKeys.EXECUTION_ENVIRONMENT)
            ?: return context
        val configuration = environment.testoRunProfile() as? TestoRunConfiguration ?: return null
        val runDir = runCatching { properties.currentRunDir() }.getOrNull() ?: return null
        val saved = environment.runnerAndConfigurationSettings?.takeIf { it.configuration is TestoRunConfiguration }
            ?: RunManager.getInstance(project)
                .findConfigurationByTypeAndName(TestoRunConfigurationType.INSTANCE, configuration.name)
        return Context(configuration, runDir, saved)
    }

    // Re-read only when run.json changes: it appears once the archive completes.
    private fun readiness(runDir: Path): TestoMutationReadiness {
        val manifest = runDir.resolve(TestoRunRecording.MANIFEST_FILE)
        val key = "$runDir|${runCatching { Files.getLastModifiedTime(manifest).toMillis() }.getOrDefault(0)}"
        if (key == readinessKey) return readiness
        readinessKey = key
        return TestoInfectionReports.readiness(runDir, TestoRunStore.getInstance(project).readManifest(runDir))
    }

    private val isReady: Boolean get() = readiness is TestoMutationReadiness.Ready && run?.isRunning != true

    private fun onButton() {
        val current = context ?: return
        val mutation = run
        if (mutation?.isRunning == true) return TestoMutationToolWindow.show(project, mutation)
        val ready = readiness as? TestoMutationReadiness.Ready ?: return
        TestoMutationService.getInstance(project).start(current.configuration, current.runDir, ready, current.options)
        refresh()
    }

    private fun tooltip(zone: Zone?): String = when (zone) {
        Zone.ARROW -> TestoBundle.message("infection.cell.options")
        Zone.PROGRESS -> TestoBundle.message(
            "infection.widget.tooltip",
            (run?.finishedCount() ?: 0).toString(),
            maxOf(run?.expected ?: 0, run?.mutants?.size ?: 0).toString(),
            escaped.toString(),
            (run?.score()?.msi ?: 0).toString(),
        )
        else -> if (run?.isRunning == true) TestoBundle.message("infection.running") else when (readiness) {
            is TestoMutationReadiness.Ready -> TestoBundle.message("action.testo.mutate.description")
            TestoMutationReadiness.Missing.NOT_FINISHED -> TestoBundle.message("infection.missing.notFinished")
            TestoMutationReadiness.Missing.NOT_PASSED -> TestoBundle.message("infection.missing.notPassed")
            TestoMutationReadiness.Missing.NO_COVERAGE_XML -> TestoBundle.message("infection.missing.coverageXml")
            TestoMutationReadiness.Missing.NO_JUNIT -> TestoBundle.message("infection.missing.junit")
        }
    }

    private fun updateProgress(mutation: TestoMutationRun?) {
        if (mutation == null) {
            progressLabel = ""
            return
        }
        val score = mutation.score()
        val done = mutation.finishedCount()
        val total = maxOf(mutation.expected, mutation.mutants.size)
        val running = mutation.isRunning
        indeterminate = total <= 0
        fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
        escaped = score.escaped
        verdict = when {
            running -> null
            mutation.stopRequested -> TestoIcons.Status.FAILURE_CANCELLED
            escaped > 0 || (mutation.exitCode != 0 && mutation.mutants.isEmpty()) -> TestoIcons.Status.FAILURE
            else -> TestoIcons.Status.SUCCESS
        }
        progressLabel = when {
            running -> TestoBundle.message("infection.widget.running", done.toString(), total.toString())
            score.msi != null -> TestoBundle.message("infection.widget.msi", score.msi.toString())
            else -> TestoBundle.message("infection.widget.done")
        }
    }

    private fun spin() {
        spinAngle = (spinAngle + SPIN_STEP) % 360
        repaint()
    }

    // Layout: [icon Infection ▾] then, with a run, [ring label ✗ n].
    private fun buttonWidth(): Int = PADDING + MUTATE.iconWidth + GAP + textWidth(TEXT) + GAP + ARROW.iconWidth + PADDING
    private fun arrowStart(): Int = buttonWidth() - PADDING - ARROW.iconWidth - GAP

    private fun progressWidth(): Int {
        if (progressLabel.isEmpty()) return 0
        var width = PADDING + RING + GAP + textWidth(progressLabel) + PADDING
        if (escaped > 0) width += GAP + ESCAPED.iconWidth + GAP + textWidth(escaped.toString())
        return width
    }

    private fun textWidth(text: String) = getFontMetrics(font).stringWidth(text)

    private fun zoneAt(x: Int): Zone? = when {
        x < arrowStart() -> Zone.BUTTON
        x < buttonWidth() -> Zone.ARROW
        x < buttonWidth() + progressWidth() -> Zone.PROGRESS
        else -> null
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        return Dimension(buttonWidth() + progressWidth(), maxOf(metrics.height, RING, MUTATE.iconHeight) + JBUI.scale(4))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(6)
            val button = buttonWidth()
            hovered?.let { zone ->
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                when (zone) {
                    Zone.PROGRESS -> g2.fillRoundRect(button, 0, progressWidth(), height, arc, arc)
                    else -> g2.fillRoundRect(0, 0, button, height, arc, arc)
                }
            }
            g2.font = font
            val metrics = g2.fontMetrics
            val baseline = (height - metrics.height) / 2 + metrics.ascent

            var x = PADDING
            val icon = if (isReady || run?.isRunning == true) MUTATE else MUTATE_DISABLED
            icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
            x += icon.iconWidth + GAP
            g2.color = if (isReady || run != null) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
            g2.drawString(TEXT, x, baseline)
            ARROW.paintIcon(this, g2, button - PADDING - ARROW.iconWidth, (height - ARROW.iconHeight) / 2)

            if (progressLabel.isEmpty()) return
            x = button + PADDING
            val done = verdict
            if (done != null) done.paintIcon(this, g2, x, (height - done.iconHeight) / 2) else paintRing(g2, x)
            x += RING + GAP
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(progressLabel, x, baseline)
            x += metrics.stringWidth(progressLabel)
            if (escaped > 0) {
                x += GAP
                ESCAPED.paintIcon(this, g2, x, (height - ESCAPED.iconHeight) / 2)
                x += ESCAPED.iconWidth + GAP
                g2.drawString(escaped.toString(), x, baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintRing(g: Graphics2D, x: Int) {
        val stroke = JBUI.scale(2).toFloat()
        val size = (RING - stroke).toDouble()
        val left = x + stroke / 2.0
        val top = (height - RING) / 2.0 + stroke / 2.0
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = RING_TRACK
        g.draw(Arc2D.Double(left, top, size, size, 0.0, 360.0, Arc2D.OPEN))
        g.color = RING_PROGRESS
        val extent = if (indeterminate) -SPIN_ARC else -360.0 * fraction
        val start = if (indeterminate) 90.0 - spinAngle else 90.0
        g.draw(Arc2D.Double(left, top, size, size, start, extent, Arc2D.OPEN))
    }

    private fun showMenu() {
        val current = context ?: return
        val group = DefaultActionGroup(
            buildList {
                add(Separator.create(TestoBundle.message("infection.options.scope")))
                TestoRunnerSettings.INFECTION_SCOPES.forEach { scope ->
                    add(option(scopeLabel(scope), current, { it.infectionScope == scope }) { settings, _ -> settings.infectionScope = scope })
                }
                add(Separator.create(TestoBundle.message("infection.options.threads")))
                TestoRunnerSettings.INFECTION_THREADS.forEach { threads ->
                    add(option(threadsLabel(threads), current, { it.infectionThreads == threads }) { settings, _ -> settings.infectionThreads = threads })
                }
                add(Separator.create(TestoBundle.message("infection.options.flags")))
                add(option("--only-covering-test-cases", current, { it.infectionOnlyCoveringTestCases }) { settings, on -> settings.infectionOnlyCoveringTestCases = on })
                add(option("--with-uncovered", current, { it.infectionWithUncovered }) { settings, on -> settings.infectionWithUncovered = on })
                add(option("--with-timeouts", current, { it.infectionTimeoutsAsEscaped }) { settings, on -> settings.infectionTimeoutsAsEscaped = on })
                add(Separator.getInstance())
                add(object : DumbAwareAction(TestoBundle.message("infection.options.edit"), null, AllIcons.General.Settings) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = current.saved != null
                    }

                    override fun actionPerformed(e: AnActionEvent) {
                        current.saved?.let { RunDialog.editConfiguration(project, it, TestoBundle.message("infection.options.edit.title")) }
                    }
                })
            }
        )
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(this),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                ActionPlaces.TOOLBAR,
            )
            .showUnderneathOf(this)
    }

    private fun option(
        text: String,
        current: Context,
        isOn: (TestoRunnerSettings) -> Boolean,
        set: (TestoRunnerSettings, Boolean) -> Unit,
    ) = object : DumbAwareToggleAction(text) {
        init {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = isOn(current.options.testoSettings.runnerSettings)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            set(current.options.testoSettings.runnerSettings, state)
        }
    }

    private fun scopeLabel(scope: String) = when (scope) {
        TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES -> TestoBundle.message("infection.scope.gitLines")
        TestoRunnerSettings.INFECTION_SCOPE_ALL -> TestoBundle.message("infection.scope.all")
        else -> TestoBundle.message("infection.scope.covered")
    }

    companion object {
        private const val SPIN_MS = 100
        private const val SPIN_STEP = 12
        private const val SPIN_ARC = 90.0

        private val TEXT get() = TestoBundle.message("infection.cell.text")
        private val MUTATE: Icon = TestoIcons.MUTATE
        private val MUTATE_DISABLED: Icon = IconLoader.getDisabledIcon(TestoIcons.MUTATE)
        private val ESCAPED: Icon = TestoIcons.Status.FAILED
        private val ARROW: Icon = AllIcons.General.LinkDropTriangle

        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
        private val RING get() = JBUI.scale(16)

        private val RING_TRACK = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD5D5D5, 0x4E5157))
        private val RING_PROGRESS = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x389FD6, 0x3592C4))

        fun threadsLabel(threads: String): String = when (threads) {
            "" -> TestoBundle.message("infection.threads.config")
            "max" -> TestoBundle.message("infection.threads.max")
            else -> threads
        }
    }
}
