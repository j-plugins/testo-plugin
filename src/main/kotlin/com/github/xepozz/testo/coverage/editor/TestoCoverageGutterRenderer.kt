package com.github.xepozz.testo.coverage.editor

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TEST_ID_ORDER
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.TestoCoveringTestsLauncher
import com.github.xepozz.testo.coverage.perTest.TestoTestIdentityMapper
import com.github.xepozz.testo.coverage.perTest.shortTestLabel
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.pom.Navigatable
import com.intellij.rt.coverage.data.LineData
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The coverage stripe in the editor gutter, drawn by [TestoCoverageEditorHighlighter] — a public-API stand-in for the
 * platform's `CoverageLineMarkerRenderer` (`@ApiStatus.Internal`). Same geometry: `Position.LEFT`, the
 * stripe filled with the standard coverage colour keys, so the user's Colors & Fonts settings apply unchanged.
 *
 * A click inside the line-marker area pops the line's story: coverage status, hit count, branch tally (Cobertura),
 * and — when the per-test index holds the line — the covering tests, navigable like the code-vision lens.
 */
internal class TestoCoverageGutterRenderer(
    private val project: Project,
    private val filePath: String,
    private val lineData: LineData,
) : ActiveGutterRenderer, LineMarkerRendererEx {

    val attributesKey: TextAttributesKey = when (lineData.status) {
        FULL -> CodeInsightColors.LINE_FULL_COVERAGE
        PARTIAL -> CodeInsightColors.LINE_PARTIAL_COVERAGE
        else -> CodeInsightColors.LINE_NONE_COVERAGE
    }

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val attributes = editor.colorsScheme.getAttributes(attributesKey)
        val color = attributes.backgroundColor ?: attributes.foregroundColor ?: return
        g.color = color
        g.fillRect(r.x, r.y, minOf(r.width, JBUI.scale(STRIPE_WIDTH)), r.height)
    }

    // The gutter dispatches clicks by Y alone; the X window is on the renderer (same bounds the platform stripe uses).
    override fun canDoAction(e: MouseEvent): Boolean {
        val gutter = e.component as? EditorGutterComponentEx ?: return false
        return e.x > gutter.lineMarkerAreaOffset && e.x < gutter.iconAreaOffset
    }

    override fun doAction(editor: Editor, e: MouseEvent) {
        e.consume()
        showPopup(RelativePoint(e))
    }

    // The platform coverage popup's shape: one metric per row, the covering tests right below.
    private fun showPopup(at: RelativePoint) {
        val header = JPanel(VerticalLayout(JBUI.scale(2)))
        header.border = JBUI.Borders.empty(6, 10)
        header.add(JBLabel(TestoBundle.message("testo.coverage.editor.popup.hits", lineData.hits)))
        lineData.branchData?.let {
            header.add(JBLabel(TestoBundle.message("testo.coverage.editor.popup.branches", it.coveredBranches, it.totalBranches)))
        }

        val tests = coveringTests()
        if (tests.isEmpty()) {
            JBPopupFactory.getInstance().createComponentPopupBuilder(header, null).createPopup().show(at)
            return
        }

        header.add(JBLabel(TestoBundle.message("testo.coverage.editor.popup.tests", tests.size)))
        val list = JBList(tests)
        list.cellRenderer = SimpleListCellRenderer.create<TestId>("") { shortTestLabel(it) }
        list.selectedIndex = 0
        list.visibleRowCount = minOf(tests.size, 8)
        // A plain JBList tracks the keyboard only; the row under the pointer is highlighted by the list wrappers the
        // platform's own chooser popups are built from, which a hand-assembled panel does not go through.
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                list.locationToIndex(e.point).takeIf { it >= 0 }?.let { list.selectedIndex = it }
            }
        })

        val runAll = JButton(
            TestoBundle.message("testo.coverage.editor.popup.run.all", tests.size),
            AllIcons.Toolwindows.ToolWindowRunWithCoverage,
        )

        val panel = JPanel(BorderLayout())
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(4))).apply {
            isOpaque = false
            add(runAll)
        }, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setRequestFocus(true)
            .createPopup()

        val mapper = TestoTestIdentityMapper.getInstance()
        val navigateSelected = {
            val id = list.selectedValue
            if (id != null) {
                popup.cancel()
                (mapper.resolve(id, project) as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) navigateSelected()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigateSelected()
            }
        })
        runAll.addActionListener {
            popup.cancel()
            TestoCoveringTestsLauncher.run(
                project,
                tests,
                TestoCoveringTestsLauncher.runName(lineSubject(), tests.size),
            )
        }
        popup.show(at)
    }

    override fun getTooltipText(): String = statusText()

    override fun getAccessibleName(): String = TestoBundle.message("testo.coverage.editor.accessible.name")

    /** How the run names itself: `Foo.php:42`, the line the tests were read off. */
    private fun lineSubject(): String = "${filePath.substringAfterLast('/')}:${lineData.lineNumber}"

    private fun coveringTests(): List<TestId> =
        TestoCoverageByTestIndex.getInstance(project).data()
            .testsCoveringLine(filePath, lineData.lineNumber)
            .sortedWith(TEST_ID_ORDER)

    private fun statusText(): String = coverageLineStatusText(lineData)

    companion object {
        private const val STRIPE_WIDTH = 8
    }
}

// com.intellij.rt.coverage.data.LineCoverage constants, spelled as the Int LineData.getStatus() answers.
private const val PARTIAL = 1
private const val FULL = 2

/** The popup/tooltip headline: status, then hits and the branch tally when the line carries them. */
internal fun coverageLineStatusText(lineData: LineData): String {
    val parts = mutableListOf(
        when (lineData.status) {
            FULL -> TestoBundle.message("testo.coverage.editor.status.full")
            PARTIAL -> TestoBundle.message("testo.coverage.editor.status.partial")
            else -> TestoBundle.message("testo.coverage.editor.status.none")
        }
    )
    if (lineData.hits > 1) parts += TestoBundle.message("testo.coverage.editor.status.hits", lineData.hits)
    lineData.branchData?.let { parts += TestoBundle.message("testo.coverage.editor.status.branches", it.coveredBranches, it.totalBranches) }
    return parts.joinToString(", ")
}
