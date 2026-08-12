package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.tabularAdvances
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit4 test for the digit slots that keep the toolbar row from jittering as its counters tick. */
class TabularAdvancesTest {

    // A caricature of a proportional font: the narrow one, every other digit wide, letters in between.
    private val widthOf = { ch: Char ->
        when (ch) {
            '1' -> 4
            in '0'..'9' -> 8
            ' ' -> 3
            else -> 6
        }
    }

    @Test
    fun everyDigitTakesTheWidestDigitsSlot() {
        assertArrayEquals(intArrayOf(8, 8), tabularAdvances("11", widthOf))
    }

    @Test
    fun otherCharactersKeepTheirOwnWidth() {
        assertArrayEquals(intArrayOf(8, 6, 8, 3, 6), tabularAdvances("1/7 s", widthOf))
    }

    @Test
    fun theWidthMovesOnlyWithTheDigitCount() {
        val widthOfText = { text: String -> tabularAdvances(text, widthOf).sum() }

        assertEquals(widthOfText("19 passed"), widthOfText("87 passed"))
        assertTrue(widthOfText("100") > widthOfText("99"))
    }
}
