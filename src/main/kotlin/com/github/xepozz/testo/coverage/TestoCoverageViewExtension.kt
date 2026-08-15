package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.intellij.coverage.CoverageBundle
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.coverage.view.ElementColumnInfo
import com.intellij.coverage.view.PercentageCoverageColumnInfo
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ColumnInfo

/**
 * The platform's file tree plus a branch column, shown only for reports that carry branch data — clover records
 * `truecount`/`falsecount` per condition, cobertura a `condition-coverage` ratio, and neither is present in a
 * line-only report, where the column would read empty for every row.
 */
class TestoCoverageViewExtension(
    project: Project,
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
        return columns.toTypedArray()
    }

    override fun getPercentage(columnIdx: Int, node: AbstractTreeNode<*>): String? {
        if (columnIdx != BRANCHES_COLUMN) return super.getPercentage(columnIdx, node)
        val file = extractFile(node) ?: return null
        return annotator.getBranchCoverageInformationString(file, mySuitesBundle)
    }

    companion object {
        private const val LINES_COLUMN = 1
        private const val BRANCHES_COLUMN = 2
    }
}
