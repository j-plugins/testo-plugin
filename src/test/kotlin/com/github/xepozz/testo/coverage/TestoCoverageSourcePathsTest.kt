package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.FileCoverage
import com.github.xepozz.testo.coverage.format.LineCoverage
import com.github.xepozz.testo.coverage.format.ParsedReport
import com.github.xepozz.testo.coverage.format.PerTestCoverage
import com.github.xepozz.testo.coverage.format.SourceLine
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.format.mapPaths
import junit.framework.TestCase

class TestoCoverageSourcePathsTest : TestCase() {

    private val existing = setOf("D:/proj/src/Foo.php", "/app/local-too.php")
    private val exists = { path: String -> path in existing }
    private val container = { path: String -> path.replaceFirst("/app/", "D:/proj/") }
    private val unrelated = { path: String -> path.replaceFirst("/srv/", "E:/other/") }

    fun testContainerPathMapsToTheLocalFile() {
        assertEquals("D:/proj/src/Foo.php", resolveCoverageSourcePath("/app/src/Foo.php", listOf(container), exists))
    }

    fun testFirstMappingThatLandsOnAFileWins() {
        assertEquals("D:/proj/src/Foo.php", resolveCoverageSourcePath("/app/src/Foo.php", listOf(unrelated, container), exists))
    }

    fun testExistingPathIsKeptAsIs() {
        assertEquals("/app/local-too.php", resolveCoverageSourcePath("/app/local-too.php", listOf(container), exists))
    }

    fun testUnresolvablePathStaysAsItCame() {
        assertEquals("/app/src/Gone.php", resolveCoverageSourcePath("/app/src/Gone.php", listOf(container), exists))
    }

    fun testThrowingMappingIsSkipped() {
        val broken = { _: String -> error("no mapping") }
        assertEquals("D:/proj/src/Foo.php", resolveCoverageSourcePath("/app/src/Foo.php", listOf(broken, container), exists))
    }

    fun testMapPathsRewritesFilesAndPerTestLines() {
        val test = TestId("\\App\\FooTest", "testIt")
        val line = SourceLine("/app/src/Foo.php", 3)
        val report = ParsedReport(
            CoverageFormat.COVERAGE_XML,
            listOf(FileCoverage("/app/src/Foo.php", listOf(LineCoverage(3, 1)))),
            false,
            PerTestCoverage(mapOf(test to setOf(line)), mapOf(line to setOf(test))),
        )

        val mapped = report.mapPaths(container)

        val local = SourceLine("D:/proj/src/Foo.php", 3)
        assertEquals(listOf("D:/proj/src/Foo.php"), mapped.files.map { it.filePath })
        assertEquals(setOf(local), mapped.perTest!!.byTest[test])
        assertEquals(setOf(test), mapped.perTest!!.byLine[local])
    }
}
