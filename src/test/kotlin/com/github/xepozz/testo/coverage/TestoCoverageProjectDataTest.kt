package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.BranchCoverage
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.FileCoverage
import com.github.xepozz.testo.coverage.format.LineCoverage
import com.github.xepozz.testo.coverage.format.ParsedReport
import com.github.xepozz.testo.coverage.format.parseCoverageReport
import com.intellij.rt.coverage.data.ProjectData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/**
 * Pure tests for [toProjectData]: the platform coverage model built from a parsed report. `com.intellij.rt.coverage.data`
 * types need no IDE fixture, so line hits and the branch → jump/switch status mapping are asserted directly.
 * `LineData.getStatus()`: 0 = uncovered, 1 = partial, 2 = full.
 */
class TestoCoverageProjectDataTest {

    private val interceptor = "D:/git/testo/testo/plugin/data/src/Internal/DataProviderInterceptor.php"

    @Test
    fun buildsLineHitsFromClover() {
        val data = parseCoverageReport(Path.of("src/test/testData/coverage/clover.xml"), CoverageFormat.CLOVER).toProjectData()
        val cls = data.getClassData(interceptor)
        assertEquals(1, cls.getLineData(40).hits)
        assertEquals(0, cls.getLineData(70).hits)
        assertEquals(0, cls.getLineData(70).status)   // uncovered -> red
        assertEquals(2, cls.getLineData(40).status)   // covered, no branch -> full
    }

    @Test
    fun coberturaPartialBranchLineIsPartial() {
        val data = parseCoverageReport(Path.of("src/test/testData/coverage/cobertura.xml"), CoverageFormat.COBERTURA).toProjectData()
        val line = data.getClassData(interceptor).getLineData(68)   // condition-coverage="75% (3/4)"
        assertEquals(1, line.status)                                 // partial -> yellow
        assertEquals(4, line.branchData.totalBranches)
        assertEquals(3, line.branchData.coveredBranches)
    }

    @Test
    fun branchMappingCoversJumpsAndSwitches() {
        val report = ParsedReport(
            CoverageFormat.COBERTURA,
            listOf(
                FileCoverage(
                    "/x.php",
                    listOf(
                        LineCoverage(10, hits = 1, branch = BranchCoverage(2, 2)),   // full two-way
                        LineCoverage(11, hits = 1, branch = BranchCoverage(4, 4)),   // full n-way
                        LineCoverage(12, hits = 1, branch = BranchCoverage(1, 2)),   // partial two-way
                        LineCoverage(13, hits = 0, branch = BranchCoverage(0, 2)),   // unexecuted line
                    ),
                ),
            ),
            hasBranches = true,
            perTest = null,
        )
        val cls = report.toProjectData().getClassData("/x.php")

        assertEquals(2, cls.getLineData(10).status)
        assertEquals(BranchPair(2, 2), cls.getLineData(10).branchData.let { BranchPair(it.totalBranches, it.coveredBranches) })
        assertEquals(2, cls.getLineData(11).status)
        assertEquals(BranchPair(4, 4), cls.getLineData(11).branchData.let { BranchPair(it.totalBranches, it.coveredBranches) })
        assertEquals(1, cls.getLineData(12).status)
        assertEquals(BranchPair(2, 1), cls.getLineData(12).branchData.let { BranchPair(it.totalBranches, it.coveredBranches) })
        assertEquals(0, cls.getLineData(13).status)   // hits==0 wins over branch data -> uncovered
    }

    /**
     * Several checked reports become one bundle, and the platform merges their `ProjectData`s — line by line, into
     * fresh `LineData`. Branch data only survives that because ours is written through `fillArrays`, which is what the
     * merge reads.
     */
    @Test
    fun branchesSurviveTheMergeOfSeveralReports() {
        val clover = parseCoverageReport(Path.of("src/test/testData/coverage/clover.xml"), CoverageFormat.CLOVER)
        val cobertura = parseCoverageReport(Path.of("src/test/testData/coverage/cobertura.xml"), CoverageFormat.COBERTURA)
        val merged = ProjectData()
        merged.merge(clover.toProjectData())
        merged.merge(cobertura.toProjectData())

        val line = merged.getClassData(interceptor).getLineData(68)
        assertEquals(4, line.branchData.totalBranches)
        assertEquals(3, line.branchData.coveredBranches)
    }

    /** coverage-xml lists files it recorded no covered line for; a `ClassData` without lines makes the platform NPE. */
    @Test
    fun fileWithoutExecutableLinesGetsNoClassData() {
        val report = ParsedReport(
            CoverageFormat.COVERAGE_XML,
            listOf(FileCoverage("/empty.php", emptyList()), FileCoverage("/x.php", listOf(LineCoverage(3, hits = 1)))),
            hasBranches = false,
            perTest = null,
        )
        val data = report.toProjectData()

        assertNull(data.getClassData("/empty.php"))
        assertEquals(setOf("/x.php"), data.classes.keys)
    }

    private data class BranchPair(val total: Int, val covered: Int)
}
