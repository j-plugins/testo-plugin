package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.perTest.TestoCoverageKeys
import com.github.xepozz.testo.tests.console.TestoReportRef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** Pure tests for the one-report-per-format pick the coverage auto-apply makes (flag-written beats configured). */
class TestoCoverageDedupeTest {

    private fun ref(format: String, path: String) = TestoReportRef(format, path, null, null, null)

    @Test
    fun flagReportBeatsConfiguredOneOfTheSameFormat() {
        val configured = ref("clover", "/app/runtime/clover.xml") to Path.of("/app/runtime/clover.xml")
        val flagged = ref("clover", "/ide/cov/r-clover.xml") to Path.of("/ide/cov/r-clover.xml")
        val chosen = dedupeCoverageByFormat(
            listOf(flagged, configured),   // announce order must not matter
            setOf(TestoCoverageKeys.normalize("/ide/cov/r-clover.xml")),
        )
        assertEquals(listOf(flagged), chosen)
    }

    @Test
    fun withoutAFlagTheLastAnnouncedWins() {
        val first = ref("cobertura", "/a.xml") to Path.of("/a.xml")
        val second = ref("cobertura", "/b.xml") to Path.of("/b.xml")
        assertEquals(listOf(second), dedupeCoverageByFormat(listOf(first, second), emptySet()))
    }

    @Test
    fun formatsAreIndependent() {
        val clover = ref("clover", "/c.xml") to Path.of("/c.xml")
        val xml = ref("coverage-xml", "/x") to Path.of("/x/index.xml")
        val chosen = dedupeCoverageByFormat(listOf(clover, xml), emptySet())
        assertEquals(setOf(clover, xml), chosen.toSet())
    }

    @Test
    fun nonCoverageFormatsAreDropped() {
        val html = ref("html", "/report") to Path.of("/report/index.html")
        assertEquals(emptyList<Any>(), dedupeCoverageByFormat(listOf(html), emptySet()))
    }
}
