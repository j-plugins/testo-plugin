package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.testsUnder
import com.intellij.coverage.CoverageBundle
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.coverage.view.ElementColumnInfo
import com.intellij.coverage.view.PercentageCoverageColumnInfo
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ColumnInfo

/**
 * The platform's file tree plus the Testo columns and toolbar. `Branches, %` is shown only for reports that carry
 * branch data (cobertura); `Tests` — distinct covering tests per file, directories as the union — only when the shown
 * bundle holds a coverage-xml suite *and* the per-test index has data, so neither column ever renders all-empty.
 */
class TestoCoverageViewExtension(
    private val project: Project,
    private val annotator: TestoCoverageAnnotator,
    suitesBundle: CoverageSuitesBundle,
) : DirectoryCoverageViewExtension(project, annotator, suitesBundle) {
    override fun createColumnInfos(): Array<ColumnInfo<*, *>> {
        val columns = mutableListOf<ColumnInfo<*, *>>(
            ElementColumnInfo(),
            PercentageCoverageColumnInfo(LINES_COLUMN, CoverageBundle.message("table.column.name.statistics"), mySuitesBundle),
        )
        if (mySuitesBundle.isBranchCoverage) {
            val name = TestoBundle.message("testo.coverage.view.column.branches")
            columns.add(PercentageCoverageColumnInfo(BRANCHES_COLUMN, name, mySuitesBundle))
        }
        if (showsTests()) columns.add(TestsColumnInfo())
        return columns.toTypedArray()
    }

    override fun getPercentage(columnIdx: Int, node: AbstractTreeNode<*>): String? {
        // Also what the view sizes a column by, off the root node — so the Tests column must answer with a count and
        // not fall through to the percentage string, which would size it for "100% (1234/1234)".
        if (columnIdx == testsColumn()) return countFor(node)?.takeIf { it > 0 }?.toString()
        if (columnIdx != BRANCHES_COLUMN) return super.getPercentage(columnIdx, node)
        val file = extractFile(node) ?: return null
        return annotator.getBranchCoverageInformationString(file, mySuitesBundle)
    }

    /**
     * Where the Tests column sits, or -1 when it is not shown. Worked out from the bundle rather than remembered from
     * [createColumnInfos]: the view builds a *separate* extension instance for its columns, its tree structure and
     * itself, so nothing one of them stores is visible to the one being asked here.
     */
    private fun testsColumn(): Int = when {
        !showsTests() -> -1
        mySuitesBundle.isBranchCoverage -> BRANCHES_COLUMN + 1
        else -> BRANCHES_COLUMN
    }

    private fun showsTests(): Boolean =
        hasPerTestData() && TestoCoverageByTestIndex.getInstance(project).data().testsByFile().isNotEmpty()

    /** Distinct covering tests of a node: the file's own set, a directory as the union over everything beneath it. */
    private fun countFor(node: NodeDescriptor<*>): Int? {
        val file = (node as? AbstractTreeNode<*>)?.let { extractFile(it) } ?: return null
        return TestoCoverageByTestIndex.getInstance(project).data().testsUnder(file.path, file.isDirectory).size
    }

    // @Experimental (not @Internal) — the one public seam into the view's toolbar; verified present on 252 and 262.
    // The tree's context menu is not a seam: `CoverageView.createPopupGroup` is private and holds `EditSource` alone,
    // so "run the covering tests of this row" is offered from the toolbar, acting on the selection.
    override fun createExtraToolbarActions(): List<AnAction> = listOf(
        com.github.xepozz.testo.tests.console.TestoTreeExpandAction(),
        com.github.xepozz.testo.tests.console.TestoTreeCollapseAction(),
        TestoSelectOpenedFileAction(project),
        TestoCoverageHighlightToggleAction(project),
        TestoCoveringTestsGutterToggleAction(project),
        TestoRunCoveringTestsAction(project),
        TestoCoverageFormatBadgesAction(mySuitesBundle),
    )

    // The index outlives coverage sessions (the code-vision lens reads it cold), so a clover-only bundle must not
    // resurface the previous run's per-test counts — the column needs a coverage-xml suite in *this* bundle.
    private fun hasPerTestData(): Boolean =
        mySuitesBundle.suites.filterIsInstance<TestoCoverageSuite>().any { it.format == CoverageFormat.COVERAGE_XML }

    private inner class TestsColumnInfo :
        ColumnInfo<NodeDescriptor<*>, String>(TestoBundle.message("testo.coverage.view.column.tests")) {
        // One row is asked for per repaint and per sort comparison, and a directory means a walk of the whole map.
        private val counts = HashMap<NodeDescriptor<*>, Int>()

        override fun valueOf(node: NodeDescriptor<*>): String? = count(node).takeIf { it > 0 }?.toString()

        override fun getComparator(): Comparator<NodeDescriptor<*>> = compareBy { count(it) }

        private fun count(node: NodeDescriptor<*>): Int = counts.getOrPut(node) { countFor(node) ?: 0 }
    }

    companion object {
        private const val LINES_COLUMN = 1
        private const val BRANCHES_COLUMN = 2
    }
}
