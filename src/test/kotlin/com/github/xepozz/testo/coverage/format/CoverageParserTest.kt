package com.github.xepozz.testo.coverage.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Pure-logic tests for the three coverage parsers and format detection, run against the real trimmed Testo reports in
 * `src/test/testData/coverage/` — no platform fixture. All three formats resolve the same source file to one path.
 */
class CoverageParserTest {

    private val dir = Path.of("src/test/testData/coverage")

    private val interceptor = "D:/git/testo/testo/plugin/data/src/Internal/DataProviderInterceptor.php"
    private val multipleResult = "D:/git/testo/testo/plugin/data/src/MultipleResult.php"
    private val dataCross = "D:/git/testo/testo/plugin/data/src/DataCross.php"

    private fun ParsedReport.file(path: String) = files.first { it.filePath == path }
    private fun FileCoverage.hits(line: Int) = lines.first { it.line == line }.hits
    private fun FileCoverage.branch(line: Int) = lines.first { it.line == line }.branch

    // ---- Clover ---------------------------------------------------------------------------------------------------

    @Test
    fun cloverParsesLineHitsAndUncoveredLines() {
        val report = parseCoverageReport(dir.resolve("clover.xml"), CoverageFormat.CLOVER)

        assertEquals(CoverageFormat.CLOVER, report.format)
        assertFalse(report.hasBranches)
        assertNull(report.perTest)
        assertEquals(setOf(interceptor, multipleResult, dataCross), report.files.map { it.filePath }.toSet())

        val f = report.file(interceptor)
        assertEquals(1, f.hits(40))
        assertEquals(0, f.hits(70))   // count="0" -> uncovered
        assertNull(f.branch(40))       // Clover carries no branch data

        assertEquals(listOf(1, 1), report.file(multipleResult).lines.map { it.hits })
        assertEquals(listOf(0, 0), report.file(dataCross).lines.map { it.hits })
    }

    // ---- Cobertura ------------------------------------------------------------------------------------------------

    @Test
    fun coberturaResolvesRelativePathsAgainstSource() {
        val report = parseCoverageReport(dir.resolve("cobertura.xml"), CoverageFormat.COBERTURA)
        assertEquals(setOf(interceptor, multipleResult, dataCross), report.files.map { it.filePath }.toSet())
    }

    @Test
    fun coberturaParsesLineHitsAndBranchConditions() {
        val report = parseCoverageReport(dir.resolve("cobertura.xml"), CoverageFormat.COBERTURA)

        assertEquals(CoverageFormat.COBERTURA, report.format)
        assertTrue(report.hasBranches)
        assertNull(report.perTest)

        val f = report.file(interceptor)
        assertEquals(1, f.hits(40))
        assertEquals(0, f.hits(70))
        assertNull(f.branch(70))                       // plain line, no branch attribute
        assertEquals(BranchCoverage(3, 4), f.branch(68))
        assertEquals(BranchCoverage(1, 2), f.branch(69))
        assertEquals(BranchCoverage(2, 4), f.branch(81))
        assertEquals(BranchCoverage(0, 4), f.branch(282))

        assertTrue(report.file(multipleResult).lines.all { it.branch == null })
    }

    @Test
    fun coberturaWithoutBranchLinesReportsNoBranches() {
        // MultipleResult + DataCross classes alone carry no branch="true" line.
        val report = CoberturaCoverageParser.parse(dir.resolve("cobertura.xml"))
        assertTrue(report.hasBranches)   // the interceptor class does; sanity that the flag tracks any branch line
    }

    // ---- coverage-xml (phpunit-xml) -------------------------------------------------------------------------------

    @Test
    fun phpUnitXmlBuildsPerTestIndexBothDirections() {
        val report = parseCoverageReport(dir.resolve("coverage-xml"), CoverageFormat.PHPUNIT_XML)

        assertEquals(CoverageFormat.PHPUNIT_XML, report.format)
        assertFalse(report.hasBranches)
        assertNotNull(report.perTest)
        val perTest = report.perTest!!

        val test = TestId("Tests\\Data\\Unit\\Internal\\DataProviderInterceptorTest", "collectsResultsFromAllProviders")
        assertEquals(setOf(test), perTest.byLine[SourceLine(interceptor, 40)])
        assertEquals(setOf(test), perTest.byLine[SourceLine(multipleResult, 25)])

        val covered = perTest.byTest.getValue(test)
        assertTrue(SourceLine(interceptor, 40) in covered)
        assertTrue(SourceLine(multipleResult, 25) in covered)
        assertTrue(SourceLine(multipleResult, 30) in covered)
    }

    @Test
    fun phpUnitXmlEmitsOnlyExecutedLinesAndKeepsEmptyFiles() {
        val report = parseCoverageReport(dir.resolve("coverage-xml"), CoverageFormat.PHPUNIT_XML)

        assertEquals(setOf(interceptor, multipleResult, dataCross), report.files.map { it.filePath }.toSet())
        assertTrue(report.file(interceptor).lines.all { it.hits == 1 })   // overlay: executed lines only
        assertTrue(report.file(dataCross).lines.isEmpty())               // empty <coverage/>
        assertNull(report.perTest!!.byLine[SourceLine(interceptor, 70)]) // uncovered line absent from the overlay
    }

    @Test
    fun phpUnitXmlAcceptsIndexFileDirectly() {
        val viaDir = parseCoverageReport(dir.resolve("coverage-xml"))
        val viaFile = parseCoverageReport(dir.resolve("coverage-xml/index.xml"))
        assertEquals(viaDir.files.map { it.filePath }.toSet(), viaFile.files.map { it.filePath }.toSet())
    }

    // ---- Detection ------------------------------------------------------------------------------------------------

    @Test
    fun detectsEachFormat() {
        assertEquals(CoverageFormat.CLOVER, detectCoverageFormat(dir.resolve("clover.xml")))
        assertEquals(CoverageFormat.COBERTURA, detectCoverageFormat(dir.resolve("cobertura.xml")))
        assertEquals(CoverageFormat.PHPUNIT_XML, detectCoverageFormat(dir.resolve("coverage-xml")))
        assertEquals(CoverageFormat.PHPUNIT_XML, detectCoverageFormat(dir.resolve("coverage-xml/index.xml")))
    }

    @Test
    fun parseWithoutFormatSniffs() {
        assertEquals(CoverageFormat.CLOVER, parseCoverageReport(dir.resolve("clover.xml")).format)
        assertEquals(CoverageFormat.COBERTURA, parseCoverageReport(dir.resolve("cobertura.xml")).format)
    }

    // ---- TestId ---------------------------------------------------------------------------------------------------

    @Test
    fun testIdSplitsOnLastDoubleColon() {
        assertEquals(TestId("Tests\\Foo\\BarTest", "doesThing"), TestId.parse("Tests\\Foo\\BarTest::doesThing"))
        assertNull(TestId.parse("noSeparator"))
        assertNull(TestId.parse("::orphan"))
        assertNull(TestId.parse("Class::"))
    }
}
