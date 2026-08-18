package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.format.parseCoverageReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Pure tests for the per-test read model and the identity mapper's selector — the parts that need no PSI. Built from
 * the real coverage-xml fixture; `resolve`/`toLocationHint` (which go through PhpIndex) are covered separately.
 */
class TestoPerTestCoverageTest {

    private val interceptor = "D:/git/testo/testo/plugin/data/src/Internal/DataProviderInterceptor.php"
    private val multipleResult = "D:/git/testo/testo/plugin/data/src/MultipleResult.php"
    private val test = TestId("Tests\\Data\\Unit\\Internal\\DataProviderInterceptorTest", "collectsResultsFromAllProviders")

    private fun data() = TestoCoverageByTestData.of(
        parseCoverageReport(Path.of("src/test/testData/coverage/coverage-xml"), CoverageFormat.COVERAGE_XML).perTest,
    )

    @Test
    fun testsCoveringLineAndRange() {
        val data = data()
        assertEquals(setOf(test), data.testsCoveringLine(interceptor, 40))
        assertEquals(emptySet<TestId>(), data.testsCoveringLine(interceptor, 70))   // uncovered -> not in overlay
        assertTrue(test in data.testsCoveringRange(interceptor, 30..60))
        assertTrue(test in data.testsCoveringRange(multipleResult, 25..30))
        assertEquals(emptySet<TestId>(), data.testsCoveringRange(interceptor, 300..320))
    }

    @Test
    fun linesOfTestAndAllTests() {
        val data = data()
        assertEquals(setOf(test), data.allTests())
        val lines = data.linesOfTest(test)
        assertTrue(SourceRef(TestoCoverageKeys.normalize(interceptor), 40) in lines)
        assertTrue(SourceRef(TestoCoverageKeys.normalize(multipleResult), 25) in lines)
    }

    @Test
    fun lookupIgnoresPathSpellingAndSlashes() {
        val data = data()
        // A backslash spelling normalizes to the same key.
        assertEquals(setOf(test), data.testsCoveringLine(interceptor.replace('/', '\\'), 40))
    }

    @Test
    fun emptyDataForNullOverlay() {
        assertEquals(emptySet<TestId>(), TestoCoverageByTestData.of(null).allTests())
    }

    @Test
    fun testsByFileGroupsDistinctTestsUnderNormalizedKeys() {
        val byFile = data().testsByFile()
        assertEquals(setOf(test), byFile[TestoCoverageKeys.normalize(interceptor)])
        assertEquals(setOf(test), byFile[TestoCoverageKeys.normalize(multipleResult)])
        assertTrue(TestoCoverageByTestData.of(null).testsByFile().isEmpty())
    }

    @Test
    fun testsUnderTakesAFileAloneAndADirectoryWhole() {
        val data = data()
        assertEquals(setOf(test), data.testsUnder(interceptor, isDirectory = false))
        // A directory is the union over everything beneath it, however deep — and its own key is not a file key.
        assertEquals(setOf(test), data.testsUnder("D:/git/testo/testo/plugin/data", isDirectory = true))
        assertEquals(emptySet<TestId>(), data.testsUnder("D:/git/testo/testo/plugin/data", isDirectory = false))
        assertEquals(emptySet<TestId>(), data.testsUnder("D:/git/testo/testo/plugin/other", isDirectory = true))
        // A prefix that is not a path segment must not match: ".../data" may not swallow ".../database".
        assertEquals(emptySet<TestId>(), data.testsUnder("D:/git/testo/testo/plugin/dat", isDirectory = true))
    }

    @Test
    fun filterSelectorHasLeadingBackslashOnce() {
        val mapper = TestoTestIdentityMapper.getInstance()
        assertEquals(
            "\\Tests\\Data\\Unit\\Internal\\DataProviderInterceptorTest::collectsResultsFromAllProviders",
            mapper.toFilterSelector(test),
        )
        assertEquals("\\Already\\Prefixed::m", mapper.toFilterSelector(TestId("\\Already\\Prefixed", "m")))
    }

    @Test
    fun keysNormalizeSlashesAndAreIdempotent() {
        val once = TestoCoverageKeys.normalize("D:\\git\\Testo\\Foo.php")
        assertEquals(once, TestoCoverageKeys.normalize(once))
        assertTrue('\\' !in once)
    }
}
