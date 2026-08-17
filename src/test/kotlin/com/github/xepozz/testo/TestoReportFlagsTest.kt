package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoReportFlags
import junit.framework.TestCase

class TestoReportFlagsTest : TestCase() {

    fun testBothReportsEmitBothFlags() {
        assertEquals(
            listOf("--log-html=/ide/report.html", "--log-junit=/ide/junit.xml"),
            TestoReportFlags.reportFlagArguments(true, true, "/ide/report.html", "/ide/junit.xml"),
        )
    }

    fun testHtmlOnlyEmitsHtmlFlag() {
        assertEquals(
            listOf("--log-html=/ide/report.html"),
            TestoReportFlags.reportFlagArguments(true, false, "/ide/report.html", "/ide/junit.xml"),
        )
    }

    fun testJunitOnlyEmitsJunitFlag() {
        assertEquals(
            listOf("--log-junit=/ide/junit.xml"),
            TestoReportFlags.reportFlagArguments(false, true, "/ide/report.html", "/ide/junit.xml"),
        )
    }

    fun testNeitherEmitsNothing() {
        assertTrue(TestoReportFlags.reportFlagArguments(false, false, "/ide/report.html", "/ide/junit.xml").isEmpty())
    }
}
