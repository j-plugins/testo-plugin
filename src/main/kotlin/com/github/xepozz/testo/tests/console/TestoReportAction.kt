package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.ui.TestoReportViewer
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * The report button at the right end of the test toolbar, past the run summary.
 *
 * Appears only once Testo has announced a report and the file is actually there — a run configured without the reporter
 * shows no button at all. Clicking opens it in an editor tab (JCEF); the dropdown offers the external browser, which is
 * also what the main click falls back to where JCEF is unavailable.
 */
class TestoReportAction(
    private val reports: TestoReportStore,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
) : SplitButtonAction(ReportMenu(reports, project, mapToLocal)), RightAlignedToolbarAction {

    // Icon *and* text: a toolbar button draws only its icon unless SHOW_TEXT_IN_TOOLBAR says otherwise, which is how
    // Testo's name for the report ended up in the tooltip and nowhere else. The presentation is rewritten in update()
    // once a report is known.
    private val mainAction = ReportTargetAction(
        TestoBundle.message("testo.report.action.text"),
        AllIcons.General.IndentDetected,
        Mode.DEFAULT,
        reports::primary,
        project,
        mapToLocal,
    ).apply { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }

    // Resolving a report means touching the filesystem, which must not happen on the EDT.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // The button always means the same thing, so it never redraws itself as whichever menu item ran last.
    override fun useDynamicSplitButton(): Boolean = false

    override fun getMainAction(e: AnActionEvent): AnAction = mainAction

    override fun update(e: AnActionEvent) {
        val report = reports.primary()
        val located = report?.let { resolveReport(it, project, mapToLocal) }
        logStateOnce(e.place, located)
        super.update(e)
        // Visible even with no report to open, and merely disabled: RunTab snapshots the toolbar's actions, so a button
        // hidden at that moment — which is every moment before the run ends — never gets a component at all.
        e.presentation.isVisible = true
        e.presentation.isEnabled = located != null
        // Testo's own name for the report, so the button says what it opens; the bundle only covers the empty case.
        e.presentation.text = report?.name ?: TestoBundle.message("testo.report.action.text")
        e.presentation.icon = AllIcons.General.IndentDetected
        // Without this the row shows the icon alone and the text survives only as a tooltip.
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        e.presentation.description = when (located) {
            null -> TestoBundle.message("testo.report.action.description.none")
            else -> TestoBundle.message("testo.report.action.description", located.toString())
        }
    }

    /** What the last update saw, so `update` firing several times a second logs one line per real change. */
    private var lastLogged: String? = null

    private fun logStateOnce(place: String, located: Path?) {
        val announced = reports.all()
        val primary = reports.primary()
        val candidates = primary?.let { reportPathCandidates(it, project.basePath, mapToLocal) }.orEmpty()
        val digest = "place=$place announced=${announced.size} primary=${primary?.path} " +
            "candidates=$candidates resolved=$located"
        if (digest == lastLogged) return
        lastLogged = digest
        LOG.info("Testo report button: $digest")
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.logger<TestoReportAction>()
    }
}

/** What a click does; [DEFAULT] is the WebView with the browser as its fallback. */
private enum class Mode { DEFAULT, WEB_VIEW, BROWSER, COPY_PATH }

/** The dropdown: the other ways to open the primary report, plus one entry per extra report when a run wrote several. */
private class ReportMenu(
    private val reports: TestoReportStore,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
) : ActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val primary = reports.primary()
        return buildList {
            if (TestoReportViewer.isAvailable) {
                add(item("testo.report.open.webview", AllIcons.Actions.Preview, Mode.WEB_VIEW) { primary })
            }
            add(item("testo.report.open.browser", AllIcons.Nodes.PpWeb, Mode.BROWSER) { primary })
            add(item("testo.report.copy.path", AllIcons.Actions.Copy, Mode.COPY_PATH) { primary })
            // Viewable only, and only when there is a choice to make: a data document or a coverage report is announced
            // too, and neither belongs behind "open this page".
            reports.viewable().filter { it != primary }.forEach { ref ->
                add(
                    ReportTargetAction(
                        ref.name ?: ref.format.uppercase(),
                        AllIcons.General.IndentDetected,
                        Mode.DEFAULT,
                        { ref },
                        project,
                        mapToLocal,
                    )
                )
            }
        }.toTypedArray()
    }

    private fun item(key: String, icon: Icon, mode: Mode, target: () -> TestoReportRef?) =
        ReportTargetAction(TestoBundle.message(key), icon, mode, target, project, mapToLocal)
}

private class ReportTargetAction(
    text: String,
    icon: Icon?,
    private val mode: Mode,
    private val target: () -> TestoReportRef?,
    private val project: Project,
    private val mapToLocal: (String) -> String?,
) : AnAction(text, null, icon), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target()?.let { resolveReport(it, project, mapToLocal) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ref = target() ?: return
        val path = resolveReport(ref, project, mapToLocal) ?: return
        val label = ref.name ?: TestoBundle.message("testo.report.editor.name")
        when (mode) {
            Mode.BROWSER -> browseReport(path)
            Mode.COPY_PATH -> CopyPasteManager.getInstance().setContents(StringSelection(path.toString()))
            // Without JCEF a tab could only say as much, so the report opens where it can actually be read.
            Mode.WEB_VIEW, Mode.DEFAULT ->
                if (!TestoReportViewer.open(project, path, label)) browseReport(path)
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
 * Touches the filesystem — callers must be off the EDT.
 */
internal fun resolveReport(ref: TestoReportRef, project: Project, mapToLocal: (String) -> String?): Path? =
    // The mapper is the PHP plugin's, over a path it may know nothing about: whatever it throws must not take the
    // toolbar's update with it.
    reportPathCandidates(ref, project.basePath) { runCatching { mapToLocal(it) }.getOrNull() }
        .asSequence()
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
