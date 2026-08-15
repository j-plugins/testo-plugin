package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.TestoCoverageReport
import com.github.xepozz.testo.coverage.applyTestoCoverage
import com.github.xepozz.testo.coverage.closeTestoCoverage
import com.github.xepozz.testo.coverage.isTestoCoverageActive
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.ui.TestoReportViewer
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The report buttons at the far right of the test toolbar — one per report Testo announced. Hand-drawn like the run
 * summary beside it: an expanded `ActionGroup` loses [RightAlignedToolbarAction] on its children. A click before the
 * report is delivered is kept as a deferred open and replayed once it is (see [TestoReportAutoOpen]).
 */
class TestoReportsAction(
    private val reports: TestoReportStore,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
) : AnAction(), CustomComponentAction, RightAlignedToolbarAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = ReportsPanel()

    /** One cell per announced report, polled: whether the file exists yet changes without anything telling us. */
    private inner class ReportsPanel : JPanel() {
        private val cells = LinkedHashMap<String, ReportCell>()
        private var coverageCell: CoverageGroupCell? = null
        private val timer = Timer(REFRESH_MS) { tick() }

        init {
            isOpaque = false
            // Laid out by hand, like the run summary: a LayoutManager caches size requirements.
            layout = null
            border = JBUI.Borders.empty(0, 10, 0, 4)
            isVisible = false
        }

        // The separator fencing the reports off; a platform Separator is not right-aligned and would land elsewhere.
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.color = JBColor.border()
            val inset = JBUI.scale(4)
            g.fillRect(JBUI.scale(2), inset, JBUI.scale(1), height - 2 * inset)
        }

        override fun addNotify() {
            super.addNotify()
            timer.start()
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

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

        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun doLayout() {
            var x = insets.left
            for (child in components) {
                val size = child.preferredSize
                child.setBounds(x, (height - size.height) / 2, size.width, size.height)
                x += size.width
            }
        }

        private var laidOutWidth = -1

        private fun tick() {
            val announced = reports.viewable()
            announced.forEach { ref ->
                cells.getOrPut(ref.path) { ReportCell(ref).also { add(it) } }.ref = ref
            }
            val gone = cells.keys - announced.mapTo(HashSet()) { it.path }
            gone.forEach { path -> cells.remove(path)?.let { remove(it) } }
            cells.values.forEach { it.refresh() }

            val coverage = reports.coverage()
            val cell = when {
                coverage.isEmpty() -> coverageCell?.let { remove(it); coverageCell = null; null }
                else -> coverageCell ?: CoverageGroupCell().also { add(it); coverageCell = it }
            }
            cell?.refresh(coverage)

            isVisible = cells.isNotEmpty() || coverageCell != null

            // Re-laid out only when the row changed shape — this runs twice a second.
            val width = preferredSize.width
            if (width != laidOutWidth) {
                laidOutWidth = width
                revalidate()
                repaint()
            }
        }
    }

    /** Icon, the report's own name, and a dropdown arrow; the arrow's third of the cell opens the menu. */
    private inner class ReportCell(ref: TestoReportRef) : JComponent() {
        var ref: TestoReportRef = ref
        private var located: Path? = null
        private var runWasFinished = false
        private var willAutoOpen = false
        // The run this cell has already auto-opened for, so one run opens the report at most once.
        private var autoOpenedRun = -1L
        // A fresh cell has no tooltip yet, so the first refresh must go through however little has changed.
        private var refreshed = false
        private var hovered = false
        private var resolving = false

        // Asked for per paint: a font set once on a raw JComponent outlives a zoom (no UI delegate reinstalls it).
        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    when {
                        e.x >= width - arrowZone() -> showMenu()
                        located != null -> open(defaultWay(), located!!)
                        else -> toggleScheduled()
                    }
                }
            })
        }

        private fun text(): String = ref.name ?: TestoBundle.message("testo.report.action.text")

        private fun arrowZone(): Int = ARROW.iconWidth + GAP + PADDING

        fun refresh() {
            // Not while the run is going: a report is announced as Testo starts writing it, over the path the
            // previous run wrote to — a check now would offer that run's file.
            val finished = reports.runFinished
            if (!finished) {
                applyResolved(null, false)
                return
            }
            // resolveReport goes through the PHP path mapper, whose getLocalPath hits the file index — a slow operation
            // forbidden on the EDT, and this runs off a Swing timer on the EDT.
            if (resolving) return
            resolving = true
            val startedAt = reports.runStartedAt
            val cellRef = ref
            ApplicationManager.getApplication().executeOnPooledThread {
                val found = resolveReport(cellRef, project, mapToLocal, startedAt)
                ApplicationManager.getApplication().invokeLater(
                    {
                        resolving = false
                        // A rerun may have started while this resolved; applying then would auto-open the previous
                        // run's report and mark the new run as already opened. Drop it — the next tick sees the run.
                        if (reports.runStartedAt == startedAt && reports.runFinished) applyResolved(found, true)
                    },
                    ModalityState.any(),
                ) { project.isDisposed }
            }
        }

        private fun applyResolved(found: Path?, finished: Boolean) {
            maybeAutoOpen(found, finished)
            val willOpen = !finished && TestoReportAutoOpen.decide(project, reports, ref).isNotEmpty()
            // Tooltip and repaint only on a real change: this runs twice a second.
            if (refreshed && found == located && finished == runWasFinished && willOpen == willAutoOpen) return
            refreshed = true
            located = found
            runWasFinished = finished
            willAutoOpen = willOpen
            toolTipText = when {
                found != null -> TestoBundle.message("testo.report.action.description")
                willOpen -> TestoBundle.message("testo.report.action.description.armed")
                finished -> TestoBundle.message("testo.report.action.description.pending")
                else -> TestoBundle.message("testo.report.action.description.running")
            }
            repaint()
        }

        /**
         * Marked per run whether a choice exists or not, so checking "always open" *after* the report arrived starts
         * with the next run instead of popping this one open under the user.
         */
        private fun maybeAutoOpen(found: Path?, finished: Boolean) {
            if (found == null || !finished || autoOpenedRun == reports.runStartedAt) return
            autoOpenedRun = reports.runStartedAt
            val key = TestoReportAutoOpen.keyOf(ref)
            TestoReportAutoOpen.decide(project, reports, ref).forEach { way ->
                reports.armAutoOpen(key, way, false)
                open(way, found)
            }
        }

        override fun getPreferredSize(): Dimension {
            val metrics = getFontMetrics(font)
            val width = PADDING + ICON.iconWidth + GAP + metrics.stringWidth(text()) + GAP + ARROW.iconWidth + PADDING
            val height = maxOf(ICON.iconHeight, metrics.height, JBUI.scale(16)) + JBUI.scale(4)
            return Dimension(width, height)
        }

        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                if (hovered) {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                }
                val icon = when {
                    located != null -> READY_ICON
                    willAutoOpen -> SCHEDULED_ICON
                    else -> ICON
                }
                icon.paintIcon(this, g2, PADDING, (height - icon.iconHeight) / 2)

                g2.font = font
                g2.color = UIUtil.getLabelForeground()
                val metrics = g2.fontMetrics
                val textX = PADDING + ICON.iconWidth + GAP
                g2.drawString(text(), textX, (height - metrics.height) / 2 + metrics.ascent)

                ARROW.paintIcon(this, g2, width - PADDING - ARROW.iconWidth, (height - ARROW.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }

        private fun defaultWay(): ReportOpenWay =
            if (TestoReportViewer.isAvailable) ReportOpenWay.WEB_VIEW else ReportOpenWay.BROWSER

        /** Opens the report when it is there; otherwise keeps the click, to be replayed once the run delivers it. */
        private fun openOrArm(way: ReportOpenWay) {
            val path = located
            if (path != null) {
                open(way, path)
            } else {
                reports.armAutoOpen(TestoReportAutoOpen.keyOf(ref), way, true)
                refresh()
            }
        }

        /**
         * Un-pressing mutes every way of opening for this run without unchecking the standing choices; pressing back
         * lifts the mute, and with nothing standing it arms the default way for this run.
         */
        private fun toggleScheduled() {
            val key = TestoReportAutoOpen.keyOf(ref)
            if (TestoReportAutoOpen.decide(project, reports, ref).isNotEmpty()) {
                reports.muteAutoOpen(key, true)
            } else {
                reports.muteAutoOpen(key, false)
                if (TestoReportAutoOpen.decide(project, reports, ref).isEmpty()) {
                    reports.armAutoOpen(key, defaultWay(), true)
                }
            }
            refresh()
        }

        private fun open(way: ReportOpenWay, path: Path) {
            when (way) {
                ReportOpenWay.BROWSER -> browseReport(path)
                ReportOpenWay.WEB_VIEW -> {
                    val label = ref.name ?: TestoBundle.message("testo.report.editor.name")
                    if (!TestoReportViewer.open(project, path, label)) browseReport(path)
                }
            }
        }

        private fun showMenu() {
            val group = DefaultActionGroup(
                buildList {
                    if (TestoReportViewer.isAvailable) {
                        add(openGroup("testo.report.open.webview", AllIcons.Actions.Preview, ReportOpenWay.WEB_VIEW))
                    }
                    add(openGroup("testo.report.open.browser", AllIcons.Nodes.PpWeb, ReportOpenWay.BROWSER))
                    add(RevealReportAction({ ref }, project, mapToLocal, reports))
                    add(CopyReportPathAction({ ref }, project, mapToLocal, reports))
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

        private fun openGroup(key: String, icon: Icon, way: ReportOpenWay) =
            OpenReportGroup(TestoBundle.message(key), icon, way, { ref }, project, reports, ::openOrArm)
    }

    /**
     * The run's coverage reports as one button: a click applies every checked report as a single merged bundle
     * ([applyTestoCoverage]), the arrow opens a checkbox per report. Toggling a checkbox while Testo coverage is
     * showing recomposes the bundle live; unchecking the last one closes it.
     */
    private inner class CoverageGroupCell : JComponent() {
        private var refs: List<TestoReportRef> = emptyList()
        private var located: Map<String, Path> = emptyMap()
        private var runWasFinished = false
        private var refreshed = false
        private var hovered = false
        private var resolving = false

        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                override fun mouseClicked(e: MouseEvent) {
                    if (e.x >= width - arrowZone()) showMenu() else applyChecked()
                }
            })
        }

        private fun text(): String = TestoBundle.message("testo.coverage.group.text")

        private fun arrowZone(): Int = ARROW.iconWidth + GAP + PADDING

        fun refresh(coverage: List<TestoReportRef>) {
            refs = coverage
            val finished = reports.runFinished
            if (!finished) {
                applyResolved(emptyMap(), false)
                return
            }
            // resolveCoverageDataFile goes through the PHP path mapper and touches the filesystem — forbidden on the
            // EDT, and this runs off a Swing timer on the EDT. Resolve on a pooled thread, apply back on the EDT.
            if (resolving) return
            resolving = true
            val startedAt = reports.runStartedAt
            val snapshot = coverage
            ApplicationManager.getApplication().executeOnPooledThread {
                val found = LinkedHashMap<String, Path>()
                for (ref in snapshot) {
                    resolveCoverageDataFile(ref, project, mapToLocal, startedAt)?.let { found[ref.path] = it }
                }
                ApplicationManager.getApplication().invokeLater(
                    {
                        resolving = false
                        if (reports.runStartedAt == startedAt && reports.runFinished) applyResolved(found, true)
                    },
                    ModalityState.any(),
                ) { project.isDisposed }
            }
        }

        private fun applyResolved(found: Map<String, Path>, finished: Boolean) {
            if (refreshed && found == located && finished == runWasFinished) return
            refreshed = true
            located = found
            runWasFinished = finished
            toolTipText = when {
                found.isNotEmpty() -> TestoBundle.message("testo.coverage.action.description")
                finished -> TestoBundle.message("testo.coverage.action.description.pending")
                else -> TestoBundle.message("testo.coverage.action.description.running")
            }
            repaint()
        }

        /** The checked reports that are actually on disk — what a click applies and a toggle recomposes. */
        private fun checkedReports(): List<TestoCoverageReport> = refs.mapNotNull { ref ->
            val path = located[ref.path] ?: return@mapNotNull null
            if (!reports.isCoverageChecked(ref.path)) return@mapNotNull null
            TestoCoverageReport(ref.name, ref.coverageFormat, path)
        }

        private fun applyChecked() {
            val checked = checkedReports()
            if (checked.isNotEmpty()) applyTestoCoverage(project, checked)
        }

        private fun onToggled() {
            if (!isTestoCoverageActive(project)) return
            val checked = checkedReports()
            if (checked.isEmpty()) closeTestoCoverage(project) else applyTestoCoverage(project, checked)
        }

        private fun showMenu() {
            val group = DefaultActionGroup(refs.map { CoverageReportToggle(it) })
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

        private inner class CoverageReportToggle(private val ref: TestoReportRef) :
            ToggleAction(ref.name ?: ref.format), DumbAware {

            init {
                // Checking off several reports is one gesture — the menu must survive each click.
                templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun isSelected(e: AnActionEvent): Boolean = reports.isCoverageChecked(ref.path)

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                reports.setCoverageChecked(ref.path, state)
                onToggled()
            }
        }

        override fun getPreferredSize(): Dimension {
            val metrics = getFontMetrics(font)
            val width =
                PADDING + COVERAGE_ICON.iconWidth + GAP + metrics.stringWidth(text()) + GAP + ARROW.iconWidth + PADDING
            val height = maxOf(COVERAGE_ICON.iconHeight, metrics.height, JBUI.scale(16)) + JBUI.scale(4)
            return Dimension(width, height)
        }

        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                if (hovered) {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                }
                val icon = if (located.isNotEmpty()) COVERAGE_ICON else COVERAGE_PENDING_ICON
                icon.paintIcon(this, g2, PADDING, (height - icon.iconHeight) / 2)
                g2.font = font
                g2.color = UIUtil.getLabelForeground()
                val metrics = g2.fontMetrics
                g2.drawString(text(), PADDING + COVERAGE_ICON.iconWidth + GAP, (height - metrics.height) / 2 + metrics.ascent)
                ARROW.paintIcon(this, g2, width - PADDING - ARROW.iconWidth, (height - ARROW.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        private const val REFRESH_MS = 500

        private val ICON: Icon = AllIcons.General.IndentDetected
        private val ARROW: Icon = AllIcons.General.LinkDropTriangle

        /** The icon's three colours: grey (nothing to open), blue (this run's report is on disk), green (scheduled). */
        private val READY_ICON: Icon = IconUtil.colorize(ICON, JBColor(0x3574F0, 0x548AF7))
        private val SCHEDULED_ICON: Icon = IconUtil.colorize(ICON, JBColor(0x59A869, 0x499C54))

        // Coverage cell: the normal coverage icon once the report is on disk, greyed while it is still pending.
        private val COVERAGE_ICON: Icon = AllIcons.General.RunWithCoverage
        private val COVERAGE_PENDING_ICON: Icon = IconLoader.getDisabledIcon(COVERAGE_ICON)

        // Read at paint time, never cached: the scale changes with the monitor the IDE was dragged to.
        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
    }
}

/** "Open in …" as a perform group: the click opens (or arms), the submenu chooses when to open unasked. */
private class OpenReportGroup(
    text: String,
    icon: Icon,
    private val way: ReportOpenWay,
    target: () -> TestoReportRef,
    project: Project,
    reports: TestoReportStore,
    private val openOrArm: (ReportOpenWay) -> Unit,
) : DefaultActionGroup(text, null, icon), DumbAware {

    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.isPerformGroup = true
        add(toggle("testo.report.autoopen.run", AutoOpenScope.THIS_RUN, target, project, reports))
        add(toggle("testo.report.autoopen.project", AutoOpenScope.PROJECT, target, project, reports))
        add(toggle("testo.report.autoopen.application", AutoOpenScope.APPLICATION, target, project, reports))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = openOrArm(way)

    private fun toggle(
        key: String,
        scope: AutoOpenScope,
        target: () -> TestoReportRef,
        project: Project,
        reports: TestoReportStore,
    ) = AutoOpenToggle(TestoBundle.message(key), scope, way, target, project, reports, openOrArm)
}

/** One scope of [TestoReportAutoOpen] under one way of opening — every (way, scope) checkmark stands on its own. */
private class AutoOpenToggle(
    text: String,
    private val scope: AutoOpenScope,
    private val way: ReportOpenWay,
    private val target: () -> TestoReportRef,
    private val project: Project,
    private val reports: TestoReportStore,
    private val openOrArm: (ReportOpenWay) -> Unit,
) : ToggleAction(text), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        TestoReportAutoOpen.isSet(scope, project, reports, key(), way)

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        // Via openOrArm, so a this-run choice over a report already delivered opens it right away.
        if (state && scope == AutoOpenScope.THIS_RUN) {
            openOrArm(way)
        } else {
            TestoReportAutoOpen.set(scope, project, reports, key(), way, state)
        }
    }

    private fun key(): String = TestoReportAutoOpen.keyOf(target())
}

