package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.runs.TestoRunRecording
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.actions.testoRunProfile
import com.github.xepozz.testo.tests.console.TestoProgressAction
import com.github.xepozz.testo.tests.console.TestoReportsRowCell
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.impl.RunDialog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ExecutionDataKeys
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import java.awt.geom.Arc2D
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Mutation testing in the reports row, right after Coverage: a *Mutation* label, a button that runs Infection over this
 * run's reports, its dropdown of Infection options, the history of this run's mutation runs, and the latest one's
 * progress, which opens the *Mutations* tool window. The options are the run configuration's own, so the editor shows
 * the same ones.
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
    private var elapsed = ""
    private var fraction = 0.0
    private var indeterminate = true
    private var verdict: Icon? = null
    private var escaped = 0
    private var spinAngle = 0
    private var hasReport = false

    private enum class Zone { LABEL, BUTTON, ARROW, HISTORY, PROGRESS, REPORT, REPORT_ARROW }

    private class Context(val configuration: TestoRunConfiguration, val runDir: Path, val saved: RunnerAndConfigurationSettings?) {
        /** Where the options are read and written: the saved configuration, else the tab's own copy. */
        val options: TestoRunConfiguration get() = saved?.configuration as? TestoRunConfiguration ?: configuration
    }

    override fun getFont(): Font = UIUtil.getLabelFont()

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hover(null)
            }

            override fun mouseClicked(e: MouseEvent) {
                when (zoneAt(e.x)) {
                    Zone.BUTTON -> onButton()
                    Zone.ARROW -> showOptions()
                    Zone.HISTORY -> if (hasHistory) showHistory()
                    Zone.PROGRESS -> run?.let { TestoMutationToolWindow.show(project, it) }
                    Zone.REPORT -> if (hasReport) run?.let { TestoMutationToolWindow.openReport(project, it) }
                    Zone.REPORT_ARROW -> if (hasReport) showReportMenu()
                    Zone.LABEL, null -> Unit
                }
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                hover(zoneAt(e.x))
            }
        })
    }

    private fun hover(zone: Zone?) {
        if (zone == hovered) return
        hovered = zone
        toolTipText = tooltip(zone)
        cursor = Cursor.getPredefinedCursor(if (isActive(zone)) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        repaint()
    }

    // The latest mutation run is what the history lists first: none yet, nothing to list.
    private val hasHistory: Boolean get() = run != null

    private fun isActive(zone: Zone?): Boolean = when (zone) {
        null, Zone.LABEL -> false
        Zone.HISTORY -> hasHistory
        Zone.REPORT, Zone.REPORT_ARROW -> hasReport
        else -> true
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
        // A run read back from the archive has no recipe of its own; this tab knows how to run its Testo run's reports again.
        if (mutation != null && mutation.recipe == null) mutation.recipe = recipeOf(current)
        updateProgress(mutation)
        hasReport = mutation != null && !mutation.isRunning && Files.isRegularFile(mutation.htmlReport)
        if (mutation?.isBusy == true) spinner.start() else spinner.stop()
        toolTipText = tooltip(hovered)
        repaint()
        return true
    }

    private fun recipeOf(current: Context): TestoMutationRecipe? = (readiness as? TestoMutationReadiness.Ready)?.let { ready ->
        TestoMutationRecipe(current.configuration, current.runDir, ready, current.options)
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

    private val isReady: Boolean get() = readiness is TestoMutationReadiness.Ready && run?.isBusy != true

    private fun onButton() {
        val current = context ?: return
        val mutation = run
        if (mutation?.isBusy == true) return TestoMutationToolWindow.show(project, mutation)
        TestoMutationService.getInstance(project).start(recipeOf(current) ?: return)
        refresh()
    }

    private fun tooltip(zone: Zone?): String? = when (zone) {
        null, Zone.LABEL -> null
        Zone.ARROW -> TestoBundle.message("infection.cell.options")
        Zone.REPORT -> TestoBundle.message(if (hasReport) "infection.cell.report" else "infection.cell.report.none")
        Zone.REPORT_ARROW -> TestoBundle.message(if (hasReport) "infection.cell.report.more" else "infection.cell.report.none")
        Zone.HISTORY -> TestoBundle.message(if (hasHistory) "infection.cell.history" else "infection.history.empty")
        Zone.PROGRESS -> TestoBundle.message(
            "infection.widget.tooltip",
            (run?.finishedCount() ?: 0).toString(),
            maxOf(run?.expected ?: 0, run?.mutants?.size ?: 0).toString(),
            escaped.toString(),
            (run?.score()?.msi ?: 0).toString(),
        )
        Zone.BUTTON -> {
            val current = readiness
            when {
                run?.isBusy == true -> TestoBundle.message("infection.running")
                current is TestoMutationReadiness.Ready -> TestoBundle.message("action.testo.mutate.description")
                current == TestoMutationReadiness.Missing.NOT_FINISHED && properties.afterArchive != null ->
                    TestoBundle.message("infection.missing.pending")
                else -> (current as TestoMutationReadiness.Missing).hint
            }
        }
    }

    private fun updateProgress(mutation: TestoMutationRun?) {
        if (mutation == null) {
            progressLabel = ""
            elapsed = ""
            return
        }
        val score = mutation.score()
        val done = mutation.finishedCount()
        val total = maxOf(mutation.expected, mutation.mutants.size)
        val running = mutation.isBusy
        indeterminate = total <= 0
        fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
        escaped = score.escaped
        verdict = mutationVerdict(running, mutation.stopRequested, escaped, mutation.exitCode, mutation.mutants.size)
        progressLabel = when {
            running -> TestoBundle.message("infection.widget.running", done.toString(), total.toString())
            score.msi != null -> TestoBundle.message("infection.widget.msi", score.msi.toString())
            else -> TestoBundle.message("infection.widget.done")
        }
        elapsed = TestoProgressAction.formatElapsed(mutation.elapsedMs())
    }

    private fun spin() {
        spinAngle = (spinAngle + SPIN_STEP) % 360
        repaint()
    }

    // Layout: Mutation [logo][▾] [history ▾] then, with a run, [ring label ✗ n  elapsed] [report][▾].
    private fun segments(): List<Pair<Zone, Int>> = buildList {
        add(Zone.LABEL to PADDING + textWidth(LABEL) + GAP)
        add(Zone.BUTTON to PADDING + LOGO.iconWidth + GAP)
        add(Zone.ARROW to ARROW.iconWidth + PADDING)
        add(Zone.HISTORY to PADDING + HISTORY.iconWidth + ARROW.iconWidth + PADDING)
        if (progressLabel.isNotEmpty()) {
            add(Zone.PROGRESS to progressWidth())
            add(Zone.REPORT to PADDING + REPORT.iconWidth + GAP)
            add(Zone.REPORT_ARROW to ARROW.iconWidth + PADDING)
        }
    }

    private fun progressWidth(): Int {
        var width = PADDING + RING + GAP + textWidth(progressLabel)
        if (escaped > 0) width += GAP + ESCAPED.iconWidth + GAP + textWidth(escaped.toString())
        if (elapsed.isNotEmpty()) width += 2 * GAP + textWidth(elapsed)
        return width + PADDING
    }

    private fun textWidth(text: String) = getFontMetrics(font).stringWidth(text)

    private fun start(zone: Zone): Int {
        var x = 0
        for ((segment, width) in segments()) {
            if (segment == zone) return x
            x += width
        }
        return x
    }

    private fun zoneAt(position: Int): Zone? {
        var x = position - LEAD
        if (x < 0) return null
        for ((zone, width) in segments()) {
            if (x < width) return zone
            x -= width
        }
        return null
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        return Dimension(LEAD + segments().sumOf { it.second }, maxOf(metrics.height, RING, LOGO.iconHeight) + JBUI.scale(4))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            // Fences mutation testing off from Coverage on its left, the way the row itself is fenced off.
            g2.color = JBColor.border()
            val inset = JBUI.scale(4)
            g2.fillRect(JBUI.scale(4), inset, JBUI.scale(1), height - 2 * inset)
            g2.translate(LEAD, 0)

            val arc = JBUI.scale(6)
            val widths = segments().toMap()
            hovered?.takeIf(::isActive)?.let { zone ->
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fillRoundRect(start(zone), 0, widths.getValue(zone), height, arc, arc)
            }
            g2.font = font
            val metrics = g2.fontMetrics
            val baseline = (height - metrics.height) / 2 + metrics.ascent

            // Whether this run can be mutated at all; a mutation run going on does not change that.
            g2.color = if (readiness is TestoMutationReadiness.Ready) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
            g2.drawString(LABEL, PADDING, baseline)

            var x = start(Zone.BUTTON) + PADDING
            val logo = if (isReady || run?.isBusy == true) LOGO else LOGO_DISABLED
            paintIcon(g2, logo, x)
            paintIcon(g2, ARROW, start(Zone.ARROW))

            x = start(Zone.HISTORY) + PADDING
            paintIcon(g2, if (hasHistory) HISTORY else HISTORY_DISABLED, x)
            paintIcon(g2, if (hasHistory) ARROW else ARROW_DISABLED, x + HISTORY.iconWidth)

            if (progressLabel.isEmpty()) return
            x = start(Zone.PROGRESS) + PADDING
            val done = verdict
            if (done != null) paintIcon(g2, done, x) else paintRing(g2, x)
            x += RING + GAP
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(progressLabel, x, baseline)
            x += metrics.stringWidth(progressLabel)
            if (escaped > 0) {
                x += GAP
                paintIcon(g2, ESCAPED, x)
                x += ESCAPED.iconWidth + GAP
                g2.drawString(escaped.toString(), x, baseline)
                x += metrics.stringWidth(escaped.toString())
            }
            if (elapsed.isNotEmpty()) {
                x += 2 * GAP
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(elapsed, x, baseline)
            }

            paintIcon(g2, if (hasReport) REPORT else REPORT_DISABLED, start(Zone.REPORT) + PADDING)
            paintIcon(g2, if (hasReport) ARROW else ARROW_DISABLED, start(Zone.REPORT_ARROW))
        } finally {
            g2.dispose()
        }
    }

    private fun paintIcon(g: Graphics2D, icon: Icon, x: Int) = icon.paintIcon(this, g, x, (height - icon.iconHeight) / 2)

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

    private fun showPopup(title: String?, group: ActionGroup, under: Zone) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                title,
                group,
                DataManager.getInstance().getDataContext(this),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                ActionPlaces.TOOLBAR,
            )
            .show(RelativePoint(this, Point(LEAD + start(under), height)))
    }

    private fun showReportMenu() {
        val mutation = run ?: return
        val report = mutation.htmlReport
        val group = DefaultActionGroup(
            object : DumbAwareAction(TestoBundle.message("testo.report.open.webview"), null, AllIcons.Actions.Preview) {
                override fun actionPerformed(e: AnActionEvent) = TestoMutationToolWindow.openReport(project, mutation)
            },
            object : DumbAwareAction(TestoBundle.message("testo.report.open.browser"), null, AllIcons.Nodes.PpWeb) {
                override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(report.toUri())
            },
            object : DumbAwareAction(TestoBundle.message("testo.report.copy.path"), null, AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(report.toString()))
            },
        )
        showPopup(null, group, Zone.REPORT)
    }

    private fun showHistory() {
        val current = context ?: return
        val service = TestoMutationService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = service.history(current.runDir)
            ApplicationManager.getApplication().invokeLater({
                if (!isShowing) return@invokeLater
                val recipe = recipeOf(current)
                val group = DefaultActionGroup(entries.map { entry ->
                    object : DumbAwareAction(historyText(entry), null, entry.verdict ?: AllIcons.Process.Step_1) {
                        override fun actionPerformed(e: AnActionEvent) = service.open(current.runDir, entry.dir, recipe)
                    }
                })
                if (entries.isEmpty()) group.add(object : DumbAwareAction(TestoBundle.message("infection.history.empty")) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = false
                    }

                    override fun actionPerformed(e: AnActionEvent) = Unit
                })
                showPopup(TestoBundle.message("infection.history.title"), group, Zone.HISTORY)
            }, project.disposed)
        }
    }

    private fun historyText(entry: TestoMutationHistoryEntry): String = buildList {
        add(DateFormatUtil.formatPrettyDateTime(entry.startedAt))
        when {
            entry.running -> add(TestoBundle.message("infection.widget.running", entry.finished.toString(), entry.mutants.toString()))
            entry.msi != null -> add(TestoBundle.message("infection.widget.msi", entry.msi.toString()))
        }
        if (entry.escaped > 0) add(TestoBundle.message("infection.node.escaped", entry.escaped.toString()))
        add(TestoProgressAction.formatElapsed(entry.elapsedMs))
    }.joinToString("  ·  ")

    private fun showOptions() {
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
        showPopup(null, group, Zone.BUTTON)
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

        private val LABEL get() = TestoBundle.message("infection.cell.label")
        private val LOGO: Icon = TestoIcons.MUTATION_RUN
        private val LOGO_DISABLED: Icon = IconLoader.getDisabledIcon(TestoIcons.MUTATION_RUN)
        private val HISTORY: Icon = AllIcons.Vcs.History
        private val HISTORY_DISABLED: Icon = IconLoader.getDisabledIcon(HISTORY)
        private val ESCAPED: Icon = TestoIcons.Status.FAILED
        private val ARROW: Icon = AllIcons.General.LinkDropTriangle
        private val ARROW_DISABLED: Icon = IconLoader.getDisabledIcon(ARROW)
        private val REPORT: Icon = AllIcons.General.Web
        private val REPORT_DISABLED: Icon = IconLoader.getDisabledIcon(REPORT)

        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
        private val RING get() = JBUI.scale(16)
        private val LEAD get() = JBUI.scale(9)

        private val RING_TRACK = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD5D5D5, 0x4E5157))
        private val RING_PROGRESS = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x389FD6, 0x3592C4))

        fun threadsLabel(threads: String): String = when (threads) {
            "" -> TestoBundle.message("infection.threads.config")
            "max" -> TestoBundle.message("infection.threads.max")
            else -> threads
        }
    }
}
