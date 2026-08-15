package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.TestoCoverageKeys
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
        if (hasPerTestData()) {
            val testsByFile = TestoCoverageByTestIndex.getInstance(project).data().testsByFile()
            if (testsByFile.isNotEmpty()) columns.add(TestsColumnInfo(testsByFile))
        }
        return columns.toTypedArray()
    }

    override fun getPercentage(columnIdx: Int, node: AbstractTreeNode<*>): String? {
        if (columnIdx != BRANCHES_COLUMN) return super.getPercentage(columnIdx, node)
        val file = extractFile(node) ?: return null
        return annotator.getBranchCoverageInformationString(file, mySuitesBundle)
    }

    // @Experimental (not @Internal) — the one public seam into the view's toolbar; verified present on 252 and 262.
    override fun createExtraToolbarActions(): List<AnAction> = listOf(
        com.github.xepozz.testo.tests.console.TestoTreeExpandAction(),
        com.github.xepozz.testo.tests.console.TestoTreeCollapseAction(),
        TestoCoverageHighlightToggleAction(project),
        TestoCoverageFormatBadgesAction(mySuitesBundle),
    )

    // The index outlives coverage sessions (the code-vision lens reads it cold), so a clover-only bundle must not
    // resurface the previous run's per-test counts — the column needs a coverage-xml suite in *this* bundle.
    private fun hasPerTestData(): Boolean =
        mySuitesBundle.suites.filterIsInstance<TestoCoverageSuite>().any { it.format == CoverageFormat.COVERAGE_XML }

    /** Distinct covering tests: the file's own set, a directory as the union over the files beneath it. */
    private inner class TestsColumnInfo(
        private val testsByFile: Map<String, Set<TestId>>,
    ) : ColumnInfo<NodeDescriptor<*>, String>(TestoBundle.message("testo.coverage.view.column.tests")) {
        // One directory is asked for per visible row and per sort comparison — the union walk runs once per path.
        private val dirCounts = HashMap<String, Int>()

        override fun valueOf(node: NodeDescriptor<*>): String? = countFor(node)?.takeIf { it > 0 }?.toString()

        override fun getComparator(): Comparator<NodeDescriptor<*>> = compareBy { countFor(it) ?: -1 }

        private fun countFor(node: NodeDescriptor<*>): Int? {
            val file = (node as? AbstractTreeNode<*>)?.let { extractFile(it) } ?: return null
            val key = TestoCoverageKeys.normalize(file.path)
            if (!file.isDirectory) return testsByFile[key]?.size
            return dirCounts.getOrPut(key) {
                val prefix = "$key/"
                testsByFile.entries.asSequence()
                    .filter { it.key.startsWith(prefix) }
                    .flatMapTo(HashSet()) { it.value }
                    .size
            }
        }
    }

    companion object {
        private const val LINES_COLUMN = 1
        private const val BRANCHES_COLUMN = 2
    }
}
