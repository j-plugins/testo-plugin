package com.github.xepozz.testo.launch

import com.github.xepozz.testo.tests.TestoLocationHints
import com.github.xepozz.testo.tests.TestoLocationHints.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestoLocationHintsTest {

    @Test
    fun addressesOfOneToThreeParts() {
        assertEquals(Parsed("a/CalcTest.php", null, null), TestoLocationHints.parse("a/CalcTest.php"))
        assertEquals(Parsed("a/CalcTest.php", "\\Ns\\CalcTest", null), TestoLocationHints.parse("a/CalcTest.php::\\Ns\\CalcTest"))
        assertEquals(Parsed("a/CalcTest.php", "\\Ns\\CalcTest", "med"), TestoLocationHints.parse("a/CalcTest.php::\\Ns\\CalcTest::med:3:0"))
        assertNull(TestoLocationHints.parse("a::b::c::d"))
        assertNull(TestoLocationHints.parse(""))
    }

    @Test
    fun coordinatesComeOffButTheFileKeepsItsColon() {
        assertEquals(Parsed("D:/p/Calculator.php", "\\Ns\\Calculator", "med"), TestoLocationHints.parse("D:/p/Calculator.php::\\Ns\\Calculator::med#2"))
        assertEquals("med", TestoLocationHints.stripTestoCoordinates("med with data set #1"))
        assertEquals("\\Ns\\medianOf", TestoLocationHints.stripTestoCoordinates("\\Ns\\medianOf:0:1"))
        assertNull(TestoLocationHints.stripTestoCoordinates(":0"))
    }
}
