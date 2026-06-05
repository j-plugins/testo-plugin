package com.github.xepozz.testo.coverage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [TestoCoverageProgramRunner.createCoverageArguments] — the CLI-flag mapping that decides between
 * `--coverage-clover=<path>` and the bare `--coverage`. The override is protected, so the test extends the runner to
 * reach it (no platform state is needed to call the pure String? -> List<String> mapping). Also pins the public
 * runner/executor id constants referenced by plugin.xml.
 */
class TestoCoverageArgumentsTest : TestoCoverageProgramRunner() {

    @Test
    fun nonEmptyPathProducesCloverFlag() {
        assertEquals(listOf("--coverage-clover=/tmp/report@cfg.xml"), createCoverageArguments("/tmp/report@cfg.xml"))
    }

    @Test
    fun nullPathFallsBackToBareCoverage() {
        assertEquals(listOf("--coverage"), createCoverageArguments(null))
    }

    @Test
    fun emptyPathFallsBackToBareCoverage() {
        assertEquals(listOf("--coverage"), createCoverageArguments(""))
    }

    @Test
    fun pathWithSpacesIsKeptVerbatimInSingleArgument() {
        val args = createCoverageArguments("/path with space/r.xml")
        assertEquals(1, args.size)
        assertEquals("--coverage-clover=/path with space/r.xml", args[0])
    }

    @Test
    fun executorIdIsCoverage() {
        assertEquals("Coverage", EXECUTOR_ID)
    }

    @Test
    fun runnerIdIsStable() {
        assertEquals("TestoCoverageRunner", RUNNER_ID)
        assertEquals(RUNNER_ID, runnerId)
    }
}
