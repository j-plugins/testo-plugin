package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.PerTestCoverage
import com.github.xepozz.testo.coverage.format.TestId

/** A source location as the index stores it: a [TestoCoverageKeys]-normalized file key and a 1-based line. */
data class SourceRef(val fileKey: String, val line: Int)

/**
 * Read model over coverage-xml's per-test overlay — which tests touched which source lines, both directions.
 * File keys are normalized on the way in, so callers may pass raw paths.
 */
interface TestoCoverageByTestData {
    fun testsCoveringLine(fileKey: String, line: Int): Set<TestId>
    fun testsCoveringRange(fileKey: String, lines: IntRange): Set<TestId>
    fun linesOfTest(testId: TestId): Set<SourceRef>
    fun allTests(): Set<TestId>

    /** Every covered file key → the distinct tests that touched it; the substrate of the view's "Tests" column. */
    fun testsByFile(): Map<String, Set<TestId>>

    companion object {
        val EMPTY: TestoCoverageByTestData = MapCoverageByTestData(emptyMap(), emptyMap())

        fun of(perTest: PerTestCoverage?): TestoCoverageByTestData =
            if (perTest == null) EMPTY else MapCoverageByTestData.from(perTest)
    }
}

/**
 * Every test that touched [path] — the file's own set, or, for a directory, the union over everything beneath it.
 * [isDirectory] is passed rather than probed so callers holding a VFS or a PSI item both fit.
 */
fun TestoCoverageByTestData.testsUnder(path: String, isDirectory: Boolean): Set<TestId> {
    val key = TestoCoverageKeys.normalize(path)
    if (!isDirectory) return testsByFile()[key] ?: emptySet()
    val prefix = "$key/"
    return testsByFile().entries.asSequence()
        .filter { it.key.startsWith(prefix) }
        .flatMapTo(LinkedHashSet()) { it.value }
}

internal class MapCoverageByTestData(
    private val byLine: Map<SourceRef, Set<TestId>>,
    private val byTest: Map<TestId, Set<SourceRef>>,
) : TestoCoverageByTestData {

    private val byFile: Map<String, Set<TestId>> = buildMap<String, MutableSet<TestId>> {
        for ((ref, tests) in byLine) getOrPut(ref.fileKey) { LinkedHashSet() } += tests
    }

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

    override fun testsByFile(): Map<String, Set<TestId>> = byFile

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