private class RevealReportAction(
    private val target: () -> TestoReportRef,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
    private val reports: TestoReportStore,
) : AnAction(RevealFileAction.getActionName(), null, AllIcons.Nodes.Folder), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = RevealFileAction.isSupported()
        e.presentation.isEnabled = e.presentation.isVisible && resolve() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openFile(resolve() ?: return)
    }

    private fun resolve(): Path? = resolveReport(target(), project, mapToLocal, reports.runStartedAt)
}

private class CopyReportPathAction(
    private val target: () -> TestoReportRef,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
    private val reports: TestoReportStore,
) : AnAction(TestoBundle.message("testo.report.copy.path"), null, AllIcons.Actions.Copy), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = resolve() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        // Re-resolved: the report may have been deleted since the menu was drawn.
        val path = resolve() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
    }

    private fun resolve(): Path? = resolveReport(target(), project, mapToLocal, reports.runStartedAt)
}

// Via toUri(), not browse(File): the latter percent-encodes Windows separators and no browser resolves the result.
private fun browseReport(path: Path) = BrowserUtil.browse(path.toUri())

/**
 * The coverage data file this run wrote — the report file itself for clover/cobertura, or `<dir>/index.xml` for
 * coverage-xml (a directory the platform's file provider cannot consume) — or `null` while there is none yet.
 */
