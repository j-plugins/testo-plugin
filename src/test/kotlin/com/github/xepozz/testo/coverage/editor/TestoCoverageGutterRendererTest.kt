package com.github.xepozz.testo.coverage.editor

import com.github.xepozz.testo.coverage.format.BranchCoverage
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.FileCoverage
import com.github.xepozz.testo.coverage.format.LineCoverage
import com.github.xepozz.testo.coverage.format.ParsedReport
import com.github.xepozz.testo.coverage.toProjectData
import com.intellij.rt.coverage.data.LineData
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the gutter popup/tooltip headline, over [LineData] built the way the runner builds it. */
class TestoCoverageGutterRendererTest {

    private fun lineData(vararg lines: LineCoverage): Map<Int, LineData> {
        val report = ParsedReport(CoverageFormat.COBERTURA, listOf(FileCoverage("/x.php", lines.toList())), true, null)
        val cls = report.toProjectData().getClassData("/x.php")
        return lines.associate { it.line to cls.getLineData(it.line) }
    }

    @Test
    fun statusAlone() {
        val lines = lineData(LineCoverage(1, hits = 1), LineCoverage(2, hits = 0))
        assertEquals("Line covered", coverageLineStatusText(lines.getValue(1)))
        assertEquals("Line not covered", coverageLineStatusText(lines.getValue(2)))
    }

    @Test
    fun hitsAppendedWhenAboveOne() {
        val lines = lineData(LineCoverage(1, hits = 5))
        assertEquals("Line covered, 5 hits", coverageLineStatusText(lines.getValue(1)))
    }

    @Test
    fun branchTallyAppended() {
        val lines = lineData(
            LineCoverage(1, hits = 1, branch = BranchCoverage(1, 2)),
            LineCoverage(2, hits = 1, branch = BranchCoverage(2, 2)),
        )
        assertEquals("Line partially covered, branches 1/2", coverageLineStatusText(lines.getValue(1)))
        assertEquals("Line covered, branches 2/2", coverageLineStatusText(lines.getValue(2)))
    }
}
