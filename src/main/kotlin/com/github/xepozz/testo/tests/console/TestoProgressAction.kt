package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.Filter
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.TestFrameworkPropertyListener
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.ColorUtil
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The run summary at the far right of the test toolbar: a progress ring that turns into a verdict icon, the
 * finished/total fraction, one counter per Testo status, and the elapsed time.
 *
 * Every counter is a button: it narrows the tree to that status, and clicking it again hands the tree back to the
 * toolbar's standing toggles. Both write the tree's single filter slot — see [applyFilter].
 *
 * [RightAlignedToolbarAction] pushes the whole thing past every other button; the separator in front of it is painted
 * by the component itself, because a `Separator` action would stay behind with the left-aligned group.
 */
class TestoProgressAction : AnAction(), CustomComponentAction, RightAlignedToolbarAction {

    /** Exit code of the run, once it has one — the verdict icon prefers it over what the tree says. */
    private val exitCode = AtomicReference<Int?>(null)

    // Not read off SMTestRunnerResultsForm: getStartTime/getEndTime/getTotalTestCount are only public from 2026.2,
    // and the form knows nothing of the phases the hover breaks the run into.
    private var timings: TestoRunTimings? = null

    private var resultsForm: SMTestRunnerResultsForm? = null
    private var statusStore: TestoStatusStore? = null
    private var consoleProperties: TestConsoleProperties? = null

    /** What the tree is narrowed to right now; `null` means no filter of ours is applied. */
    private var selected: TestoTestStatus? = null

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = ProgressPanel()