internal fun resolveCoverageDataFile(
    ref: TestoReportRef,
    project: Project,
    mapToLocal: (String) -> String?,
    writtenAfter: Long,
): Path? {
    val coverageXml = ref.coverageFormat == CoverageFormat.COVERAGE_XML
    return reportPathCandidates(ref, project.basePath) { runCatching { mapToLocal(it) }.getOrNull() }
        .asSequence()
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .map { if (coverageXml && !it.fileName?.toString().equals("index.xml", ignoreCase = true)) it.resolve("index.xml") else it }
        .firstOrNull { isReportOf(it, writtenAfter) }
}

/** The announced report as a local file this run wrote, or `null` while there is none. Touches the filesystem. */
internal fun resolveReport(
    ref: TestoReportRef,
    project: Project,
    mapToLocal: (String) -> String?,
    writtenAfter: Long,
): Path? =
    // The PHP plugin's mapper may throw over a path it does not know; that must not take the toolbar with it.
    reportPathCandidates(ref, project.basePath) { runCatching { mapToLocal(it) }.getOrNull() }
        .asSequence()
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { isReportOf(it, writtenAfter) }

/** A file left by an earlier run reads as this one's, since the path never changes — hence the timestamp. */
internal fun isReportOf(path: Path, writtenAfter: Long): Boolean = runCatching {
    Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() >= writtenAfter
}.getOrDefault(false)
