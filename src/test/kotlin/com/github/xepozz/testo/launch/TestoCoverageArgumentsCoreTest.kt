package com.github.xepozz.testo.launch

import com.github.xepozz.testo.coverage.format.CoverageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestoCoverageArgumentsCoreTest {
    private val base = "/work/coverage/Run@cfg.xml"

    @Test
    fun defaultsAskForCoberturaAndCoverageXmlOnBranchLevel() {
        val selection = TestoRunSelection()
        val flags = TestoCoverageArguments.flagLocalPaths(selection, base)

        assertEquals(
            listOf(
                CoverageFormat.COBERTURA to "/work/coverage/Run@cfg-cobertura.xml",
                CoverageFormat.COVERAGE_XML to "/work/coverage/Run@cfg-coverage-xml",
            ),
            flags,
        )
        assertEquals(
            listOf("--coverage-cobertura=/c.xml", "--coverage-xml=/x"),
            TestoCoverageArguments.reportArguments(flags, listOf("/c.xml", "/x")),
        )
        assertEquals(listOf("--coverage-level=branch", "--type=!bench"), TestoCoverageArguments.extraArguments(selection))
    }

    @Test
    fun noFormatsMeansABareCoverageFlag() {
        val selection = TestoRunSelection(coverageCobertura = false, coverageXml = false)

        assertEquals(listOf("--coverage"), TestoCoverageArguments.reportArguments(TestoCoverageArguments.flagLocalPaths(selection, base), emptyList()))
        assertEquals(listOf("--coverage"), TestoCoverageArguments.reportArguments(TestoCoverageArguments.flagLocalPaths(TestoRunSelection(), null), emptyList()))
    }

    @Test
    fun autoLevelIsBranchOnlyWithXdebugAndABranchCarryingFormat() {
        assertNull(TestoCoverageArguments.level(TestoRunSelection(coverageDriver = TestoCoverageDriver.PCOV)))
        assertNull(TestoCoverageArguments.level(TestoRunSelection(coverageCobertura = false)))
        assertEquals("branch", TestoCoverageArguments.level(TestoRunSelection(coverageCobertura = false, coverageClover = true)))
        assertEquals("line", TestoCoverageArguments.level(TestoRunSelection(coverageLevel = "line", coverageDriver = TestoCoverageDriver.PCOV)))
    }
}
