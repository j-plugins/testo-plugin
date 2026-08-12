package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
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
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
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
 * The report buttons at the far right of the test toolbar, past the run summary — one per report Testo announced.
 *
 * Hand-drawn, like the run summary beside it: no platform widget gives icon, name, a click that opens the report and an
 * arrow for the other ways to open it, and an expanded `ActionGroup` loses [RightAlignedToolbarAction] on its children.
 *
 * A cell always looks and acts enabled; what the click does is decided by whether the file is there yet. Present, fresh
 * and the process exited — it opens; otherwise the click is kept as a deferred open, replayed once the run delivers the
 * file (see [TestoReportAutoOpen]). A scheduled open wears a clock badge on the cell's icon, and the tooltip tells the
 * rest: writing, delivered, or never written in this run.
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

    /**
     * One cell per announced report, polled rather than subscribed: the store is written by the converter off the
     * process's thread, and whether the file exists yet changes without anything telling us.
     */
    private inner class ReportsPanel : JPanel() {
        private val cells = LinkedHashMap<String, ReportCell>()
        private val timer = Timer(REFRESH_MS) { tick() }

        init {
            isOpaque = false
            // Laid out by hand, like the run summary: a LayoutManager caches size requirements, and this row is
            // re-measured whenever a report appears or its name changes.
            layout = null
            // The left inset holds the separator that parts the reports from the run summary.
            border = JBUI.Borders.empty(0, 10, 0, 4)
            isVisible = false
        }

        // A platform Separator can't sit here: it is not right-aligned, so it would land among the left buttons.
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
            // Cells follow the announcements: added when a report shows up, dropped if the store is ever cleared.
            announced.forEach { ref ->
                cells.getOrPut(ref.path) { ReportCell(ref).also { add(it) } }.ref = ref
            }
            val gone = cells.keys - announced.mapTo(HashSet()) { it.path }
            gone.forEach { path -> cells.remove(path)?.let { remove(it) } }

            cells.values.forEach { it.refresh() }
            isVisible = cells.isNotEmpty()

            // Only when the row itself changed shape — a cell that merely lit up repaints itself, and this runs
            // twice a second for as long as the tab is open.
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
        // The run whose report this cell has already handed to maybeAutoOpen, so one run opens it at most once —
        // a report deleted and rewritten within the run must not pop the viewer open again.
        private var autoOpenedRun = -1L
        // A fresh cell has no tooltip yet, so the first refresh must go through however little has changed.
        private var refreshed = false
        private var hovered = false

        // Asked for per paint: a font set once on a raw JComponent outlives a zoom, since there is no UI delegate to
        // reinstall it, and the cell would keep the size it was built at.
        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
            // Always the hand: a cell whose file is not there yet still takes the click, as a deferred open.
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

        /** Re-resolves the file, which is what moves the cell between lit and dimmed. */
        fun refresh() {
            // Not while the run is going: the report is announced as Testo starts writing it, over the path the
            // previous run wrote to — so a check now would light the button up on a report that belongs to that run.
            val finished = reports.runFinished
            val found = if (finished) resolveReport(ref, project, mapToLocal, reports.runStartedAt) else null
            maybeAutoOpen(found, finished)
            // Scheduled only while the run still goes: what remains scheduled after it (a stopped run's arm) has
            // nothing left to fire it, and must not keep wearing the colour of a promise.
            val willOpen = !finished && TestoReportAutoOpen.decide(project, reports, ref).isNotEmpty()
            // Tooltip and repaint only on a real change: this runs twice a second for as long as the tab is open.
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
         * The deferred click: the first time this run's report is there, every standing choice opens it its own way.
         * Marked per run whether a choice exists or not, so checking "always open" *after* the report arrived starts
         * with the next run instead of popping this one open under the user.
         */
        private fun maybeAutoOpen(found: Path?, finished: Boolean) {
            if (found == null || !finished || autoOpenedRun == reports.runStartedAt) return
            autoOpenedRun = reports.runStartedAt
            val key = TestoReportAutoOpen.keyOf(ref)
            TestoReportAutoOpen.decide(project, reports, ref).forEach { way ->
                // The run's own arm is one-shot; a project- or application-wide choice stays for the next run.
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
                // Grey while there is nothing to open, blue once this run's report is on disk, green while the run
                // still goes but an auto-open stands scheduled.
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
         * The button un-pressed and pressed again. Un-pressing silences every way of opening for this run behind one
         * flag — the standing checkmarks stay put; pressing back lifts the flag so they resume, and with none of them
         * checked it schedules the one thing a bare press can mean: the WebView, for this run.
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

    private companion object {
        private const val REFRESH_MS = 500

        private val ICON: Icon = AllIcons.General.IndentDetected
        private val ARROW: Icon = AllIcons.General.LinkDropTriangle

        /** The icon's three colours: grey (nothing to open), blue (this run's report is on disk), green (scheduled). */
        private val READY_ICON: Icon = IconUtil.colorize(ICON, JBColor(0x3574F0, 0x548AF7))
        private val SCHEDULED_ICON: Icon = IconUtil.colorize(ICON, JBColor(0x59A869, 0x499C54))

        // Read at paint time, never cached: the scale changes with the monitor the IDE was dragged to.
        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
    }
}

/**
 * "Open in …" as a perform group: clicking the entry opens the report — or arms its way's deferred open while there
 * is nothing to open yet — and its submenu chooses when the report opens on its own.
 */
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
        // Checking this-run goes through openOrArm, so a choice over a report already delivered opens it right away
        // instead of arming a click nothing is left to replay.
        if (state && scope == AutoOpenScope.THIS_RUN) {
            openOrArm(way)
        } else {
            TestoReportAutoOpen.set(scope, project, reports, key(), way, state)
        }
    }

    private fun key(): String = TestoReportAutoOpen.keyOf(target())
}

/** Shows the report in the file manager, named whatever this OS calls it — "Show in Explorer", "Reveal in Finder". */
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
        // Re-resolved rather than remembered: the report may have been deleted since the menu was drawn.
        val path = resolve() ?: return
        RevealFileAction.openFile(path)
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
        // Re-resolved rather than remembered: the report may have been deleted since the menu was drawn.
        val path = resolve() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
    }

    private fun resolve(): Path? = resolveReport(target(), project, mapToLocal, reports.runStartedAt)
}

/**
 * Hands the report to the external browser.
 *
 * Through `Path.toUri()`, not `browse(File)`: the latter goes by way of the Windows path, whose separators come out
 * percent-encoded — `file:///D:/%5Cgit%5C…`, which no browser resolves. `toUri()` yields `file:///D:/git/…`.
 */
private fun browseReport(path: Path) = BrowserUtil.browse(path.toUri())

/**
 * The announced report as a local file this run wrote, or `null` while there is none.
 *
 * Touches the filesystem. Called from the cell's timer on the EDT — three `stat`s twice a second, which is the price of
 * noticing that the file has appeared without anything announcing it.
 */
internal fun resolveReport(
    ref: TestoReportRef,
    project: Project,
    mapToLocal: (String) -> String?,
    writtenAfter: Long,
): Path? =
    // The mapper is the PHP plugin's, over a path it may know nothing about: whatever it throws must not take the
    // toolbar's update with it.
    reportPathCandidates(ref, project.basePath) { runCatching { mapToLocal(it) }.getOrNull() }
        .asSequence()
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { isReportOf(it, writtenAfter) }

/** A file left by an earlier run reads as this one's, since the path never changes — hence the timestamp. */
internal fun isReportOf(path: Path, writtenAfter: Long): Boolean = runCatching {
    Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() >= writtenAfter
}.getOrDefault(false)