    fun attachTo(
        console: SMTRunnerConsoleView,
        store: TestoStatusStore,
        clock: TestoRunTimings,
        targets: TestoTargetStore,
        handler: ProcessHandler?,
    ) {
        val viewer = console.resultsViewer
        resultsForm = viewer
        statusStore = store
        timings = clock
        consoleProperties = console.properties

        // The platform rewrites the shared slot on every toggle, dropping a live counter. Registered after its own
        // listener, so this one runs second and re-asserts whichever filter is in charge.
        val onToggle = object : TestFrameworkPropertyListener<Boolean> {
            override fun onChanged(value: Boolean) = applyFilter()
        }
        console.properties.addListener(TestConsoleProperties.HIDE_PASSED_TESTS, onToggle)
        console.properties.addListener(TestConsoleProperties.HIDE_IGNORED_TEST, onToggle)
        // Called from the augmenter's processStarted, so this is as close to the real start as the plugin can get.
        clock.noteStart()

        viewer.addEventsListener(object : TestResultsViewer.EventsListener {
            override fun onTestingStarted(viewer: TestResultsViewer) {
                // Only on a second session in the same console: wiping on every announcement would throw away what
                // the converter already reported for this run, since it reads the stream before the platform.
                if (clock.isFinished()) {
                    store.clear()
                    targets.clear()
                    clock.clear()
                    clock.noteStart()
                    exitCode.set(null)
                }
                // Unconditionally: a narrowed tree must not survive into the next run, and until we write ours the
                // slot holds the platform's isPassed/isIgnored composition, which hides risky tests as passes.
                ApplicationManager.getApplication().invokeLater {
                    selected = null
                    applyFilter()
                }
            }

            override fun onTestNodeAdded(viewer: TestResultsViewer, test: SMTestProxy) = Unit

            override fun onSelected(
                selected: SMTestProxy?,
                viewer: TestResultsViewer,
                model: TestFrameworkRunningModel,
            ) = Unit

            override fun onTestingFinished(viewer: TestResultsViewer) {
                // The only safe moment to read the tree: nothing appends to it any more.
                runCatching { store.recountFrom(viewer.testsRootNode) }
                clock.noteFinish()
            }
        })

        // The verdict follows the process, not the tree: a run that dies before reporting anything is still red, and
        // a run killed mid-flight stops the clock even though onTestingFinished never came.
        handler?.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                exitCode.set(event.exitCode)
                clock.noteFinish()
            }
        })
    }

    /**
     * The verdict icon the ring turns into once the run is over, or `null` while it is still going.
     *
     * Public because the tree's root wears the same one — the run has one verdict, and deriving it twice is how the
     * two would come to disagree. Recomputed per call, so a repaint never shows a verdict already moved past.
     */
    fun currentVerdict(): Icon? {
        val form = resultsForm ?: return null
        val store = statusStore ?: return null
        if (timings?.snapshot()?.finished != true) return null
        return verdict(store.counts(), wasCancelled(form))
    }

    /**
     * Whether the run was cut short: the platform terminates the root node when testing finishes with the tree
     * still incomplete.
     *
     * Not `processWillTerminate`'s `willBeDestroyed` — `notifyProcessTerminated` hard-codes that to `true`, so it
     * arrives set on an ordinary exit too and greyed out every verdict.
     */
    private fun wasCancelled(form: SMTestRunnerResultsForm): Boolean =
        runCatching { form.testsRootNode.isInterrupted }.getOrDefault(false)

    /** Green or red normally, grey when cancelled — the tests that did report still pick check or cross. */
    private fun verdict(counts: Map<TestoTestStatus, Int>, cancelled: Boolean): Icon {
        val problems = counts.any { (status, n) -> status.isProblem && n > 0 }
        // A killed process has no exit code worth reading, so a cancelled run is judged only by its tests.
        val failed = if (cancelled) problems else exitCode.get()?.let { it != 0 } ?: problems
        return when {
            cancelled && failed -> TestoIcons.Status.FAILURE_CANCELLED
            cancelled -> TestoIcons.Status.SUCCESS_CANCELLED
            failed -> TestoIcons.Status.FAILURE
            else -> TestoIcons.Status.SUCCESS
        }
    }

    /** Narrows the tree to [status]; passing `null` or the already selected status hands it back to the toggles. */
    private fun toggleFilter(status: TestoTestStatus?) {
        selected = if (status == null || status == selected) null else status
        applyFilter()
    }

    /**
     * Writes whichever filter is in charge into the tree's one filter slot.
     *
     * A selected counter replaces the standing toggles rather than narrowing them further: intersecting would answer
     * "show me the passed ones" with an empty tree while "Show passed" is off. Releasing it restores them.
     */
    private fun applyFilter() {
        val form = resultsForm ?: return
        val store = statusStore ?: return
        val active = selected
        form.setFilter(if (active != null) OnlyStatus(active, store) else standingFilter(store))
    }

    /**
     * The toolbar's two toggles, read in Testo's statuses rather than the platform's three.
     *
     * The platform composes them off `isPassed`/`isIgnored`, where flaky and risky both look like a plain pass — so
     * "Show passed" hid risky tests and no toggle could reach them. See [hiddenByToggles].
     */
    private fun standingFilter(store: TestoStatusStore): Filter<AbstractTestProxy> {
        val properties = consoleProperties ?: return Filter.NO_FILTER
        val hidden = hiddenByToggles(
            hidePassed = TestConsoleProperties.HIDE_PASSED_TESTS.value(properties),
            hideIgnored = TestConsoleProperties.HIDE_IGNORED_TEST.value(properties),
        )
        return if (hidden.isEmpty()) Filter.NO_FILTER else ExceptStatuses(hidden, store)
    }

    /** Keeps suites whose subtree still holds a matching test, so the matches stay reachable under their parents. */
    private class OnlyStatus(
        private val status: TestoTestStatus,
        private val store: TestoStatusStore,
    ) : Filter<AbstractTestProxy>() {
        override fun shouldAccept(test: AbstractTestProxy): Boolean {
            val proxy = test as? SMTestProxy ?: return true
            return if (proxy.isSuite) proxy.children.any { shouldAccept(it) } else store.statusOf(proxy) == status
        }
    }

    /** The standing toggles: everything survives except the listed statuses. */
    private class ExceptStatuses(
        private val hidden: Set<TestoTestStatus>,
        private val store: TestoStatusStore,
    ) : Filter<AbstractTestProxy>() {
        override fun shouldAccept(test: AbstractTestProxy): Boolean {
            val proxy = test as? SMTestProxy ?: return true
            // A childless suite hides nothing, so it stays. A running test has no status yet and is never hidden.
            if (proxy.isSuite) return proxy.children.isEmpty() || proxy.children.any { shouldAccept(it) }
            return store.statusOf(proxy) !in hidden
        }
    }

    /**
     * One row of: ring/verdict, fraction, per-status counters, elapsed time. It polls rather than subscribes — the
     * counts live in [TestoStatusStore], which the converter feeds off the EDT, and the ring has to animate anyway.
     */
    private inner class ProgressPanel : JPanel() {
        private val progress = ProgressCell()
        private val counters = TestoTestStatus.entries.map { StatusCell(it) }
        private val elapsed = ElapsedCell()
        private val timer = Timer(REFRESH_MS) { tick() }

        init {
            isOpaque = false
            // Laid out by hand: a LayoutManager caches its size requirements, and this row is re-measured on a
            // timer — a stale cache answers "width unchanged" forever and the counters clip every digit that grows.
            layout = null
            border = JBUI.Borders.empty(0, 10, 0, 6)
            add(progress)
            counters.forEach { add(it) }
            add(elapsed)
            isVisible = false
        }

        /** Summed straight off the children, every time — see the note on [layout]. */
        override fun getPreferredSize(): Dimension {
            val insets = insets
            var width = insets.left + insets.right
            var height = 0
            for (child in components) {
                if (!child.isVisible) continue
                val size = child.preferredSize
                width += size.width
                height = maxOf(height, size.height)
            }
            return Dimension(width, height + insets.top + insets.bottom)
        }

        // A fixed set of labels: nothing to give up when space runs short, nothing to do with more of it.
        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun doLayout() {
            var x = insets.left
            for (child in components) {
                if (!child.isVisible) {
                    child.setBounds(x, 0, 0, 0)
                    continue
                }
                val size = child.preferredSize
                child.setBounds(x, (height - size.height) / 2, size.width, size.height)
                x += size.width
            }
        }

        override fun addNotify() {
            super.addNotify()
            timer.start()
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        // Zooming reaches every component through this call. The cells pick up the new font themselves, but a
        // settled run skips the tick that would re-measure the row, so the widths are declared stale here.
        override fun updateUI() {
            super.updateUI()
            painted = null
            laidOutWidth = -1
            revalidate()
            repaint()
        }

        /** Everything the row shows, so a tick that would redraw the same pixels can be dropped. */
        private var painted: String? = null
        private var laidOutWidth = -1

        private fun tick() {
            val form = resultsForm ?: return
            val store = statusStore ?: return
            val clock = timings ?: return

            val counts = store.counts()
            val finished = counts.values.sum()
            val total = store.totalHint()
            val assertions = store.assertionCount()
            val spans = clock.snapshot()
            val running = form.isRunning && !spans.finished
            val cancelled = wasCancelled(form)
            // A finished tab keeps ticking (the toolbar may still rebuild it), but stops redrawing once settled.
            val digest =
                "$finished/$total|$running|$counts|$assertions|$selected|$spans|${exitCode.get()}|$cancelled"
            if (!running && digest == painted) return
            painted = digest

            progress.update(finished, total, assertions, running, if (spans.finished) verdict(counts, cancelled) else null)
            // The tree's root wears the same verdict and repaints on its own schedule: nudge it on the settling tick.
            if (spans.finished) form.treeView?.repaint()
            counters.forEach { it.update(counts[it.status] ?: 0, it.status == selected) }
            elapsed.update(spans)

            // Nothing reported and nothing running: give the width back to the buttons on the left.
            isVisible = running || finished > 0 || spans.finished
            // Only a width change concerns the toolbar; the ring animating in place does not.
            val width = preferredSize.width
            if (width != laidOutWidth) {
                laidOutWidth = width
                revalidate()
            }
            repaint()
        }

        // The separator fencing the widget off from the buttons on its left.
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = SEPARATOR
            val inset = JBUI.scale(3)
            g.fillRect(0, inset, JBUI.scale(1), height - 2 * inset)
        }
    }

    /** One icon-and-number cell, drawn by hand so the hover highlight, the ring and the text share one baseline. */
    private abstract inner class Cell : JComponent() {
        private var hovered = false
        protected var active = false
        protected var text: String = ""
        protected var icon: Icon? = null

        /** Width reserved before the text: the icon, or whatever a subclass paints in its place. */
        protected open val leadingWidth: Int get() = icon?.iconWidth ?: 0

        // Asked for per paint rather than assigned once: a font set on a raw JComponent outlives a zoom, since
        // there is no UI delegate to reinstall it, and the row would keep the size it was built at.
        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = repaintAs { hovered = true }
                override fun mouseExited(e: MouseEvent) = repaintAs { hovered = false }
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

        private inline fun repaintAs(change: () -> Unit) {
            change()
            repaint()
        }

        protected abstract fun onClick()

        /** Set by subclasses that are not buttons. */
        protected fun makeInert() {
            cursor = Cursor.getDefaultCursor()
        }

        /** Swaps the tooltip only on a real change: `setToolTipText` re-registers with the ToolTipManager. */
        protected fun retip(tip: String) {
            if (tip != toolTipText) toolTipText = tip
        }

        override fun getPreferredSize(): Dimension {
            if (!isVisible) return Dimension(0, 0)
            val metrics = getFontMetrics(font)
            val textWidth = if (text.isEmpty()) 0 else metrics.stringWidth(text)
            val gap = if (leadingWidth > 0 && textWidth > 0) GAP else 0
            val height = maxOf(icon?.iconHeight ?: 0, metrics.height, JBUI.scale(16)) + JBUI.scale(4)
            return Dimension(PADDING * 2 + leadingWidth + gap + textWidth, height)
        }

        override fun getMaximumSize(): Dimension = preferredSize
        override fun getMinimumSize(): Dimension = preferredSize

        final override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                if (active || hovered) {
                    g2.color = if (active) JBUI.CurrentTheme.ActionButton.pressedBackground()
                    else JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                }
                paintLeading(g2, PADDING)
                icon?.let { it.paintIcon(this, g2, PADDING, (height - it.iconHeight) / 2) }
                if (text.isNotEmpty()) {
                    g2.color = UIUtil.getLabelForeground()
                    g2.font = font
                    val metrics = g2.fontMetrics
                    val x = PADDING + leadingWidth + (if (leadingWidth > 0) GAP else 0)
                    g2.drawString(text, x, (height - metrics.height) / 2 + metrics.ascent)
                }
            } finally {
                g2.dispose()
            }
        }

        /** Hook for whatever takes the icon's place; called before the icon, so only one of the two ever draws. */
        protected open fun paintLeading(g: Graphics2D, x: Int) = Unit
    }

    /** Ring while the run streams, verdict icon once it is over; resets the filter on click. */
    private inner class ProgressCell : Cell() {
        private var fraction = 0.0
        private var indeterminate = true
        private var spin = 0

        // Reserve the ring's slot even when a verdict icon has taken it, so the row does not shift on the last tick.
        override val leadingWidth: Int get() = maxOf(icon?.iconWidth ?: 0, RING)

        override fun onClick() = toggleFilter(null)

        fun update(finished: Int, total: Int, assertions: Int?, running: Boolean, verdict: Icon?) {
            icon = verdict
            indeterminate = total <= 0
            fraction = if (total > 0) (finished.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
            // Counts go in as strings: MessageFormat would otherwise group a plain Int into "1,234".
            val done = finished.toString()
            val all = total.toString()
            text = if (total > 0) TestoBundle.message("testo.progress.total.fraction", done, all)
            else TestoBundle.message("testo.progress.total", done)
            // Assertions have nowhere to go in the row itself, so the hover is where Testo's count surfaces.
            retip(
                if (assertions == null) TestoBundle.message("testo.progress.total.tooltip", done, all)
                else TestoBundle.message("testo.progress.total.tooltip.assertions", done, all, assertions.toString())
            )
            if (running) spin = (spin + SPIN_STEP) % 360
        }

        override fun paintLeading(g: Graphics2D, x: Int) {
            if (icon != null) return
            val stroke = JBUI.scale(2).toFloat()
            val size = (RING - stroke).toDouble()
            val left = x + stroke / 2.0
            val top = (height - RING) / 2.0 + stroke / 2.0
            g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = RING_TRACK
            g.draw(Arc2D.Double(left, top, size, size, 0.0, 360.0, Arc2D.OPEN))
            g.color = RING_PROGRESS
            val extent = if (indeterminate) -SPIN_ARC else -360.0 * fraction
            val start = if (indeterminate) 90.0 - spin else 90.0
            g.draw(Arc2D.Double(left, top, size, size, start, extent, Arc2D.OPEN))
        }
    }

    /** One status counter; hidden while its count is zero. */
    private inner class StatusCell(val status: TestoTestStatus) : Cell() {
        init {
            icon = status.icon
            isVisible = false
            toolTipText = TestoBundle.message("testo.progress.filter.tooltip", status.label)
        }

        override fun onClick() = toggleFilter(status)

        fun update(count: Int, isSelected: Boolean) {
            isVisible = count > 0
            active = isSelected
            text = TestoBundle.message("testo.progress.counter", count.toString(), status.label)
        }
    }

    /** Not a button — the run's wall clock, with the phase breakdown on the hover. */
    private inner class ElapsedCell : Cell() {
        init {
            icon = AllIcons.Vcs.History
            isVisible = false
            makeInert()
        }

        override fun onClick() = Unit

        fun update(spans: TestoRunTimings.Snapshot) {
            isVisible = spans.totalMs > 0
            text = formatElapsed(spans.totalMs)
            retip(breakdown(spans))
        }

        private fun breakdown(spans: TestoRunTimings.Snapshot): String = buildString {
            append("<html><table cellpadding='0' cellspacing='0'>")
            // Bold and undimmed: the line the rest breaks down, not one figure among five. No rule under it —
            // Swing's <hr> answers to neither CSS nor the theme.
            row("testo.progress.elapsed.total", formatElapsed(spans.totalMs), emphasized = true, dimLabel = false)
            if (spans.startupMs > 0) row("testo.progress.elapsed.startup", formatElapsed(spans.startupMs))
            if (spans.testsMs > 0) row("testo.progress.elapsed.tests", formatElapsed(spans.testsMs))
            if (spans.summedTestsMs > 0) {
                val factor = spans.parallelism
                // Worth a line only where it parts with the window it fitted into: level with the wall clock it is
                // the Tests figure printed twice.
                if (factor == null || factor >= BOOST_FLOOR || factor <= 1 / BOOST_FLOOR) {
                    row("testo.progress.elapsed.summed", formatElapsed(spans.summedTestsMs))
                }
                // A floor, not an equality: the window also holds work between tests that no duration counts.
                factor?.takeIf { it >= BOOST_FLOOR }?.let {
                    row(
                        "testo.progress.elapsed.boost",
                        TestoBundle.message("testo.progress.elapsed.boost.value", formatFactor(it)),
                        emphasized = true,
                    )
                }
            }
            if (spans.postProcessingMs > 0) {
                row("testo.progress.elapsed.postprocessing", formatElapsed(spans.postProcessingMs))
            }
            append("</table></html>")
        }

        /**
         * One line of the breakdown: label left, figure right-aligned.
         *
         * `<nobr>` on both cells, or the columns stop lining up at the width Swing's HTML view first settles on.
         * Emphasis and dimming are `<b>` and `<font>` tags: this is HTML 3.2, which ignores `font-weight` and
         * `color` on a cell.
         *
         * @param emphasized bold, both label and figure.
         * @param dimLabel the label in the breakdown's muted colour.
         */
        private fun StringBuilder.row(
            labelKey: String,
            value: String,
            emphasized: Boolean = false,
            dimLabel: Boolean = true,
        ) {
            append("<tr><td><nobr>")
            if (dimLabel) append("<font color='").append(LABEL_COLOR).append("'>")
            if (emphasized) append("<b>")
            append(TestoBundle.message(labelKey))
            if (emphasized) append("</b>")
            if (dimLabel) append("</font>")
            append("&nbsp;&nbsp;&nbsp;</nobr></td><td align='right'><nobr>")
            if (emphasized) append("<b>").append(value).append("</b>") else append(value)
            append("</nobr></td></tr>")
        }
    }

    companion object {
        private const val REFRESH_MS = 100
        // How far the summed test time must stand from the window it fitted into to be worth saying at all; within
        // this band the two are one figure read off two clocks.
        private const val BOOST_FLOOR = 1.05
        private const val SPIN_STEP = 12
        private const val SPIN_ARC = 90.0

        // Read at paint time, never cached: the scale changes with the monitor the IDE was dragged to.
        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
        // Matches the status icons, so the row does not shift when the ring is replaced by the verdict.
        private val RING get() = JBUI.scale(16)

        private val SEPARATOR = JBColor.namedColor("Toolbar.separatorColor", JBColor(0xCDCDCD, 0x515151))
        private val RING_TRACK = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD5D5D5, 0x4E5157))
        // Falls back to the JetBrains palette blue when the theme names no progress colour of its own.
        private val RING_PROGRESS = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x389FD6, 0x3592C4))

        // Read per build, not held: a theme switch would otherwise leave dark-theme grey on a light background.
        private val LABEL_COLOR: String get() = "#" + ColorUtil.toHex(UIUtil.getContextHelpForeground())

        internal fun formatElapsed(ms: Long): String = when {
            // Hundredths read as precision up to a minute and as noise past it, where whole seconds are enough.
            ms < 60_000 -> String.format(Locale.ROOT, "%.2f sec", ms / 1000.0)
            else -> String.format(Locale.ROOT, "%d min %02d sec", ms / 60_000, ms % 60_000 / 1000)
        }

        internal fun formatFactor(factor: Double): String = String.format(Locale.ROOT, "%.1f", factor)
    }
}

/**
 * Which Testo statuses the toolbar's two standing toggles take out of the tree.
 *
 * Flaky goes with passed: it wears the same check, only yellow, and a run that ended green on the retry is not what
 * "hide what passed" is meant to leave behind. Risky does not — its icon is an exclamation, it is the framework
 * saying the test did something it could not vouch for, and that is exactly what a failures-only view is for. So with
 * both toggles off the tree holds failed, error, aborted and risky, which is the set worth looking at.
 *
 * Cancelled goes with skipped for the same kind of reason: neither reached a verdict, and Testo reports both to the
 * platform through `testIgnored`, so this is also what the button did before — only now it says so in Testo's terms
 * rather than by accident of the three states the protocol can carry.
 */
fun hiddenByToggles(hidePassed: Boolean, hideIgnored: Boolean): Set<TestoTestStatus> = buildSet {
    if (hidePassed) {
        add(TestoTestStatus.PASSED)
        add(TestoTestStatus.FLAKY)
    }
    if (hideIgnored) {
        add(TestoTestStatus.SKIPPED)
        add(TestoTestStatus.CANCELLED)
    }
}
