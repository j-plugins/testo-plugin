package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.github.xepozz.testo.tests.console.TestoTargetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the rerun target a service message carries: turning a location hint back into the `--filter`
 * selector Testo built it from, and telling apart the nodes that need one from the nodes that do not.
 */
class TestoRunTargetTest {

    private fun hint(tail: String) = "php_qn://D:/project/src/Internal/Calculator.php::$tail"

    @Test
    fun theSelectorIsTheTailOfTheHint() {
        // Exactly the string `--filter` takes, coordinates and all — Testo builds the hint out of it.
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator::med:3:0",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator::med:3:0")),
        )
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator::med",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator::med")),
        )
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator")),
        )
    }

    @Test
    fun aWindowsDriveLetterIsNotASeparator() {
        // The path holds a single colon; only the doubled one separates the file from the selector.
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf("php_qn://D:/x/C.php::\\Ns\\C::it"))
    }

    @Test
    fun aHintPointingAtNothingButAFileHasNoSelector() {
        assertNull(TestoRunTarget.filterOf("php_qn://D:/project/testo.php"))
        assertNull(TestoRunTarget.filterOf(""))
    }

    @Test
    fun thePluginsOwnDisplayCoordinatesAreNotPartOfTheSelector() {
        // Line markers and history lookup append these; Testo never sends them, but a hint may arrive from either.
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf(hint("\\Ns\\C::it") + "#2"))
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf(hint("\\Ns\\C::it") + " with data set #3"))
    }

    @Test
    fun aSelectorOfAnyShapeCorrectsTheProducer() {
        // Whatever the hint names goes to `--filter` verbatim. A case is not exempt: the element-based path narrows it
        // to `--path <file>`, and a file may declare several cases.
        assertEquals("\\Ns\\C::it:1:2", TestoRunTarget(hint("\\Ns\\C::it:1:2")).filter)
        assertEquals("\\Ns\\C", TestoRunTarget(hint("\\Ns\\C")).filter)
        assertEquals("\\Ns\\freeFunction", TestoRunTarget(hint("\\Ns\\freeFunction")).filter)
    }

    @Test
    fun aNodeThatNarrowsNothingIsEmpty() {
        assertTrue(TestoRunTarget().isEmpty)
        assertFalse("a case hint narrows the run to that one case", TestoRunTarget(hint("\\Ns\\C")).isEmpty)
        assertFalse(TestoRunTarget(hint("\\Ns\\C::it")).isEmpty)
        assertFalse(TestoRunTarget(null, suite = "Unit").isEmpty)
        assertFalse(TestoRunTarget(null, type = "bench").isEmpty)
        assertTrue("blank attributes are as good as absent", TestoRunTarget(null, suite = " ", type = "").isEmpty)
        assertTrue("a hint naming only a file selects nothing", TestoRunTarget("php_qn://D:/p/testo.php").isEmpty)
    }

    @Test
    fun nodesOfTheSameNameKeepTheirOwnTargets() {
        val store = TestoTargetStore()
        // Every data provider of a run opens a `Dataset #0 [0]`; only the hint tells one from another.
        val first = hint("\\Ns\\A::it:0:0")
        val second = hint("\\Ns\\B::it:0:0")
        store.note(TestoRunTarget(first))
        store.note(TestoRunTarget(second))

        assertEquals("\\Ns\\A::it:0:0", store.targetFor(first)?.filter)
        assertEquals("\\Ns\\B::it:0:0", store.targetFor(second)?.filter)
    }

    @Test
    fun aNodeWithoutAHintIsNotWorthRemembering() {
        val store = TestoTargetStore()
        // A run-level suite: no location, so the platform runs no producer for it and there is nothing to hand over.
        store.note(TestoRunTarget(null, suite = "sandbox"))

        assertNull(store.targetFor(null))
        assertNull(store.targetFor(""))
    }

    @Test
    fun anEmptyTargetIsNotWorthRemembering() {
        val store = TestoTargetStore()
        // The config-file node: its hint names a file, which selects nothing the element-based path does not already.
        val fileHint = "php_qn://D:/project/testo.php"
        store.note(TestoRunTarget(fileHint))

        assertNull(store.targetFor(fileHint))
    }

    @Test
    fun aCaseIsWorthRememberingOnItsHintAlone() {
        val store = TestoTargetStore()
        val caseHint = hint("\\Ns\\TestLevelPipelineFailure")
        store.note(TestoRunTarget(caseHint))

        assertEquals("\\Ns\\TestLevelPipelineFailure", store.targetFor(caseHint)?.filter)
    }
}
