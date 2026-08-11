package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.ui.TestoReportViewer
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
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
 * Hand-drawn, for the same reasons the run summary beside it is: a plain toolbar button shows the icon alone (the text
 * survives only as a tooltip), `SplitButtonAction` paints its own component and drops the text entirely, and
 * `ComboBoxAction` turns every click into a dropdown. Only a custom component gives icon, name, a click that opens the
 * report, and an arrow for the other ways to open it — and, being one right-aligned action rather than an expanded
 * group, it actually lands to the right of the summary instead of among the buttons on the left.
 *
 * States, in the order a run walks through them: no announcement, no button; announced, a disabled button for as long
 * as the run lasts; the process exits with the file there, the button lights up; the file is deleted, it goes back to
 * disabled.
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
            border = JBUI.Borders.empty(0, 6, 0, 4)
            isVisible = false
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

            val width = preferredSize.width
            if (width != laidOutWidth) {
                laidOutWidth = width
                revalidate()
            }
            repaint()
        }
    }

    /** Icon, the report's own name, and a dropdown arrow; the arrow's third of the cell opens the menu. */
    private inner class ReportCell(ref: TestoReportRef) : JComponent() {
        var ref: TestoReportRef = ref
        private var located: Path? = null
        private var hovered = false
        private var lastLogged: String? = null

        // Asked for per paint: a font set once on a raw JComponent outlives a zoom, since there is no UI delegate to
        // reinstall it, and the cell would keep the size it was built at.
        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
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
                    if (located == null) return
                    if (e.x >= width - arrowZone()) showMenu() else openDefault()
                }
            })
        }

        private fun text(): String = ref.name ?: TestoBundle.message("testo.report.action.text")

        private fun arrowZone(): Int = ARROW.iconWidth + GAP + PADDING

        /** Re-resolves the file, which is what moves the cell between enabled and disabled. */
        fun refresh() {
            // Not while the run is going: the report is announced as Testo starts writing it, over the path the
            // previous run wrote to — so a check now would light the button up on a report that belongs to that run.
            val finished = reports.runFinished
            val found = if (finished) resolveReport(ref, project, mapToLocal) else null
            val changed = found != located
            located = found
            cursor = if (found == null) Cursor.getDefaultCursor() else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = when {
                found != null -> TestoBundle.message("testo.report.action.description", found.toString())
                finished -> TestoBundle.message("testo.report.action.description.pending")
                else -> TestoBundle.message("testo.report.action.description.running")
            }
            val digest = "report=${ref.path} finished=$finished resolved=$found"
            if (digest != lastLogged) {
                lastLogged = digest
                LOG.info("Testo report button: $digest")
            }
            if (changed) repaint()
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
                if (hovered && located != null) {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                }
                // Dimmed as a whole while the file is missing, so "announced" reads differently from "ready".
                val enabled = located != null
                val icon = if (enabled) ICON else disabledIcon(ICON)
                icon.paintIcon(this, g2, PADDING, (height - icon.iconHeight) / 2)

                g2.font = font
                g2.color = if (enabled) UIUtil.getLabelForeground() else DISABLED_TEXT
                val metrics = g2.fontMetrics
                val textX = PADDING + ICON.iconWidth + GAP
                g2.drawString(text(), textX, (height - metrics.height) / 2 + metrics.ascent)

                val arrow = if (enabled) ARROW else disabledIcon(ARROW)
                arrow.paintIcon(this, g2, width - PADDING - arrow.iconWidth, (height - arrow.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }

        private fun openDefault() {
            val path = located ?: return
            val label = ref.name ?: TestoBundle.message("testo.report.editor.name")
            if (!TestoReportViewer.open(project, path, label)) browseReport(path)
        }

        private fun showMenu() {
            val group = DefaultActionGroup(
                buildList {
                    if (TestoReportViewer.isAvailable) {
                        add(item("testo.report.open.webview", AllIcons.Actions.Preview, Mode.WEB_VIEW))
                    }
                    add(item("testo.report.open.browser", AllIcons.Nodes.PpWeb, Mode.BROWSER))
                    add(item("testo.report.copy.path", AllIcons.Actions.Copy, Mode.COPY_PATH))
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

        private fun item(key: String, icon: Icon, mode: Mode) =
            ReportTargetAction(TestoBundle.message(key), icon, mode, { ref }, project, mapToLocal)
    }

    private companion object {
        private const val REFRESH_MS = 500

        private val ICON: Icon = AllIcons.General.IndentDetected
        private val ARROW: Icon = AllIcons.General.LinkDropTriangle

        // Read at paint time, never cached: the scale changes with the monitor the IDE was dragged to.
        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)

        private val DISABLED_TEXT = JBColor.namedColor("Label.disabledForeground", JBColor(0x8C8C8C, 0x6F737A))

        private val LOG = logger<TestoReportsAction>()
    }
}

/** The platform's own greying, so a disabled cell matches every other disabled control in the row. */
private fun disabledIcon(icon: Icon): Icon = IconLoader.getDisabledIcon(icon)

/** What a menu entry does; [WEB_VIEW] falls back to the browser where JCEF is unavailable. */
private enum class Mode { WEB_VIEW, BROWSER, COPY_PATH }

private class ReportTargetAction(
    text: String,
    icon: Icon?,
    private val mode: Mode,
    private val target: () -> TestoReportRef,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
) : AnAction(text, null, icon), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolveReport(target(), project, mapToLocal) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ref = target()
        // Re-resolved rather than remembered: the report may have been deleted since the menu was drawn.
        val path = resolveReport(ref, project, mapToLocal) ?: return
        val label = ref.name ?: TestoBundle.message("testo.report.editor.name")
        when (mode) {
            Mode.BROWSER -> browseReport(path)
            Mode.COPY_PATH -> CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
            Mode.WEB_VIEW -> if (!TestoReportViewer.open(project, path, label)) browseReport(path)
        }
    }
}

/**
 * Hands the report to the external browser.
 *
 * Through `Path.toUri()`, not `browse(File)`: the latter goes by way of the Windows path, whose separators come out
 * percent-encoded — `file:///D:/%5Cgit%5C…`, which no browser resolves. `toUri()` yields `file:///D:/git/…`.
 */
private fun browseReport(path: Path) = BrowserUtil.browse(path.toUri())

/**
 * The announced report as a local file, or `null` while none of the candidates exists.
 *
 * Touches the filesystem. Called from the cell's timer on the EDT — three `stat`s twice a second, which is the price of
 * noticing that the file has appeared without anything announcing it.
 */
internal fun resolveReport(ref: TestoReportRef, project: Project, mapToLocal: (String) -> String?): Path? =
    // The mapper is the PHP plugin's, over a path it may know nothing about: whatever it throws must not take the
    // toolbar's update with it.
    reportPathCandidates(ref, project.basePath) { runCatching { mapToLocal(it) }.getOrNull() }
        .asSequence()
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
