package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.testsUnder
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.coverage.view.ElementColumnInfo
import com.intellij.coverage.view.PercentageCoverageColumnInfo
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ColumnInfo

/** Covered out of total, in whatever unit the column counts. */
data class CoverageTally(val covered: Int, val total: Int)

/** A node's numbers in the Coverage view; see [TestoCoverageAnnotator.coverageOf] for when each is null. */
data class NodeCoverage(val files: CoverageTally?, val lines: CoverageTally?, val branches: CoverageTally?)

/**
 * A cell: `85% (17/20)`, the shape the platform's `PercentageParser` sorts by. Floored, so only a complete tally reads
 * 100%; null for an empty one.
 */
fun CoverageTally.cellText(): String? =
    if (total <= 0) null else "${covered.coerceIn(0, total).toLong() * 100 / total}% ($covered/$total)"

/**
 * The platform's file tree plus the Testo columns and toolbar: `Files` (filled on directories only), `Lines`,
 * `Branches` only for reports that carry branch data (cobertura, clover), and `Tests` — distinct covering tests per
 * file, directories as the union — only when the shown bundle holds a coverage-xml suite *and* the per-test index has
 * data, so neither optional column ever renders all-empty.
 */
class TestoCoverageViewExtension(
    private val project: Project,
    private val annotator: TestoCoverageAnnotator,
    suitesBundle: CoverageSuitesBundle,
) : DirectoryCoverageViewExtension(project, annotator, suitesBundle) {
    private enum class Metric(val titleKey: String, val of: (NodeCoverage) -> CoverageTally?) {
        FILES("testo.coverage.view.column.files", NodeCoverage::files),
        LINES("testo.coverage.view.column.lines", NodeCoverage::lines),
        BRANCHES("testo.coverage.view.column.branches", NodeCoverage::branches),
    }

    /**
     * The metric columns in order, from column 1. Worked out from the bundle rather than remembered from
     * [createColumnInfos]: the view builds a *separate* extension instance for its columns, its tree structure and
     * itself, so nothing one of them stores is visible to the one asked in [getPercentage].
     */
    private fun metrics(): List<Metric> =
        listOfNotNull(Metric.FILES, Metric.LINES, Metric.BRANCHES.takeIf { mySuitesBundle.isBranchCoverage })

    override fun createColumnInfos(): Array<ColumnInfo<*, *>> {
        val columns = mutableListOf<ColumnInfo<*, *>>(ElementColumnInfo())
        metrics().forEachIndexed { i, metric ->
            columns.add(PercentageCoverageColumnInfo(i + 1, TestoBundle.message(metric.titleKey), mySuitesBundle))
        }
        if (showsTests()) columns.add(TestsColumnInfo())
        return columns.toTypedArray()
    }

    override fun getPercentage(columnIdx: Int, node: AbstractTreeNode<*>): String? {
        // Also what the view sizes a column by, off the root node — so the Tests column must answer with a count and
        // not a percentage string, which would size it for "100% (1234/1234)".
        if (columnIdx == testsColumn()) return countFor(node)?.takeIf { it > 0 }?.toString()
        val metric = metrics().getOrNull(columnIdx - 1) ?: return null
        val file = extractFile(node) ?: return null
        return annotator.coverageOf(file, mySuitesBundle)?.let(metric.of)?.cellText()
    }

    /** Where the Tests column sits, or -1 when it is not shown. */
    private fun testsColumn(): Int = if (showsTests()) metrics().size + 1 else -1

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
}
