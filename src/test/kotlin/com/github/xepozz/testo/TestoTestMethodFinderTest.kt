package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoTestMethodFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Method field of a run configuration is a selector, and the validator has to reduce it back to the name a file
 * can be searched for — otherwise a perfectly good run is stopped by a "Cannot find … in …" dialog.
 */
class TestoTestMethodFinderTest {

    @Test
    fun aPlainMethodNameIsItsOwnDeclaredName() {
        assertEquals("med", TestoTestMethodFinder.declaredName("med"))
    }

    @Test
    fun attributeAndDataSetCoordinatesAreStripped() {
        assertEquals("med", TestoTestMethodFinder.declaredName("med:1"))
        assertEquals("med", TestoTestMethodFinder.declaredName("med:1:0"))
    }

    @Test
    fun theClassOfAQualifiedSelectorIsStripped() {
        // What a run produced from a results-tree node holds: the whole string `--filter` takes. Splitting on the
        // first colon instead would leave the class FQN, which no function in the file is named.
        assertEquals("med", TestoTestMethodFinder.declaredName("\\Testo\\Bench\\Internal\\Calculator::med"))
        assertEquals("med", TestoTestMethodFinder.declaredName("\\Testo\\Bench\\Internal\\Calculator::med:1:0"))
        assertEquals("med", TestoTestMethodFinder.declaredName("\\Calculator::med:1:0"))
    }

    @Test
    fun anEmptySelectorNamesNothing() {
        assertNull(TestoTestMethodFinder.declaredName(""))
        assertNull(TestoTestMethodFinder.declaredName("   "))
        assertNull(TestoTestMethodFinder.declaredName("\\Ns\\Calculator::"))
    }
}
