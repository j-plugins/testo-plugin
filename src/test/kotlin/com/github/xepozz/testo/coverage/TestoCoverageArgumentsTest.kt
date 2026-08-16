package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Pure-logic tests for the Coverage run's CLI-flag mapping: which formats the settings enable, the local path each one
 * writes to, and the flag spelling. The methods are on the runner, so the test extends it (no platform state needed).
 * Also pins the public runner/executor id constants referenced by plugin.xml.
 */
class TestoCoverageArgumentsTest : TestoCoverageProgramRunner() {

    private val base = "/tmp/report@cfg.xml"

    @Test
    fun defaultsRequestCoberturaAndCoverageXmlButNotClover() {
        val flags = coverageFlagLocalPaths(TestoRunnerSettings(), base)
        assertEquals(
            listOf(
                CoverageFormat.COBERTURA to "/tmp/report@cfg-cobertura.xml",
                CoverageFormat.COVERAGE_XML to "/tmp/report@cfg-coverage-xml",
            ),
            flags,
        )
    }

    @Test
    fun everyFormatEnabledYieldsThreeFlags() {
        val settings = TestoRunnerSettings(coverageClover = true)
        val flags = coverageFlagLocalPaths(settings, base)
        assertEquals(
            listOf("--coverage-clover=/tmp/report@cfg-clover.xml"),
            flags.filter { it.first == CoverageFormat.CLOVER }.map { coverageFlagFor(it.first, it.second) },
        )
        assertEquals(3, flags.size)
    }

    @Test
    fun noBasePathMeansNoFlags() {
        assertTrue(coverageFlagLocalPaths(TestoRunnerSettings(), null).isEmpty())
        assertTrue(coverageFlagLocalPaths(TestoRunnerSettings(), "").isEmpty())
    }

    @Test
    fun everyFormatDisabledMeansNoFlags() {
        val settings = TestoRunnerSettings(coverageCobertura = false, coverageXml = false)
        assertTrue(coverageFlagLocalPaths(settings, base).isEmpty())
    }

    @Test
    fun pathWithSpacesIsKeptVerbatimInSingleFlag() {
        assertEquals(
            "--coverage-cobertura=/path with space/r-cobertura.xml",
            coverageFlagFor(CoverageFormat.COBERTURA, "/path with space/r-cobertura.xml"),
        )
    }

    @Test
    fun flagDataFilesPointAtIndexXmlForCoverageXml() {
        val files = flagLocalDataFiles(
            listOf(
                CoverageFormat.COBERTURA to "/tmp/r-cobertura.xml",
                CoverageFormat.COVERAGE_XML to "/tmp/r-coverage-xml",
            )
        )
        assertEquals(Path.of("/tmp/r-cobertura.xml"), files[0])
        assertEquals(Path.of("/tmp/r-coverage-xml/index.xml"), files[1])
    }

    @Test
    fun coverageOnlyOptionsDefaultToExcludingBenchmarks() {
        assertEquals(listOf("--type=!bench"), extraCoverageArguments(TestoRunnerSettings()))
    }

    @Test
    fun coverageOnlyOptionsAreSplitLikeACommandLine() {
        val settings = TestoRunnerSettings(coverageOptions = """--type=!bench --filter "a b"""")

        assertEquals(listOf("--type=!bench", "--filter", "a b"), extraCoverageArguments(settings))
    }

    @Test
    fun emptyCoverageOnlyOptionsAddNothing() {
        assertTrue(extraCoverageArguments(TestoRunnerSettings(coverageOptions = "   ", coverageLevel = "auto")).isEmpty())
    }

    @Test
    fun autoLevelSendsNoLevelFlag() {
        assertTrue(extraCoverageArguments(TestoRunnerSettings()).none { it.startsWith("--coverage-level") })
    }

    @Test
    fun chosenLevelLeadsTheCoverageOnlyArguments() {
        val settings = TestoRunnerSettings(coverageLevel = "branch")

        assertEquals(listOf("--coverage-level=branch", "--type=!bench"), extraCoverageArguments(settings))
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
