package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.PerTestCoverage
import com.github.xepozz.testo.coverage.format.TestId

/** A source location as the index stores it: a [TestoCoverageKeys]-normalized file key and a 1-based line. */
data class SourceRef(val fileKey: String, val line: Int)

/**
 * Read model over coverage-xml's per-test overlay — which tests touched which source lines, both directions. The
 * substrate for "how many tests cover this" (arch §9) and the TIA seam (§8). File keys are normalized on the way in,
 * so callers may pass raw paths.
 */
interface TestoCoverageByTestData {
    fun testsCoveringLine(fileKey: String, line: Int): Set<TestId>
    fun testsCoveringRange(fileKey: String, lines: IntRange): Set<TestId>
    fun linesOfTest(testId: TestId): Set<SourceRef>
    fun allTests(): Set<TestId>

    companion object {
        val EMPTY: TestoCoverageByTestData = MapCoverageByTestData(emptyMap(), emptyMap())

        fun of(perTest: PerTestCoverage?): TestoCoverageByTestData =
            if (perTest == null) EMPTY else MapCoverageByTestData.from(perTest)
    }
}

internal class MapCoverageByTestData(
    private val byLine: Map<SourceRef, Set<TestId>>,
    private val byTest: Map<TestId, Set<SourceRef>>,
) : TestoCoverageByTestData {

    override fun testsCoveringLine(fileKey: String, line: Int): Set<TestId> =
        byLine[SourceRef(TestoCoverageKeys.normalize(fileKey), line)] ?: emptySet()

    override fun testsCoveringRange(fileKey: String, lines: IntRange): Set<TestId> {
        val key = TestoCoverageKeys.normalize(fileKey)
        val tests = LinkedHashSet<TestId>()
        for (line in lines) byLine[SourceRef(key, line)]?.let { tests += it }
        return tests
    }

    override fun linesOfTest(testId: TestId): Set<SourceRef> = byTest[testId] ?: emptySet()

    override fun allTests(): Set<TestId> = byTest.keys

    companion object {
        fun from(perTest: PerTestCoverage): MapCoverageByTestData {
            val byLine = HashMap<SourceRef, Set<TestId>>()
            for ((sourceLine, tests) in perTest.byLine) {
                byLine[SourceRef(TestoCoverageKeys.normalize(sourceLine.filePath), sourceLine.line)] = tests
            }
            val byTest = perTest.byTest.mapValues { (_, lines) ->
                lines.mapTo(LinkedHashSet()) { SourceRef(TestoCoverageKeys.normalize(it.filePath), it.line) }
            }
            return MapCoverageByTestData(byLine, byTest)
        }
    }
}
