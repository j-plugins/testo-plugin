package com.github.xepozz.testo.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageCellTextTest {
    @Test
    fun percentageLeadsTheCounts() {
        assertEquals("85% (17/20)", CoverageTally(17, 20).cellText())
        assertEquals("100% (5/5)", CoverageTally(5, 5).cellText())
        assertEquals("0% (0/8)", CoverageTally(0, 8).cellText())
    }

    @Test
    fun onlyACompleteTallyReadsHundred() {
        assertEquals("99% (1999/2000)", CoverageTally(1999, 2000).cellText())
    }

    @Test
    fun emptyTallyHasNoCell() {
        assertNull(CoverageTally(0, 0).cellText())
    }
}
